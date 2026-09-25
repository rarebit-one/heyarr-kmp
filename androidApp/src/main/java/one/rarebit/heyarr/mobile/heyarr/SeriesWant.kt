package one.rarebit.heyarr.mobile.heyarr
import one.rarebit.heyarr.core.heyarr.DesiredItem

/**
 * What the desired state says about a *series* as a whole (ADR-0089).
 *
 * A work-scoped `want_content` on a series no longer creates a bare, forever-`UNKNOWN`
 * want: the node resolves the series' metadata id and establishes a `FollowedSource`
 * whose poll loop enumerates every episode as an **item-scoped** want (and monitors for
 * new ones). Wanting a series *is* following it. So:
 *
 *  - [following] — the series is a standing subscription: at least one item-scoped want
 *    names it (the episodes the follow's poll has projected). This is the signal
 *    heyarr-desktop reads too, so the two clients agree on when a series is "followed".
 *  - [wholeSeriesWanted] — a work-scoped want exists. On a node with no metadata
 *    provider the bridge falls back to today's bare work want (ADR-0089 §2), so this can
 *    be true before [following] catches up; it upgrades to a full follow once a provider
 *    is configured and the next poll runs.
 *
 * [everythingCovered] is either: the "want the whole series" door is done.
 *
 * Pure and dependency-free on purpose — a natural KMP `commonMain` candidate, and unit
 * tested against the scope strings the REST `GET /desired` view emits.
 */
data class SeriesWantState(val following: Boolean, val wholeSeriesWanted: Boolean) {
    val everythingCovered: Boolean get() = following || wholeSeriesWanted
}

/** Fold a work's [wants] (as grouped by `LibraryIndex.wantsFor`) into its [SeriesWantState]. */
fun seriesWantState(wants: List<DesiredItem>): SeriesWantState = SeriesWantState(
    following = wants.any { it.scope == "item" },
    wholeSeriesWanted = wants.any { it.scope == "work" },
)
