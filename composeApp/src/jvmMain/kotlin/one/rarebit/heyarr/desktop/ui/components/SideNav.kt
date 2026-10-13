package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import one.rarebit.heyarr.desktop.ui.railSection
import one.rarebit.heyarr.ui.components.ArchiveMark
import one.rarebit.heyarr.ui.components.EditorialRule
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.ui.theme.HeyarrFonts
import one.rarebit.heyarr.ui.theme.Tokens

/** A nav destination: label, icon, route, optional keyboard hint. */
data class NavItem(val route: Route, val label: String, val icon: ImageVector, val hint: String? = null)

/** Connection status and the action that opens its details sheet. */
data class NavConnectionInfo(val state: Connection, val detail: String?, val onOpen: () -> Unit)

/** The consumer half of the rail: the three ways in, and search. Home is the mark at the top. */
val CONSUME_ITEMS = listOf(
    NavItem(Route.Consume(Experience.WATCH), "Watch", HeyarrIcons.Watch, "Ctrl 1"),
    NavItem(Route.Consume(Experience.LISTEN), "Listen", HeyarrIcons.Listen, "Ctrl 2"),
    NavItem(Route.Consume(Experience.READ), "Read", HeyarrIcons.Read, "Ctrl 3"),
    NavItem(Route.Search, "Search", HeyarrIcons.Search, "Ctrl+F"),
)

/**
 * Below the rule: Manage, the one door to the curator workspace (missing, downloads, the full
 * library, sources), and Settings. Cast is not a destination of its own but where "Play on…" and
 * the now-playing bar lead (Route.NowPlaying).
 */
val NAV_ITEMS = listOf(
    NavItem(Route.Manage, "Manage", HeyarrIcons.Manage, "Ctrl 4"),
    NavItem(Route.Settings, "Settings", HeyarrIcons.Settings, "Ctrl ,"),
)

/** Below this height the rail drops its captions so every destination stays on screen. */
private val COMPACT_RAIL_HEIGHT = 640.dp

/** The rail's uppercase caption style: small sans, tracked. */
private val RAIL_LABEL: androidx.compose.ui.text.TextStyle
    @Composable get() = androidx.compose.ui.text.TextStyle(
        fontFamily = HeyarrFonts.rubik,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
        lineHeight = 12.sp,
    )

/**
 * The left rail: a tool selector rather than a sidebar. The mark (Home) and a connection glyph
 * sit at the top; each destination is an icon over an uppercase caption. Only the active item
 * gets a field — a faint mint tile and a marker — so the rail stays quiet. In a short window the
 * captions drop and every destination still fits, instead of scrolling out of reach.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun SideNav(current: Route, onGo: (Route) -> Unit, connection: NavConnectionInfo, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxHeight().width(Tokens.navWidth).background(Tokens.bgBase)) {
        val compact = maxHeight < COMPACT_RAIL_HEIGHT
        Column(
            Modifier.fillMaxHeight().fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp),
        ) {
            // Section, not route equality: a work opened from Home still belongs to Home.
            HomeMark(current.section == Route.Home.section) { onGo(Route.Home) }
            ConnectionGlyph(connection)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
            ) {
                Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
                for (item in CONSUME_ITEMS) {
                    RailItem(item, active = isActive(current, item), compact = compact) { onGo(item.route) }
                }
                EditorialRule(
                    Modifier.padding(horizontal = 14.dp, vertical = if (compact) 4.dp else 8.dp),
                    dashed = true,
                )
                for (item in NAV_ITEMS) {
                    RailItem(item, active = isActive(current, item), compact = compact) { onGo(item.route) }
                }
            }
        }
        // A hairline between the rail and the workspace, instead of a filled rail panel.
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(Tokens.hairline).background(Tokens.border))
    }
}

/** Discover is search's other scope; every curator screen (and a work opened from one) lights Manage. */
internal fun isActive(current: Route, item: NavItem): Boolean =
    if (current == Route.Discover) item.route == Route.Search else current.railSection == item.route.railSection

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun HomeMark(active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.focusRing(interaction, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Home"
                selected = active
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        ArchiveMark()
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun RailItem(item: NavItem, active: Boolean, compact: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fg = when {
        active -> Tokens.accent
        hovered -> Tokens.textPrimary
        else -> Tokens.textMuted
    }
    Box(
        Modifier.fillMaxWidth()
            .focusRing(interaction, RectangleShape)
            .background(if (active) Tokens.accent.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics {
                contentDescription = item.label + (item.hint?.let { " ($it)" } ?: "")
                selected = active
            },
    ) {
        if (active) {
            Box(
                Modifier.align(
                    Alignment.CenterStart,
                ).width(2.dp).height(if (compact) 20.dp else 28.dp).background(Tokens.accent),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            if (!compact) {
                Text(
                    item.label.uppercase(),
                    style = RAIL_LABEL,
                    color = fg,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The connection as one small glyph under the mark: its tone and shape say the state, and it
 * opens the connection sheet. Latency and the host live in that sheet, not in the rail, where
 * they were clipped in a narrow window.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun ConnectionGlyph(connection: NavConnectionInfo) {
    val (tone, label) = when (connection.state) {
        Connection.ONLINE -> Tokens.success to "Online"
        Connection.OFFLINE -> Tokens.danger to "Offline"
        Connection.UNAUTHORIZED -> Tokens.warning to "Refused"
        Connection.UNCONFIGURED -> Tokens.textDisabled to "Not set up"
        Connection.UNKNOWN -> Tokens.textDisabled to "Connecting"
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.focusRing(interaction, RectangleShape)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = connection.onOpen,
            )
            .semantics {
                contentDescription =
                    "heyarr connection: $label${connection.detail?.let { ", $it" } ?: ""}. Open connection details"
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Online is solid; every other state is an outline, so the glyph does not rest on colour alone.
        val glyph = Modifier.size(7.dp)
        Box(if (connection.state == Connection.ONLINE) glyph.background(tone) else glyph.border(1.5.dp, tone))
        Box(Modifier.size(width = 10.dp, height = 2.dp).background(tone.copy(alpha = if (hovered) 0.9f else 0.5f)))
    }
}
