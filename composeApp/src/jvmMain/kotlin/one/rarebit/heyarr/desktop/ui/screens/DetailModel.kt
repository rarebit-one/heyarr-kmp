package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.Season
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.HeroSkeleton
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.icon

/** What both faces of a work read: the session, where it was opened from, and what has loaded. */
internal class DetailModel(
    val session: AppSession,
    val route: Route.Detail,
    val state: DetailState,
    val type: MediaType,
    val wants: List<DesiredItem>,
    val seasons: List<Season>,
) {
    val title: String get() = state.detail?.work?.title ?: route.titleHint ?: "this title"
}

/**
 * The title page: the work and the thing itself, for watching, listening or reading. Manage sits
 * in the top bar, one click (or Ctrl+M) away.
 */
internal fun LazyListScope.titlePage(
    m: DetailModel,
    onBack: () -> Unit,
    onGetIt: (String, String, MediaType) -> Unit,
    onRetry: () -> Unit,
) {
    val detail = m.state.detail
    item {
        DetailTopBar(m.route.from, onBack) {
            if (detail != null) GhostButton("Manage", { m.state.managing = true }, icon = HeyarrIcons.Manage)
        }
    }
    item {
        when {
            m.state.loading && detail == null -> HeroSkeleton(340.dp)
            detail == null -> ErrorState("Couldn't load this work", m.state.detailError, onRetry = onRetry)
            else -> DetailHero(m, detail, onGetIt)
        }
    }
    if (detail == null) return
    when (m.type) {
        MediaType.SERIES -> item { SeasonsBlock(m.session, detail, m.seasons, m.state, m.wants) }
        MediaType.MUSIC, MediaType.AUDIOBOOK -> item { TracksBlock(m.session, detail, m.state) }
        MediaType.FEED, MediaType.PODCAST -> item { ArchiveBlock(m.session, m.state) }
        else -> item { FileBlock(m.session, detail, m.state) }
    }
}

/**
 * The same work's Manage view: wanting more of it, its releases, the rules that judged them, and
 * its health. Opened straight from Downloads or Missing, its back button returns there and the
 * title page is a button away; opened from the title page, back returns to it.
 */
internal fun LazyListScope.manageView(
    m: DetailModel,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    onReloadWants: () -> Unit,
) {
    val detail = m.state.detail
    val direct = m.route.curate
    item {
        DetailTopBar(if (direct) m.route.from else m.title, if (direct) onBack else ({ m.state.managing = false })) {
            if (direct) GhostButton("Title page", { m.state.managing = false }, icon = HeyarrIcons.Forward)
            if (!m.session.isGuest && detail != null) GhostButton("Remove", onRemove, icon = HeyarrIcons.Delete)
        }
    }
    item { SectionHeader("Manage “${m.title}”", icon = m.type.icon(), subtitle = "Releases, rules and health") }
    if (detail == null) {
        item {
            if (m.state.loading) {
                HeroSkeleton(
                    120.dp,
                )
            } else {
                ErrorState("Couldn't load this work", m.state.detailError, onRetry = null)
            }
        }
        return
    }
    if (m.type == MediaType.SERIES && GuestGate.allows(m.session.mode, Surface.WANT)) {
        item { SeasonWants(m) }
    }
    item { TabStrip(m.state.tab, onSelect = { m.state.tab = it }) }
    when (m.state.tab) {
        DetailTab.RELEASES -> item { ReleasesTab(m.session, m.wants, m.state, onReloadWants) }
        DetailTab.RULES -> item { RulesTab(m.session, m.wants) }
        DetailTab.HEALTH -> item { HealthTab(m.session, detail, m.type, m.wants, m.state, m.seasons) }
    }
}

/** Want more of a series, a season at a time — the panel the title page used to carry. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun SeasonWants(m: DetailModel) {
    val detail = m.state.detail ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(if (m.state.wantMenu) "Close" else "Want seasons…", {
            m.state.wantMenu = !m.state.wantMenu
        }, icon = HeyarrIcons.Add, compact = true)
        if (m.state.wantMenu) {
            WantSeasonsPanel(m.session, detail, calendarSeasons(m.seasons, m.state), m.wants)
        }
    }
}

/** The calendar: the library's seasons, plus any TVmaze knows that the library has never seen. */
internal fun calendarSeasons(seasons: List<Season>, state: DetailState): List<Season> {
    val extSeasons = state.externalEpisodes.map { it.season }.distinct()
        .filter { n -> seasons.none { it.number == n } }.sorted()
    return (seasons + extSeasons.map { Season(it, emptyList()) }).sortedWith(
        compareBy({ it.number == null }, { if (it.number == 0) Int.MAX_VALUE else it.number ?: 0 }),
    )
}
