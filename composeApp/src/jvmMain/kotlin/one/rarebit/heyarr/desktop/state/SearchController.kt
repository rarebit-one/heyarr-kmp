package one.rarebit.heyarr.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.state.*
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi

/**
 * Universal search: one query, every media kind at once. On each keystroke the query is
 * debounced, then fanned out as one `search_content` per searchable kind, plus one
 * untyped call for episode hits, plus a client-side match over the followed sources —
 * all in parallel, each landing in its own [Segment] the moment it returns. A slow
 * kind therefore never holds the others back, and a failed one shows its own error
 * inside its section instead of blanking the page.
 */
class SearchController(
    private val scope: CoroutineScope,
    private val api: () -> HeyarrApi?,
    private val onTransportFailure: (McpTransportException) -> Unit,
    private val debounceMs: Long = 220,
) {
    var query: String by mutableStateOf("")
        private set
    var filter: SearchFilter by mutableStateOf(SearchFilter.ALL)
    var segments: Map<MediaType, Segment> by mutableStateOf(emptyMap())
        private set
    var episodes: Segment by mutableStateOf(Segment.Loaded(emptyList()))
        private set
    var sources: Segment by mutableStateOf(Segment.Loaded(emptyList()))
        private set
    var selected: Int by mutableStateOf(-1)

    private var followed: List<FollowedSource>? = null
    private var debounce: Job? = null
    private var inFlight: List<Job> = emptyList()
    private var generation = 0

    val sections: List<SearchSection> get() = SearchGrouping.group(segments, episodes, sources, filter)
    val rows: List<SearchRow> get() = SearchGrouping.flatten(sections)
    val isIdle: Boolean get() = query.isBlank()
    val isSettled: Boolean get() = SearchGrouping.settled(sections)

    fun updateQuery(q: String) {
        query = q
        selected = -1
        debounce?.cancel()
        if (q.isBlank()) {
            cancelInFlight()
            segments = emptyMap()
            episodes = Segment.Loaded(emptyList())
            sources = Segment.Loaded(emptyList())
            return
        }
        debounce = scope.launch {
            delay(debounceMs)
            run(q)
        }
    }

    fun submit() {
        debounce?.cancel()
        if (query.isNotBlank()) run(query)
    }

    fun moveSelection(delta: Int) {
        val n = rows.size
        if (n == 0) {
            selected = -1
            return
        }
        selected = ((if (selected < 0 && delta < 0) 0 else selected) + delta).mod(n)
    }

    fun selectedRow(): SearchRow? = rows.getOrNull(selected)

    private fun cancelInFlight() {
        inFlight.forEach { it.cancel() }
        inFlight = emptyList()
    }

    private fun run(q: String) {
        val a = api() ?: return
        cancelInFlight()
        val gen = ++generation
        segments = MediaType.SEARCHABLE.associateWith { Segment.Pending }
        episodes = Segment.Pending
        sources = Segment.Pending
        val jobs = ArrayList<Job>()
        for (kind in MediaType.SEARCHABLE) {
            jobs += scope.launch {
                val seg = fetch { a.searchContent(q, kind) }.fold(
                    onSuccess = { hits -> Segment.Loaded(hits.works.map { SearchRow.WorkRow(it) }, hits.truncated) },
                    onFailure = { Segment.Failed(it.message ?: "failed") },
                )
                if (gen == generation) segments = segments + (kind to seg)
            }
        }
        jobs += scope.launch {
            val seg = fetch { a.searchContent(q, null) }.fold(
                onSuccess = { hits -> Segment.Loaded(hits.episodes.map { SearchRow.EpisodeRow(it) }) },
                onFailure = { Segment.Failed(it.message ?: "failed") },
            )
            if (gen == generation) episodes = seg
        }
        jobs += scope.launch {
            val list = followed ?: fetch { a.followed() }.getOrNull()?.also { followed = it }
            val seg = if (list == null) {
                Segment.Failed("followed sources unavailable")
            } else {
                Segment.Loaded(SearchGrouping.matchSources(q, list))
            }
            if (gen == generation) sources = seg
        }
        inFlight = jobs
    }

    private suspend fun <T> fetch(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching(block) }.onFailure { if (it is McpTransportException) onTransportFailure(it) }

    /** Forget the cached followed list (after a follow/unfollow). */
    fun invalidateSources() {
        followed = null
    }
}
