package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.desktop.state.Toast
import one.rarebit.heyarr.desktop.theme.LocalAppearance
import one.rarebit.heyarr.desktop.theme.LocalMediaTheme
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * The small reusable set every screen is built from. Each piece reads the accent in
 * force ([LocalMediaTheme]) so wrapping it in a [one.rarebit.heyarr.desktop.theme.MediaScope]
 * is all it takes to re-skin it.
 */

// ── focus ────────────────────────────────────────────────────────────────────────

/** A visible 2px focus ring in the active accent, drawn only while keyboard-focused. */
@Composable
fun Modifier.focusRing(interaction: MutableInteractionSource, shape: Shape, inset: Dp = 0.dp): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalMediaTheme.current.accent
    return if (focused) this.padding(inset).border(2.dp, accent, shape) else this.padding(inset)
}

/** Hover lift for cards: surface-2 tint while the pointer is over it. */
@Composable
fun Modifier.hoverSurface(interaction: MutableInteractionSource, shape: Shape): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Tokens.surface2 else Tokens.surface1)
    return this.hoverable(interaction).background(bg, shape)
}

// ── buttons ──────────────────────────────────────────────────────────────────────

/**
 * The primary CTA: a flat rectangular control with an icon (Play ▸, Add +).
 * Disabled controls use a dimmed surface and text.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    contentDescription: String = label,
) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RectangleShape
    val bg = when { !enabled -> Tokens.surface2; hovered -> theme.accentHover; else -> theme.ctaGradientStart }
    val fg = if (enabled) theme.onAccent else Tokens.textDisabled
    Row(
        modifier
            .focusRing(interaction, shape, inset = 2.dp)
            .clip(shape)
            .background(bg, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Secondary: hairline-bordered rectangle on surface-2, text-primary. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    danger: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RectangleShape
    val fg = when { !enabled -> Tokens.textDisabled; danger -> Tokens.danger; else -> Tokens.textPrimary }
    Row(
        modifier
            .focusRing(interaction, shape, inset = 2.dp)
            .clip(shape)
            .background(if (hovered && enabled) Tokens.surface3 else Tokens.surface2, shape)
            .border(Tokens.hairline, Tokens.border, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 7.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Ghost: text-only, for tertiary actions (Clear, Cancel). */
@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.radiusButton)
    val fg = if (enabled) (if (hovered) Tokens.textPrimary else Tokens.textMuted) else Tokens.textDisabled
    Row(
        modifier
            .focusRing(interaction, shape)
            .clip(shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** A square icon-only button (transport controls, close, refresh). */
@Composable
fun IconButtonRound(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 36.dp, filled: Boolean = false) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when { filled && enabled -> theme.accent; hovered && enabled -> Tokens.surface3; else -> Tokens.surface2 }
    val fg = when { !enabled -> Tokens.textDisabled; filled -> theme.onAccent; else -> Tokens.textPrimary }
    Box(
        modifier
            .focusRing(interaction, RectangleShape, inset = 2.dp)
            .size(size)
            .clip(RectangleShape)
            .background(bg, RectangleShape)
            .border(Tokens.hairline, if (filled) Color.Transparent else Tokens.border, RectangleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.5f)) }
}

// ── chips & badges ───────────────────────────────────────────────────────────────

/** A selectable filter chip (type filters, status filters). Selected = accent tint + accent border. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, count: Int? = null) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RectangleShape
    val bg = when { selected -> theme.tint(0.22f); hovered -> Tokens.surface3; else -> Tokens.surface2 }
    val fg = if (selected) Tokens.textPrimary else Tokens.textMuted
    Row(
        modifier
            .focusRing(interaction, shape, inset = 2.dp)
            .clip(shape)
            .background(bg, shape)
            .border(Tokens.hairline, if (selected) theme.accent else Tokens.border, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.contentDescription = if (selected) "$label, selected" else label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (selected) theme.accent else fg, modifier = Modifier.size(14.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, softWrap = false)
        if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled, maxLines = 1, softWrap = false)
    }
}

/** The media-type badge on a card or row — always in that type's own accent, whatever the surrounding scope. */
@Composable
fun MediaBadge(type: MediaType, modifier: Modifier = Modifier) {
    val theme = one.rarebit.heyarr.ui.theme.MediaThemes.of(type)
    Box(
        modifier.background(theme.tint(0.22f), RoundedCornerShape(Tokens.radiusChip)).border(Tokens.hairline, theme.accent.copy(alpha = 0.5f), RoundedCornerShape(Tokens.radiusChip))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(type.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = theme.accentGradientEnd, maxLines = 1)
    }
}

