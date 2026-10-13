package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.state.LibraryIndex
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Experience
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.ArchiveLoading
import one.rarebit.heyarr.ui.components.DashedDivider
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.SectionLabel
import one.rarebit.heyarr.ui.components.StatusGlyph
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * Home's context column: what the collection looks like, and where to go next. Every count is
 * read from the want index (`GET /desired`); none is estimated. A guest has no want index, so the
 * insight is left out rather than shown as zeros.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun HomeContext(session: AppSession, onOpen: (Route) -> Unit) {
    // Counts only once the want index has answered: before that (or if it failed) the empty
    // stand-in would read as an authoritative "0 of everything".
    when {
        session.isGuest -> Unit
        session.indexLoaded -> CollectionInsight(session.index, onOpen)
        session.indexLoading -> ArchiveLoading("Counting the collection…")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Browse")
        DashedDivider()
        ContextLink(HeyarrIcons.Watch, "Films and series") { onOpen(Route.Consume(Experience.WATCH)) }
        ContextLink(HeyarrIcons.Listen, "Music, podcasts and audiobooks") { onOpen(Route.Consume(Experience.LISTEN)) }
        ContextLink(HeyarrIcons.Read, "Books and articles") { onOpen(Route.Consume(Experience.READ)) }
        ContextLink(HeyarrIcons.Discover, "Find something new") { onOpen(Route.Discover) }
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Collection insight")
        DashedDivider()
        InsightRow(LibraryStatus.IN_LIBRARY, counts[LibraryStatus.IN_LIBRARY] ?: 0, "held as wanted", Tokens.accent) {
            onOpen(Route.Library)
        }
        InsightRow(LibraryStatus.WANTED, counts[LibraryStatus.WANTED] ?: 0, "being acquired", Tokens.ochre) {
            onOpen(Route.Missing)
        }
        InsightRow(LibraryStatus.MISSING, counts[LibraryStatus.MISSING] ?: 0, "nothing acceptable yet", Tokens.danger) {
            onOpen(Route.Missing)
        }
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
private fun ContextLink(icon: ImageVector, label: String, onClick: () -> Unit) {
    ContextRow(onClick) {
        Icon(icon, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            HeyarrIcons.Forward,
            contentDescription = null,
            tint = Tokens.textDisabled,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun ContextRow(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().focusRing(interaction, RectangleShape)
            .background(if (hovered) Tokens.surface2 else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
