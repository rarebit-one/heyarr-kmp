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
 * Home's context column: where to go next. Counts and wants live in Manage, not on the way in to
 * watching something.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun HomeContext(session: AppSession, onOpen: (Route) -> Unit) {
    OnItsWay(session, onOpen)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Browse")
        DashedDivider()
        ContextLink(HeyarrIcons.Watch, "Films and series") { onOpen(Route.Consume(Experience.WATCH)) }
        ContextLink(HeyarrIcons.Listen, "Music, podcasts and audiobooks") { onOpen(Route.Consume(Experience.LISTEN)) }
        ContextLink(HeyarrIcons.Read, "Books and articles") { onOpen(Route.Consume(Experience.READ)) }
        ContextLink(HeyarrIcons.Discover, "Find something new") { onOpen(Route.Discover) }
        if (!session.isGuest) ContextLink(HeyarrIcons.Manage, "Manage the collection") { onOpen(Route.Manage) }
    }
}

/**
 * What heyarr is still getting, as one line into Manage → Downloads. Counted from the want index
 * once it has answered, and left out at zero: nothing coming needs no announcement.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun OnItsWay(session: AppSession, onOpen: (Route) -> Unit) {
    if (session.isGuest || !session.indexLoaded) return
    val counts = remember(session.index) { insightCounts(session.index) }
    val coming = (counts[LibraryStatus.WANTED] ?: 0) + (counts[LibraryStatus.MISSING] ?: 0)
    if (coming == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("On the way")
        DashedDivider()
        ContextLink(
            HeyarrIcons.Download,
            if (coming ==
                1
            ) {
                "1 title heyarr is getting"
            } else {
                "$coming titles heyarr is getting"
            },
        ) {
            onOpen(Route.Downloads)
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun ContextLink(icon: ImageVector, label: String, onClick: () -> Unit) {
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
internal fun ContextRow(
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