/** A tiny "·"-separated metadata line (year · runtime · genre). Empty parts are dropped. */
@Composable
fun MetaLine(parts: List<String?>, modifier: Modifier = Modifier, color: Color = Tokens.textMuted, maxLines: Int = 1) {
    val text = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A keyboard hint, e.g. Ctrl+F. */
@Composable
fun Kbd(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Tokens.surface2, RoundedCornerShape(Tokens.radiusChip)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusChip)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
    }
}

// ── section header ───────────────────────────────────────────────────────────────

/** A rail/grid header: display-face title with a short accent underline, optional trailing action. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val accent = LocalMediaTheme.current.accent
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(36.dp).height(3.dp).background(accent, RectangleShape))
            if (subtitle != null) { Spacer(Modifier.height(6.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) }
        }
        if (trailing != null) trailing()
    }
}

// ── skeletons & states ───────────────────────────────────────────────────────────

/** A shimmering placeholder block (static when Reduce motion is on). */
@Composable
fun Skeleton(modifier: Modifier, shape: Shape = RoundedCornerShape(Tokens.radiusChip)) {
    val reduce = LocalAppearance.current.reduceMotion
    val alpha = if (reduce) 1f else {
        val t = rememberInfiniteTransition()
        val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse))
        a
    }
    Box(modifier.alpha(alpha).background(Tokens.surface2, shape))
}

/** Empty state: icon, title, a line of help, optional action. */
@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, detail: String? = null, icon: ImageVector = Icons.Rounded.Search, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(Tokens.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.s2)) {
        Box(Modifier.size(56.dp).background(Tokens.surface2, CircleShape).border(Tokens.hairline, Tokens.border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted, modifier = Modifier.padding(horizontal = 24.dp))
        if (action != null) { Spacer(Modifier.height(8.dp)); action() }
    }
}

/** Error state: same shape as empty, red-tinted, with the reason verbatim and a retry. */
@Composable
fun ErrorState(title: String, detail: String?, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Tokens.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.s2)) {
        Box(Modifier.size(56.dp).background(Tokens.danger.copy(alpha = 0.14f), CircleShape).border(Tokens.hairline, Tokens.danger.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Tokens.danger, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted, modifier = Modifier.padding(horizontal = 24.dp))
        if (onRetry != null) { Spacer(Modifier.height(8.dp)); SecondaryButton("Try again", onRetry, compact = true) }
    }
}

/** An inline notice strip (info / warning / danger) for honest states like "discovery needs a metadata provider". */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, tone: Color = Tokens.warning, icon: ImageVector = Icons.Rounded.Info, detail: String? = null) {
    Row(
        modifier.fillMaxWidth().background(tone.copy(alpha = 0.10f), RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, tone.copy(alpha = 0.35f), RoundedCornerShape(Tokens.radiusInput)).padding(12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
    }
}

/** The disconnected banner: shown under the top edge whenever heyarr cannot be reached. */
@Composable
fun OfflineBanner(text: String, detail: String?, onRetry: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Tokens.danger.copy(alpha = 0.16f)).border(Tokens.hairline, Tokens.danger.copy(alpha = 0.4f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = Tokens.danger, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = Tokens.textPrimary)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GhostButton("Retry", onRetry)
        GhostButton("Settings", onSettings)
    }
}

// ── toasts ───────────────────────────────────────────────────────────────────────

/** One toast card. A refusal quotes the tool's message verbatim and names the tool. */
@Composable
fun ToastCard(toast: Toast, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val (tone, icon) = when (toast.kind) {
        Toast.Kind.INFO -> Tokens.slate to Icons.Rounded.Info
        Toast.Kind.SUCCESS -> Tokens.success to Icons.Rounded.CheckCircle
        Toast.Kind.ERROR -> Tokens.danger to Icons.Rounded.ErrorOutline
        Toast.Kind.REFUSED -> Tokens.warning to Icons.Rounded.ErrorOutline
    }
    Row(
        modifier.width(380.dp).background(Tokens.surface3, RoundedCornerShape(Tokens.radiusCard)).border(Tokens.hairline, tone.copy(alpha = 0.5f), RoundedCornerShape(Tokens.radiusCard)).padding(12.dp)
            .semantics { this.role = Role.Image; this.contentDescription = "${toast.kind.name.lowercase()}: ${toast.title}. ${toast.detail ?: ""}" },
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(toast.title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
            if (toast.detail != null) Text(toast.detail, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            if (toast.tool != null) Text("via ${toast.tool}", style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled)
            if (toast.action != null) Text(
                toast.action.label,
                style = MaterialTheme.typography.labelLarge,
                color = tone,
                modifier = Modifier.padding(top = 4.dp).clickable { toast.action.onClick(); onDismiss() }
                    .semantics { this.role = Role.Button },
            )
        }
        IconButtonRound(Icons.Rounded.Close, "Dismiss", onDismiss, size = 26.dp)
    }
}
