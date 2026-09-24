package one.rarebit.heyarr.core.state

import one.rarebit.heyarr.core.heyarr.DesiredItem

/**
 * What the library says about a work, derived ONLY from real want state — never from
 * personal history (which this client cannot see):
 *
 *  - [IN_LIBRARY]  a want on this work is content-satisfied (`AVAILABLE` / `FULLY_SATISFIED`).
 *  - [WANTED]      a want exists and heyarr is working on it (`SELECTED`, or any acquiring phase).
 *  - [MISSING]     a want exists and nothing acceptable has been found (`MISSING`).
 *  - [NOT_TRACKED] no want names this work.
 */
enum class LibraryStatus(val label: String) {
    IN_LIBRARY("In library"),
    WANTED("Wanted"),
    MISSING("Missing"),
    NOT_TRACKED("Not tracked"),
    ;

    companion object {
        private val SATISFIED = setOf("AVAILABLE", "FULLY_SATISFIED", "SATISFIED")
        private val IN_PROGRESS = setOf("SELECTED", "ACQUIRING", "DOWNLOADING", "FETCHING", "VERIFYING", "PLACING")

        /** The status one acquisition [state] word means. Unknown words count as in progress, never as satisfied. */
        fun ofState(state: String): LibraryStatus = when (state.uppercase()) {
            in SATISFIED -> IN_LIBRARY
            "MISSING" -> MISSING
            in IN_PROGRESS -> WANTED
            else -> WANTED
        }

        /** Several wants on one work: the best-satisfied one wins (held beats wanted beats missing). */
        fun combine(states: Collection<LibraryStatus>): LibraryStatus = when {
            states.isEmpty() -> NOT_TRACKED
            IN_LIBRARY in states -> IN_LIBRARY
            WANTED in states -> WANTED
            MISSING in states -> MISSING
            else -> NOT_TRACKED
        }
    }
}

/**
 * The work → status index the search results and library grid read. Built from one
 * `GET /desired` page-walk; refreshed after every Want/Monitor action.
 */
class LibraryIndex(val items: List<DesiredItem>) {
    private val byWork: Map<String, List<DesiredItem>> = items.filter { it.workId != null }.groupBy { it.workId!! }

    fun statusOf(workId: String): LibraryStatus =
        LibraryStatus.combine(byWork[workId].orEmpty().map { LibraryStatus.ofState(it.state) })

    fun wantsFor(workId: String): List<DesiredItem> = byWork[workId].orEmpty()

    /** An optimistic copy that shows [workId] as wanted before the server confirms. */
    fun withPendingWant(workId: String, qualityProfileId: String?): LibraryIndex = LibraryIndex(
        items + DesiredItem(
            id = "pending:$workId", workId = workId, qualityProfileId = qualityProfileId, monitor = true, reason = null,
            state = "SELECTED", phase = "pending", content = null, placement = null, detail = "Sending…", updatedAt = null,
        ),
    )

    companion object {
        val EMPTY = LibraryIndex(emptyList())
    }
}
