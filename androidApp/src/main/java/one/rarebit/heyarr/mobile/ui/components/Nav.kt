package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.mobile.state.Connection
import one.rarebit.heyarr.mobile.theme.LocalMediaTheme
import one.rarebit.heyarr.mobile.theme.RubikFamily
import one.rarebit.heyarr.mobile.theme.Tokens

/** The top-level destinations, in bar order. No Forum. */
enum class NavSection(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    DISCOVER("Discover", Icons.Rounded.Explore),
    SEARCH("Search", Icons.Rounded.Search),
    LIBRARY("Library", Icons.Rounded.VideoLibrary),
    MISSING("Missing", Icons.Rounded.ReportProblem),
    CAST("Cast", Icons.Rounded.Cast),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

/** The tracked UPPERCASE Rubik caption under each tile — the desktop rail's, sized for a phone. */
private val TILE_LABEL = TextStyle(fontFamily = RubikFamily, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, lineHeight = 11.sp)
private val RAIL_LABEL = TextStyle(fontFamily = RubikFamily, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, lineHeight = 12.sp)

/**
 * The phone's bottom bar: every destination as an icon tile over an uppercase caption,
 * the active tile tinted in the accent of the media in focus. Sits above the system
 * navigation bar inset.
 */
@Composable
fun HeyarrBottomBar(current: NavSection?, onGo: (NavSection) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalMediaTheme.current
    Column(modifier.fillMaxWidth().background(Tokens.surface1)) {
        Box(Modifier.fillMaxWidth().height(Tokens.hairline).background(Tokens.border))
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp).windowInsetsPadding(WindowInsets.navigationBars), verticalAlignment = Alignment.CenterVertically) {
            for (s in NavSection.entries) NavTile(s, active = s == current, accent = theme.accent, accentEnd = theme.accentGradientEnd, style = TILE_LABEL, tileSize = 36.dp, iconSize = 20.dp, modifier = Modifier.weight(1f)) { onGo(s) }
        }
    }
}

/**
 * The tablet rail: a logo, then each destination as an icon over an uppercase caption,
 * and the connection at the foot — the desktop's `SideNav`, one width at every size.
 */
@Composable
fun HeyarrNavRail(current: NavSection?, onGo: (NavSection) -> Unit, connection: Connection, modifier: Modifier = Modifier, connectionDetail: String? = null, onConnection: () -> Unit = {}) {
    val theme = LocalMediaTheme.current
    Column(
        modifier.fillMaxHeight().width(Tokens.navWidth).background(Tokens.surface1).windowInsetsPadding(WindowInsets.statusBars).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        one.rarebit.heyarr.ui.components.ArchiveMark(Modifier.padding(4.dp))
        Spacer(Modifier.height(14.dp))
        for (s in NavSection.entries) NavTile(s, active = s == current, accent = theme.accent, accentEnd = theme.accentGradientEnd, style = RAIL_LABEL, tileSize = 44.dp, iconSize = 22.dp, modifier = Modifier.fillMaxWidth()) { onGo(s) }
        Spacer(Modifier.weight(1f))
        ConnectionTile(connection, connectionDetail, onConnection)
    }
}

@Composable
private fun NavTile(item: NavSection, active: Boolean, accent: Color, accentEnd: Color, style: TextStyle, tileSize: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(Tokens.radiusButton)
    val tile = when { active -> accent.copy(alpha = 0.18f); pressed -> Tokens.surface2; else -> Color.Transparent }
    val fg = when { active -> accentEnd; pressed -> Tokens.textPrimary; else -> Tokens.textMuted }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.contentDescription = item.label; this.selected = active }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(tileSize).focusRing(interaction, shape).background(tile, shape).border(Tokens.hairline, if (active) accent.copy(alpha = 0.45f) else Color.Transparent, shape), contentAlignment = Alignment.Center) {
            Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(iconSize))
        }
        Text(item.label.uppercase(), style = style, color = if (active) accentEnd else Tokens.textMuted, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
    }
}

@Composable
private fun ConnectionTile(connection: Connection, detail: String?, onClick: () -> Unit) {
    val (tone, label) = when (connection) {
        Connection.ONLINE -> Tokens.success to "Online"
        Connection.OFFLINE -> Tokens.danger to "Offline"
        Connection.UNAUTHORIZED -> Tokens.warning to "Refused"
        Connection.UNKNOWN -> Tokens.textDisabled to "Connecting"
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().focusRing(interaction, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
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
