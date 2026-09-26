package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.desktop.state.Connection
import one.rarebit.heyarr.desktop.ui.Experience
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.EditorialRule
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.ui.theme.HeyarrFonts
import one.rarebit.heyarr.ui.theme.Tokens

/** A nav destination: label, icon, route, optional keyboard hint. */
data class NavItem(val route: Route, val label: String, val icon: ImageVector, val hint: String? = null)

/** Connection status and the action that opens its details sheet. */
data class NavConnectionInfo(val state: Connection, val detail: String?, val onOpen: () -> Unit)

val CONSUME_ITEMS = listOf(
    NavItem(Route.Consume(Experience.WATCH), "Watch", Icons.Rounded.Movie, "Ctrl 1"),
    NavItem(Route.Consume(Experience.LISTEN), "Listen", Icons.Rounded.Headphones, "Ctrl 2"),
    NavItem(Route.Consume(Experience.READ), "Read", Icons.Rounded.MenuBook, "Ctrl 3"),
)

val NAV_ITEMS = listOf(
    NavItem(Route.Home, "Home", Icons.Rounded.Home, "⌃1"),
    NavItem(Route.Search, "Search", Icons.Rounded.Search, "Ctrl+F"),
    NavItem(Route.Library, "Library", Icons.Rounded.VideoLibrary, "⌃3"),
    NavItem(Route.Missing, "Missing", Icons.Rounded.ReportProblem, "⌃4"),
    NavItem(Route.NowPlaying, "Cast", Icons.Rounded.Cast, "⌃5"),
    NavItem(Route.Settings, "Settings", Icons.Rounded.Settings, "⌘,"),
)

/** The rail's uppercase caption style: small sans, tracked. */
private val RAIL_LABEL: androidx.compose.ui.text.TextStyle
    @Composable get() = androidx.compose.ui.text.TextStyle(
        fontFamily = HeyarrFonts.rubik,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        lineHeight = 12.sp,
    )

/**
 * The left rail: a logo, then each destination as an icon over an uppercase caption,
 * and the connection at the foot. One width at every window size. The active item
 * gets a tinted tile in the accent of the media in focus.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun SideNav(current: Route, onGo: (Route) -> Unit, connection: NavConnectionInfo, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxHeight().width(
            Tokens.navWidth,
        ).background(Tokens.surface1).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        one.rarebit.heyarr.ui.components.ArchiveMark(Modifier.padding(4.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            for (item in CONSUME_ITEMS) {
                RailItem(item, active = current.section == item.route.section, accent = Tokens.accent) {
                    onGo(item.route)
                }
            }
            Spacer(Modifier.height(4.dp))
            EditorialRule(Modifier.padding(horizontal = 10.dp), dashed = true)
            Spacer(Modifier.height(4.dp))
            for (item in NAV_ITEMS) {
                val selected = when {
                    current == Route.Discover -> item.route == Route.Search
                    current.section == item.route.section -> true
                    else -> false
                }
                RailItem(item, active = selected, accent = Tokens.accent) {
                    onGo(item.route)
                }
            }
        }
        ConnectionTile(connection.state, connection.detail, connection.onOpen)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun RailItem(item: NavItem, active: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RectangleShape
    val tile = when {
        active -> accent.copy(alpha = 0.1f)
        hovered -> Tokens.surface2
        else -> Color.Transparent
    }
    val fg = when {
        active -> Tokens.accentGradEnd
        hovered -> Tokens.textPrimary
        else -> Tokens.textMuted
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.radiusButton))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics {
                this.contentDescription = item.label
                this.selected = active
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(38.dp).focusRing(
                interaction,
                shape,
            ).background(
                tile,
                shape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            if (active) {
                Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(14.dp).background(accent))
            }
        }
        Text(
            item.label.uppercase(),
            style = RAIL_LABEL,
            color = if (active) Tokens.accentGradEnd else Tokens.textMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ConnectionTile(connection: Connection, detail: String?, onClick: () -> Unit) {
    val (tone, label) = when (connection) {
        Connection.ONLINE -> Tokens.success to "Online"
        Connection.OFFLINE -> Tokens.danger to "Offline"
        Connection.UNAUTHORIZED -> Tokens.warning to "Refused"
        Connection.UNCONFIGURED -> Tokens.textDisabled to "Set up"
        Connection.UNKNOWN -> Tokens.textDisabled to "Connecting"
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.radiusButton))
            .background(if (hovered) Tokens.surface2 else Color.Transparent, RoundedCornerShape(Tokens.radiusButton))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription =
                    "heyarr connection: $label${detail?.let { ", $it" } ?: ""}. Open connection details"
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(
                36.dp,
            ).focusRing(
                interaction,
                RectangleShape,
            ).background(
                Tokens.surface2,
                RectangleShape,
            ).border(Tokens.hairline, tone.copy(alpha = 0.6f), RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).background(tone, RectangleShape))
        }
        Text(label.uppercase(), style = RAIL_LABEL, color = tone, maxLines = 1, softWrap = false)
        if (detail !=
            null
        ) {
            Text(
                detail.substringBefore(" ·"),
                style = RAIL_LABEL.copy(letterSpacing = 0.2.sp, fontWeight = FontWeight.Normal),
                color = Tokens.textDisabled,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
