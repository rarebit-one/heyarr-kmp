package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.mobile.catalog.WorksPage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-JVM pager for the library's incremental load: accumulates pages in [items], guards
 * against overlapping fetches with an [AtomicBoolean], and resets to page 1 on an explicit
 * [reset]. [fetchPage] is called synchronously on whatever thread invokes [loadNext]; the
 * caller is responsible for dispatching to an IO dispatcher (e.g. `viewModelScope + Dispatchers.IO`).
 *
 * Keeping this class free of Compose and coroutine state means the pager logic can be
 * exercised in plain-JVM unit tests without an Android runtime.
 */
class LibraryPager(private val fetchPage: (cursor: String?) -> WorksPage) {
    @Volatile var items: List<Work> = emptyList()
        private set

    @Volatile var isAtEnd: Boolean = false
        private set

    @Volatile private var cursor: String? = null

    // Bumped by every reset(); a fetch compares it before and after to tell whether its
    // page still belongs to the current list.
    @Volatile private var generation = 0
    private val lock = Any()

    private val inFlight = AtomicBoolean(false)

    /** True while a fetch is in progress. The UI uses this to suppress duplicate triggers. */
    val isInFlight: Boolean get() = inFlight.get()

    /**
     * Fetch the next page and append its items to [items]. Returns true if a fetch was
     * dispatched, false if one was already in flight or the end of the list was reached.
     * Any exception from [fetchPage] propagates to the caller; the guard is always
     * released in the `finally` block so the pager stays usable after an error.
     */
    fun loadNext(): Boolean {
        if (isAtEnd || !inFlight.compareAndSet(false, true)) return false
        try {
            // A reset() that lands while a page is in flight makes that page belong to the
            // list the user just threw away: drop it and fetch from the new start in the
            // same slot, so a Refresh pressed mid-scroll never splices a stale page in. The
            // generation check and the publish share [lock] with reset(), so a reset can't
            // slip in between them (Codex on #133).
            var published = false
            do {
                val gen = synchronized(lock) { generation }
                val page = fetchPage(cursor)
                synchronized(lock) {
                    if (gen == generation) {
                        items = items + page.items
                        cursor = page.nextCursor
                        isAtEnd = page.nextCursor == null
                        published = true
                    }
                }
            } while (!published)
            return true
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * The cursor that will be sent with the next [loadNext] call; null at the
     * start, after a [reset], or once [isAtEnd] is true. Exposed so the Compose
     * screen state can mirror it — needed to restore the pager after a tab switch
     * (the screen state survives, the `remember`-held pager does not).
     */
    val nextCursor: String? get() = cursor

    /**
     * Restore pager state from a previous incremental load. Call this once, right
     * after construction, when the composable is recreated after a tab switch and
     * [items] already live in the screen state. Without it the next [loadNext] call
     * would fetch from `cursor = null` (page 1) and overwrite the user's position.
     *
     * Not thread-safe by design: call only during the composition that creates this
     * pager, before [loadNext] can fire.
     */
    fun resume(existing: List<Work>, nextCursor: String?, isAtEnd: Boolean) {
        items = existing
        cursor = nextCursor
        this.isAtEnd = isAtEnd
    }

    /**
     * Reset to the beginning so the next [loadNext] starts from page 1. Calling this
     * while a fetch is in progress is safe: that fetch sees the new [generation], drops
     * its page and fetches page 1 instead, and its caller publishes the fresh list (a
     * concurrent [loadNext] returns false while it runs).
     */
    fun reset() = synchronized(lock) {
        generation++
        items = emptyList()
        cursor = null
        isAtEnd = false
    }
}
