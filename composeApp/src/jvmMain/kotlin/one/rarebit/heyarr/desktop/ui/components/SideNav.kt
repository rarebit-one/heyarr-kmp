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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import one.rarebit.heyarr.desktop.ui.Experience
import one.rarebit.heyarr.desktop.theme.RubikFamily
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.desktop.state.Connection
import one.rarebit.heyarr.desktop.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.desktop.ui.Route

/** A nav destination: label, icon, route, optional keyboard hint. */
data class NavItem(val route: Route, val label: String, val icon: ImageVector, val hint: String? = null)

val CONSUME_ITEMS = listOf(
    NavItem(Route.Consume(Experience.WATCH), "Watch", Icons.Rounded.Movie, "Ctrl 1"),
    NavItem(Route.Consume(Experience.LISTEN), "Listen", Icons.Rounded.Headphones, "Ctrl 2"),
    NavItem(Route.Consume(Experience.READ), "Read", Icons.Rounded.MenuBook, "Ctrl 3"),
)

val NAV_ITEMS = listOf(
    NavItem(Route.Home, "Home", Icons.Rounded.Home, "⌃1"),
    NavItem(Route.Discover, "Discover", Icons.Rounded.Explore, "⌃2"),
    NavItem(Route.Search, "Search", Icons.Rounded.Search, "⌘K"),
    NavItem(Route.Library, "Library", Icons.Rounded.VideoLibrary, "⌃3"),
    NavItem(Route.Missing, "Missing", Icons.Rounded.ReportProblem, "⌃4"),
    NavItem(Route.NowPlaying, "Cast", Icons.Rounded.Cast, "⌃5"),
    NavItem(Route.Settings, "Settings", Icons.Rounded.Settings, "⌘,"),
)

/** The rail's uppercase caption style: small sans, tracked. */
private val RAIL_LABEL = androidx.compose.ui.text.TextStyle(fontFamily = RubikFamily,fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, lineHeight = 12.sp)

/**
 * The left rail: a logo, then each destination as an icon over an uppercase caption,
 * and the connection at the foot. One width at every window size. The active item
 * gets a tinted tile in the accent of the media in focus.
 */
@Composable
fun SideNav(current: Route, onGo: (Route) -> Unit, connection: Connection, compact: Boolean = false, modifier: Modifier = Modifier, connectionDetail: String? = null, onConnection: () -> Unit = {}, consuming: Boolean = false) {
    val theme = LocalMediaTheme.current
    Column(
        modifier.fillMaxHeight().width(Tokens.navWidth).background(Tokens.surface1).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.size(40.dp).background(Brush.linearGradient(listOf(theme.ctaGradientStart, theme.accentGradientEnd)), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Text("h", style = MaterialTheme.typography.headlineMedium, color = theme.onAccent)
        }
        Spacer(Modifier.height(14.dp))
        for (item in if (consuming) CONSUME_ITEMS + NAV_ITEMS.filter { it.route == Route.Settings || it.route == Route.NowPlaying } else NAV_ITEMS) RailItem(item, active = current.section == item.route.section, accent = theme.accent, accentEnd = theme.accentGradientEnd) { onGo(item.route) }
        Spacer(Modifier.weight(1f))
        ConnectionTile(connection, connectionDetail, onConnection)
    }
}

@Composable
private fun RailItem(item: NavItem, active: Boolean, accent: Color, accentEnd: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(3.dp)
    val tile = when { active -> accent.copy(alpha = 0.18f); hovered -> Tokens.surface2; else -> Color.Transparent }
    val fg = when { active -> accentEnd; hovered -> Tokens.textPrimary; else -> Tokens.textMuted }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.contentDescription = item.label; this.selected = active }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(44.dp).focusRing(interaction, shape).background(tile, shape).border(Tokens.hairline, if (active) accent.copy(alpha = 0.45f) else Color.Transparent, shape), contentAlignment = Alignment.Center) {
            Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        }
        Text(item.label.uppercase(), style = RAIL_LABEL, color = if (active) accentEnd else Tokens.textMuted, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
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
        Modifier.fillMaxWidth().focusRing(interaction, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(if (hovered) Tokens.surface2 else Color.Transparent, RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = "heyarr connection: $label${detail?.let { ", $it" } ?: ""}. Open connection details" }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(36.dp).background(Tokens.surface2, CircleShape).border(Tokens.hairline, tone.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(tone, CircleShape))
        }
        Text(label.uppercase(), style = RAIL_LABEL, color = tone, maxLines = 1, softWrap = false)
        if (detail != null) Text(detail.substringBefore(" ·"), style = RAIL_LABEL.copy(letterSpacing = 0.2.sp, fontWeight = FontWeight.Normal), color = Tokens.textDisabled, maxLines = 1, softWrap = false)
    }
}
