package one.rarebit.heyarr.mobile.state

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
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.search.asFeedSource
import one.rarebit.heyarr.core.state.SearchController as SharedSearchController

/**
 * Android's Compose adapter over `:core`'s [SharedSearchController], which owns the
 * universal-search logic (debounce, per-kind fan-out, followed-source cache). This class
 * only mirrors that controller's [SearchState] flow into snapshot state for the screen,
 * and adapts [HeyarrApi] to the [SearchBackend] seam. The phone has no keyboard
 * selection, so it doesn't expose the shared controller's `selected` state.
 *
 * Each call syncs the mirror right away as well as through the collector, so the search
 * box's value changes on the same frame as the keystroke, as it did when the state here
 * was snapshot state.
 */
class SearchController(
    scope: CoroutineScope,
    api: () -> HeyarrApi,
    onTransportFailure: (McpTransportException) -> Unit,
    debounceMs: Long = 220,
) {
    private val shared = SharedSearchController(
        scope = scope,
        backend = { ApiSearchBackend(api()) },
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
    val sections: List<SearchSection> get() = state.sections
    val rows: List<SearchRow> get() = state.rows
    val isIdle: Boolean get() = state.isIdle
    val isSettled: Boolean get() = state.isSettled

    fun updateQuery(q: String) = sync { shared.updateQuery(q) }

    fun submit() = sync { shared.submit() }

    /** Forget the cached followed list (after a follow/unfollow). */
    fun invalidateSources() = shared.invalidateSources()

    private inline fun sync(action: () -> Unit) {
        action()
        state = shared.state.value
    }
}

/**
 * Android's [HeyarrApi] still returns its own followed-source type, so the seam maps it
 * to `:core`'s at the search boundary with [asFeedSource].
 */
private class ApiSearchBackend(private val api: HeyarrApi) : SearchBackend {
    override fun searchContent(query: String, type: MediaType?): SearchHits = api.searchContent(query, type)

    override fun followedSources(): List<FollowedSource> = api.followed().map { it.asFeedSource() }
}
