package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.state.LibraryIndex
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.ArchiveLoading
import one.rarebit.heyarr.ui.components.DashedDivider
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.SectionLabel
import one.rarebit.heyarr.ui.components.StatusGlyph
import one.rarebit.heyarr.ui.components.UnderlineTabs
import one.rarebit.heyarr.ui.theme.Tokens

/** The curator workspace's sections, in the order its tab strip shows them. */
internal enum class ManageSection(val label: String, val route: Route, val icon: ImageVector, val blurb: String) {
    OVERVIEW("Overview", Route.Manage, HeyarrIcons.Manage, "Counts across every want."),
    MISSING(
        "Missing",
        Route.Missing,
        HeyarrIcons.Missing,
        "Wants with nothing acceptable yet, and ones that could be better.",
    ),
    DOWNLOADS("Downloads", Route.Downloads, HeyarrIcons.Download, "What heyarr is fetching now, and the job queue."),
    LIBRARY("Library", Route.Library, HeyarrIcons.Library, "Every catalogued work, filterable by want status."),
    SOURCES("Sources", Route.Sources, HeyarrIcons.Sources, "Feeds and series heyarr follows and archives."),
    ;

    companion object {
        fun of(route: Route): ManageSection? = entries.firstOrNull { it.route == route }
    }
}

/**
 * The frame every curator screen sits in: its sections as tabs over the screen itself, so the
 * workspace reads as one place reached through the Manage door rather than five rail items.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun ManageFrame(current: Route, onOpen: (Route) -> Unit, content: @Composable () -> Unit) {
    val section = ManageSection.of(current) ?: ManageSection.OVERVIEW
    Column(Modifier.fillMaxSize()) {
        UnderlineTabs(
            ManageSection.entries.map { it.label },
            section.ordinal,
            { onOpen(ManageSection.entries[it].route) },
            Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 20.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/**
 * Manage's landing: what the wants add up to, and a way into each section. Every count is read
 * from the want index (`GET /desired`); none is estimated, and a guest — who has no want index —
 * sees the sections without numbers rather than a row of zeros.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun ManageOverview(session: AppSession, onOpen: (Route) -> Unit, onWantTitle: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SectionHeader(
            "Manage",
            subtitle = "What heyarr keeps, fetches and follows",
            trailing = { PrimaryButton("Want by title", onWantTitle, icon = HeyarrIcons.Add, compact = true) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.widthIn(max = 360.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    session.isGuest -> Text(
                        "Sign in to see what is wanted and being fetched.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.textMuted,
                    )

                    // Counts only once the want index has answered: before that (or if it failed)
                    // the empty stand-in would read as an authoritative "0 of everything".
                    session.indexLoaded -> CollectionInsight(session.index, onOpen)

                    session.indexLoading -> ArchiveLoading("Counting the collection…")
                }
            }
            Column(Modifier.widthIn(max = 460.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Sections")
                DashedDivider()
                for (s in ManageSection.entries.drop(1)) SectionLink(s) { onOpen(s.route) }
            }
        }
    }
}

/** Manage → Downloads: the wants in flight and the job queue, on a page of its own. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun ManageDownloads(
    session: AppSession,
    state: DownloadsState,
    onOpen: (Route) -> Unit,
    catalogWorks: List<Work>?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Downloads", subtitle = "What heyarr is fetching right now")
        DownloadsScreen(session, state, onOpen, catalogWorks)
    }
}

/** Works per status across the wants, counted per work as the library grid shows them. */
internal fun insightCounts(index: LibraryIndex): Map<LibraryStatus, Int> =
    index.items.mapNotNull { it.workId }.distinct()
        .groupingBy { index.statusOf(it) }.eachCount()

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun CollectionInsight(index: LibraryIndex, onOpen: (Route) -> Unit) {
    val counts = remember(index) { insightCounts(index) }
    SectionLabel("Collection insight")
    DashedDivider()
    InsightRow(LibraryStatus.IN_LIBRARY, counts[LibraryStatus.IN_LIBRARY] ?: 0, "held as wanted", Tokens.accent) {
        onOpen(Route.Library)
    }
    InsightRow(LibraryStatus.WANTED, counts[LibraryStatus.WANTED] ?: 0, "being acquired", Tokens.ochre) {
        onOpen(Route.Downloads)
    }
    InsightRow(LibraryStatus.MISSING, counts[LibraryStatus.MISSING] ?: 0, "nothing acceptable yet", Tokens.danger) {
        onOpen(Route.Missing)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun InsightRow(status: LibraryStatus, count: Int, caption: String, tone: Color, onClick: () -> Unit) {
    ContextRow(onClick) {
        StatusGlyph(status, tone, Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
            Text(
                "${status.label.uppercase()} · $caption",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun SectionLink(section: ManageSection, onClick: () -> Unit) {
    ContextRow(onClick) {
        Icon(section.icon, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(section.label, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
            Text(section.blurb, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        Icon(
            HeyarrIcons.Forward,
            contentDescription = null,
            tint = Tokens.textDisabled,
            modifier = Modifier.size(14.dp),
        )
    }
}
