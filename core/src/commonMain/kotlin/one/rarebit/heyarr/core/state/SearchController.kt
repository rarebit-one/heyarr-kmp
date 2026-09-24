package one.rarebit.heyarr.core.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.mcp.SearchHits
import one.rarebit.heyarr.core.theme.MediaType

/**
 * The two heyarr reads universal search needs: the seam between the shared
 * [SearchController] and each app's own `HeyarrApi`. Both calls are blocking. The
 * controller runs them on the I/O dispatcher it is given.
 *
 * Each app adapts its `HeyarrApi` to this interface, rather than having `HeyarrApi`
 * implement it, because the apps don't agree on the followed-source type yet: Android
 * still maps its own `FollowedSource` to `:core`'s with `asFeedSource()`.
 */
interface SearchBackend {
    /** `search_content` for [query], narrowed to [type], or untyped (the episode hits) when null. */
    fun searchContent(query: String, type: MediaType?): SearchHits

    /** `list_followed`, as `:core`'s [FollowedSource]. */
    fun followedSources(): List<FollowedSource>
}

/**
 * Everything the search screen renders, as one immutable value, so an app's adapter
 * can mirror it in a single piece of UI state. [sections], [rows], [isIdle] and
 * [isSettled] are derived on read, as they were when each app held its own copy.
 */
data class SearchState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val segments: Map<MediaType, Segment> = emptyMap(),
    val episodes: Segment = Segment.Loaded(emptyList()),
    val sources: Segment = Segment.Loaded(emptyList()),
    /** The keyboard-highlighted row in [rows], or -1. Only the desktop drives it. */
    val selected: Int = -1,
) {
    val sections: List<SearchSection> get() = SearchGrouping.group(segments, episodes, sources, filter)
    val rows: List<SearchRow> get() = SearchGrouping.flatten(sections)
    val isIdle: Boolean get() = query.isBlank()
    val isSettled: Boolean get() = SearchGrouping.settled(sections)
}

/**
 * Universal search: one query, every media kind at once. On each keystroke the query is
 * debounced, then fanned out as one `search_content` per searchable kind, plus one
 * untyped call for episode hits, plus a client-side match over the followed sources —
 * all in parallel, each landing in its own [Segment] the moment it returns. A slow
 * kind therefore never holds the others back, and a failed one shows its own error
 * inside its section instead of blanking the page.
 *
 * Shared by both apps. It has no Compose: the state is a [StateFlow], and each app's thin
 * Compose adapter mirrors it into snapshot state. A null [backend] (the desktop before a
 * session exists) makes a search a no-op, while the query itself is still recorded.
 */
class SearchController(
    private val scope: CoroutineScope,
    private val backend: () -> SearchBackend?,
    private val onTransportFailure: (McpTransportException) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    private val debounceMs: Long = 220,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var followed: List<FollowedSource>? = null
    private var debounce: Job? = null
    private var inFlight: List<Job> = emptyList()
    private var generation = 0

    fun updateQuery(q: String) {
        _state.update { it.copy(query = q, selected = -1) }
        debounce?.cancel()
        if (q.isBlank()) {
            cancelInFlight()
            _state.update {
                it.copy(
                    segments = emptyMap(),
                    episodes = Segment.Loaded(emptyList()),
                    sources = Segment.Loaded(emptyList()),
                )
            }
            return
        }
        debounce = scope.launch {
            delay(debounceMs)
            run(q)
        }
    }

    fun submit() {
        debounce?.cancel()
        val q = _state.value.query
        if (q.isNotBlank()) run(q)
    }

    fun setFilter(filter: SearchFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setSelected(index: Int) {
        _state.update { it.copy(selected = index) }
    }

    fun moveSelection(delta: Int) {
        _state.update { s ->
            val n = s.rows.size
            if (n == 0) {
                s.copy(selected = -1)
            } else {
                s.copy(selected = ((if (s.selected < 0 && delta < 0) 0 else s.selected) + delta).mod(n))
            }
        }
    }

    fun selectedRow(): SearchRow? = _state.value.let { it.rows.getOrNull(it.selected) }

    /** Forget the cached followed list (after a follow/unfollow). */
    fun invalidateSources() {
        followed = null
    }

    private fun cancelInFlight() {
        inFlight.forEach { it.cancel() }
        inFlight = emptyList()
    }

    private fun run(q: String) {
        val b = backend() ?: return
        cancelInFlight()
        val gen = ++generation
        _state.update {
            it.copy(
                segments = MediaType.SEARCHABLE.associateWith { Segment.Pending },
                episodes = Segment.Pending,
                sources = Segment.Pending,
            )
        }
        val jobs = ArrayList<Job>()
        for (kind in MediaType.SEARCHABLE) {
            jobs += scope.launch {
                val seg = fetch { b.searchContent(q, kind) }.fold(
                    onSuccess = { hits -> Segment.Loaded(hits.works.map { SearchRow.WorkRow(it) }, hits.truncated) },
                    onFailure = { Segment.Failed(it.message ?: "failed") },
                )
                if (gen == generation) _state.update { it.copy(segments = it.segments + (kind to seg)) }
            }
        }
        jobs += scope.launch {
            val seg = fetch { b.searchContent(q, null) }.fold(
                onSuccess = { hits -> Segment.Loaded(hits.episodes.map { SearchRow.EpisodeRow(it) }) },
                onFailure = { Segment.Failed(it.message ?: "failed") },
            )
            if (gen == generation) _state.update { it.copy(episodes = seg) }
        }
        jobs += scope.launch {
            val list = followed ?: fetch { b.followedSources() }.getOrNull()?.also { followed = it }
            val seg = if (list == null) {
                Segment.Failed("followed sources unavailable")
            } else {
                Segment.Loaded(SearchGrouping.matchSources(q, list))
            }
            if (gen == generation) _state.update { it.copy(sources = seg) }
        }
        inFlight = jobs
    }

    private suspend fun <T> fetch(block: () -> T): Result<T> {
        val result = withContext(ioDispatcher) { runCatching(block) }
        return result.onFailure { if (it is McpTransportException) onTransportFailure(it) }
    }
}
