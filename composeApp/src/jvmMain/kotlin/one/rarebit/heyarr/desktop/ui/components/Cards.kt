package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.components.CornerBracket
import one.rarebit.heyarr.ui.components.MediaBadge
import one.rarebit.heyarr.ui.components.MetaLine
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PixelCluster
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.RailState
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.ui.components.icon
import one.rarebit.heyarr.ui.components.interactiveSurface
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens

private const val HERO_CROSSFADE_DURATION_MS = 500

/**
 * Artwork with a blur-up: a restrained archive fallback (type glyph + pixel cluster)
 * shows at once; the decoded bitmap fades over it when it lands. Loading is lazy —
 * see [one.rarebit.heyarr.desktop.state.ArtworkLoader.rememberArtwork] at the call site.
 */
@Composable
fun Artwork(
    bitmap: ImageBitmap?,
    type: MediaType,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    glyphSize: Dp = 28.dp,
) {
    val theme = MediaThemes.of(type)
    val reduce = LocalAppearance.current.reduceMotion
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(if (reduce) 0 else 350))
    Box(
        modifier.background(
            Brush.linearGradient(listOf(theme.accent.copy(alpha = 0.15f), Tokens.surface2, Tokens.surface1)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap ==
            null
        ) {
            Icon(
                type.icon(),
                contentDescription = null,
                tint = theme.accent.copy(alpha = 0.55f),
                modifier = Modifier.size(glyphSize),
            )
            PixelCluster(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(16.dp),
                color = theme.accentGradientEnd,
                pattern = type.ordinal % 3,
            )
            CornerBracket(
                Modifier.fillMaxSize().padding(8.dp),
                color = theme.accent.copy(alpha = 0.45f),
            )
        }
        if (bitmap !=
            null
        ) {
            Image(
                bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(alpha),
            )
        }
    }
}

/**
 * Whether a card should offer its Want affordance. Wanting writes desired state, an
 * enrolled-only [Surface.WANT]: a guest must never see the button (hide it — don't
 * show-then-refuse), and even an enrolled client hides it once the work is already
 * in the library. [GuestGate] in `:core` is the single source of truth for the guest
 * half of that rule; kept as a pure function so the per-card gate is unit-testable
 * without a Compose harness.
 */
fun wantVisible(mode: ClientMode, status: LibraryStatus?): Boolean =
    GuestGate.allows(mode, Surface.WANT) && status != LibraryStatus.IN_LIBRARY

/**
 * The poster / cover / square card. Aspect, placeholder glyph and accent follow the
 * type; the type badge sits top-left, the status pill top-right, and the one-click
 * Want action appears on hover (or focus) at the bottom edge — but only for an enrolled
 * client ([wantVisible]); a guest never sees a want button that would only be refused.
 * Fully keyboard-operable: the card is a focusable button, and Want is a second focus stop.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun MediaCard(
    title: String,
    type: MediaType,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: List<String?> = emptyList(),
    artwork: ImageBitmap? = null,
    status: LibraryStatus? = null,
    onWant: (() -> Unit)? = null,
    /** The client's mode; a guest ([ClientMode.GUEST]) never sees the Want affordance. */
    mode: ClientMode = ClientMode.ENROLLED,
    width: Dp = if (MediaThemes.of(type).aspect == CardAspect.WIDE) 280.dp else Tokens.posterWidth,
    showBadge: Boolean = true,
    aspectOverride: CardAspect? = null,
) = MediaScope(type) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.radiusCard)
    Column(
        modifier.width(width)
            .focusRing(interaction, shape, inset = 2.dp)
            .clip(shape)
            .interactiveSurface(interaction, shape)
            .border(Tokens.hairline, if (hovered) theme.accent.copy(alpha = 0.6f) else Tokens.border, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { this.contentDescription = "${type.label}: $title${status?.let { ", ${it.label}" } ?: ""}" },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio((aspectOverride ?: theme.aspect).ratio)) {
            Artwork(artwork, type, Modifier.fillMaxSize(), contentDescription = null)
            if (theme.spineShadow) {
                Box(
                    Modifier.width(
                        10.dp,
                    ).fillMaxSize().background(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)),
                    ),
                )
            }
            if (showBadge) MediaBadge(type, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (status != null) StatusPill(status, Modifier.align(Alignment.TopEnd).padding(8.dp), compact = !hovered)
            if (onWant != null && wantVisible(mode, status) && hovered) {
                CardWantButton(status, onWant, Modifier.align(Alignment.BottomEnd))
            }
        }
        CardCaption(title, subtitle, meta)
    }
}

/** The skeleton twin of [MediaCard]. */
@Composable
fun MediaCardSkeleton(aspect: CardAspect = CardAspect.POSTER, width: Dp = Tokens.posterWidth) {
    Column(Modifier.width(width).clip(RoundedCornerShape(Tokens.radiusCard)).background(Tokens.surface1)) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(aspect.ratio), RoundedCornerShape(0.dp))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Skeleton(Modifier.fillMaxWidth(0.8f).height(12.dp))
            Skeleton(Modifier.fillMaxWidth(0.5f).height(10.dp))
        }
    }
}

