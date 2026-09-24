package one.rarebit.heyarr.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.mcp.SearchHits
import one.rarebit.heyarr.core.state.SearchBackend
import one.rarebit.heyarr.core.state.SearchFilter
import one.rarebit.heyarr.core.state.SearchRow
import one.rarebit.heyarr.core.state.SearchSection
import one.rarebit.heyarr.core.state.SearchState
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.core.state.SearchController as SharedSearchController

/**
 * The desktop's Compose adapter over `:core`'s [SharedSearchController], which owns the
 * universal-search logic (debounce, per-kind fan-out, followed-source cache, keyboard
 * selection). This class only mirrors that controller's [SearchState] flow into snapshot
 * state for the screens, and adapts [HeyarrApi] to the [SearchBackend] seam.
 *
 * Each call syncs the mirror right away as well as through the collector, so the search
 * box's value changes on the same frame as the keystroke, as it did when the state here
 * was snapshot state.
 */
class SearchController(
    scope: CoroutineScope,
    api: () -> HeyarrApi?,
    onTransportFailure: (McpTransportException) -> Unit,
    debounceMs: Long = 220,
) {
    private val shared = SharedSearchController(
        scope = scope,
        backend = { api()?.let(::ApiSearchBackend) },
        onTransportFailure = onTransportFailure,
        ioDispatcher = Dispatchers.IO,
        debounceMs = debounceMs,
    )
    private var state: SearchState by mutableStateOf(shared.state.value)

    init {
        scope.launch { shared.state.collect { state = it } }
    }

    val query: String get() = state.query
    var filter: SearchFilter
        get() = state.filter
        set(value) = sync { shared.setFilter(value) }
    var selected: Int
        get() = state.selected
        set(value) = sync { shared.setSelected(value) }
    val sections: List<SearchSection> get() = state.sections
    val rows: List<SearchRow> get() = state.rows
    val isIdle: Boolean get() = state.isIdle
    val isSettled: Boolean get() = state.isSettled

    fun updateQuery(q: String) = sync { shared.updateQuery(q) }

    fun submit() = sync { shared.submit() }

    fun moveSelection(delta: Int) = sync { shared.moveSelection(delta) }

    fun selectedRow(): SearchRow? = shared.selectedRow()

    /** Forget the cached followed list (after a follow/unfollow). */
    fun invalidateSources() = shared.invalidateSources()

    private inline fun sync(action: () -> Unit) {
        action()
        state = shared.state.value
    }
}

/** The desktop's [HeyarrApi] already speaks `:core`'s types, so this is a straight pass-through. */
private class ApiSearchBackend(private val api: HeyarrApi) : SearchBackend {
    override fun searchContent(query: String, type: MediaType?): SearchHits = api.searchContent(query, type)

    override fun followedSources(): List<FollowedSource> = api.followed()
}