/** A compact list row (search results, missing list): art thumb, title, meta, badge, status, trailing action. */
@Composable
fun MediaRow(
    title: String,
    type: MediaType,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: List<String?> = emptyList(),
    artwork: ImageBitmap? = null,
    status: LibraryStatus? = null,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) = MediaScope(type) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val bg = when {
        selected -> theme.tint(0.16f)
        hovered -> Tokens.surface2
        else -> Color.Transparent
    }
    Row(
        modifier.fillMaxWidth()
            .focusRing(interaction, shape)
            .clip(shape)
            .background(bg, shape)
            .border(Tokens.hairline, if (selected) theme.accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
            .semantics {
                this.contentDescription =
                    "${type.label}: $title${status?.let { ", ${it.label}" } ?: ""}${if (selected) ", selected" else ""}"
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumbW = 52.dp * theme.aspect.ratio
        Box(Modifier.width(thumbW).height(52.dp).clip(RoundedCornerShape(Tokens.radiusCard))) {
            Artwork(artwork, type, Modifier.fillMaxSize(), glyphSize = 18.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (type != MediaType.UNKNOWN) MediaBadge(type)
            }
            if (subtitle !=
                null
            ) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MetaLine(meta)
        }
        if (status != null) StatusPill(status)
        if (trailing != null) trailing()
    }
}

/**
 * A horizontally-scrolling content rail with a section header. LazyRow, so a 200-item
 * rail composes only what is visible. The header is themed by the surrounding scope;
 * each card themes itself.
 */
@Composable
fun <T> Rail(
    title: String,
    state: RailState<T>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    emptyText: String = "Nothing here yet.",
    skeletonAspect: CardAspect = CardAspect.POSTER,
    skeletonWidth: Dp = Tokens.posterWidth,
    trailing: (@Composable () -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    key: ((T) -> Any)? = null,
    card: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(title, subtitle = subtitle, trailing = trailing)
        when (state) {
            RailState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gridGap)) {
                repeat(6) { MediaCardSkeleton(skeletonAspect, skeletonWidth) }
            }

            is RailState.Failed -> Notice(
                state.message,
                tone = Tokens.danger,
                detail = onRetry?.let {
                    "Click Retry in the banner or reload the page."
                },
            )

            is RailState.Loaded -> if (state.items.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted)
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.gridGap),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 24.dp),
                ) {
                    items(state.items, key = key) { card(it) }
                }
            }
        }
    }
}

/**
 * The hero spotlight: full-bleed art behind a layered scrim, a title/meta/CTA stack
 * bottom-left. The whole block sits in the item's media scope, so the CTA gradient and
 * the badge follow the type.
 */
@Composable
fun Hero(
    title: String,
    type: MediaType,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    meta: List<String?> = emptyList(),
    description: String? = null,
    artwork: ImageBitmap? = null,
    status: LibraryStatus? = null,
    primary: (@Composable () -> Unit)? = null,
    secondary: (@Composable () -> Unit)? = null,
    height: Dp = 380.dp,
) = MediaScope(type) {
    val theme = LocalMediaTheme.current
    val shape = RoundedCornerShape(Tokens.radiusCard)
    Box(
        modifier.fillMaxWidth().height(
            height,
        ).clip(shape).background(Tokens.surface1).border(Tokens.hairline, Tokens.border, shape),
    ) {
        SpotlightArtwork(artwork, type)
        HeroScrims()
        Column(
            Modifier.align(Alignment.BottomStart).padding(28.dp).fillMaxWidth(0.62f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeroKicker(type, kicker, status)
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                color = Tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(meta, color = Tokens.textPrimary.copy(alpha = 0.85f))
            if (description !=
                null
            ) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (primary != null) primary()
                if (secondary != null) secondary()
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // This private helper follows Compose's composable naming convention.
private fun SpotlightArtwork(artwork: ImageBitmap?, type: MediaType) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    Crossfade(
        artwork,
        animationSpec = tween(if (reduceMotion) 0 else HERO_CROSSFADE_DURATION_MS),
        label = "spotlight-artwork",
    ) { image ->
        Artwork(image, type, Modifier.fillMaxSize(), glyphSize = 72.dp)
    }
}

@Composable
fun HeroSkeleton(height: Dp = 380.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Tokens.radiusCard))) {
        Skeleton(Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        Column(
            Modifier.align(Alignment.BottomStart).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Skeleton(Modifier.width(80.dp).height(14.dp))
            Skeleton(Modifier.width(360.dp).height(30.dp))
            Skeleton(Modifier.width(220.dp).height(12.dp))
            Skeleton(Modifier.width(120.dp).height(40.dp), RectangleShape)
        }
    }
}
