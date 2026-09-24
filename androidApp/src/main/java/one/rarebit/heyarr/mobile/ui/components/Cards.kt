package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.ui.components.interactiveSurface
import one.rarebit.heyarr.ui.components.MediaBadge
import one.rarebit.heyarr.ui.components.MetaLine
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.icon
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.components.RailState

/**
 * Artwork with a blur-up: an accent-tinted gradient placeholder (with the type glyph)
 * shows at once; the image fades over it when Coil lands it. Loading is lazy and
 * cached on disk by Coil over the app's shared OkHttp client, so a poster on our node
 * carries the credential (net/AuthInterceptor) and one on a public host goes out bare.
 * A 404 (a work without art) simply leaves the glyph — it is not an error.
 */
@Composable
fun Artwork(url: String?, type: MediaType, modifier: Modifier = Modifier, contentDescription: String? = null, glyphSize: Dp = 28.dp) {
    val theme = MediaThemes.of(type)
    val reduce = LocalAppearance.current.reduceMotion
    val context = LocalContext.current
    Box(modifier.background(Brush.linearGradient(listOf(theme.accent.copy(alpha = 0.35f), Tokens.surface2, Tokens.surface1))), contentAlignment = Alignment.Center) {
        Icon(type.icon(), contentDescription = null, tint = theme.accent.copy(alpha = 0.55f), modifier = Modifier.size(glyphSize))
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(if (reduce) 0 else 350).build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One entry of a card's long-press menu. */
data class CardAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/**
 * The poster / cover / square card. Aspect, placeholder glyph and accent follow the
 * type; the type badge sits top-left, the status pill top-right. A tap opens; a
 * long-press opens the menu — Want when the work is not tracked, plus whatever the
 * caller adds (star, add to playlist). Both the card and the menu are described for
 * TalkBack.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    title: String,
    type: MediaType,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: List<String?> = emptyList(),
    artwork: String? = null,
    status: LibraryStatus? = null,
    onWant: (() -> Unit)? = null,
    /** The card width; null fills the parent (a grid cell). */
    width: Dp? = if (MediaThemes.of(type).aspect == CardAspect.WIDE) 280.dp else Tokens.posterWidth,
    showBadge: Boolean = true,
    /** 0..1 to draw a progress bar along the art's bottom edge (the continue rail). */
    progress: Float? = null,
    aspectOverride: CardAspect? = null,
    actions: List<CardAction> = emptyList(),
) = MediaScope(type) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Tokens.radiusCard)
    var menuOpen by remember { mutableStateOf(false) }
    val wantable = onWant != null && (status == null || status == LibraryStatus.NOT_TRACKED)
    val hasMenu = wantable || actions.isNotEmpty()
    Box(if (width != null) modifier.width(width) else modifier) {
        Column(
            Modifier.fillMaxWidth()
                .focusRing(interaction, shape, inset = 2.dp)
                .clip(shape)
                .interactiveSurface(interaction, shape)
                .border(Tokens.hairline, Tokens.border, shape)
                .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen, onLongClick = if (hasMenu) ({ menuOpen = true }) else null)
                .semantics { this.contentDescription = "${type.label}: $title${status?.let { ", ${it.label}" } ?: ""}${if (hasMenu) ". Long press for actions" else ""}" },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio((aspectOverride ?: theme.aspect).ratio)) {
                Artwork(artwork, type, Modifier.fillMaxSize(), contentDescription = null)
                if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Tokens.bgBase.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Brush.horizontalGradient(listOf(theme.accent, theme.accentGradientEnd))))
                }
                if (theme.spineShadow) Box(Modifier.width(10.dp).fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
                if (showBadge) MediaBadge(type, Modifier.align(Alignment.TopStart).padding(8.dp))
                if (status != null) StatusPill(status, Modifier.align(Alignment.TopEnd).padding(8.dp), compact = true)
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaLine(meta)
            }
        }
        if (hasMenu) DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.background(Tokens.surface3)) {
            DropdownMenuItem(text = { Text("Open", color = Tokens.textPrimary) }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null, tint = Tokens.textMuted) }, onClick = { menuOpen = false; onOpen() })
            if (wantable) DropdownMenuItem(text = { Text("Want", color = Tokens.textPrimary) }, leadingIcon = { Icon(Icons.Rounded.Add, null, tint = theme.accentGradientEnd) }, onClick = { menuOpen = false; onWant?.invoke() })
            for (a in actions) DropdownMenuItem(text = { Text(a.label, color = Tokens.textPrimary) }, leadingIcon = a.icon?.let { ic -> @Composable { Icon(ic, null, tint = Tokens.textMuted) } }, onClick = { menuOpen = false; a.onClick() })
        }
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
    artwork: String? = null,
    status: LibraryStatus? = null,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) = MediaScope(type) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val bg = if (selected) theme.tint(0.16f) else Color.Transparent
    Row(
        modifier.fillMaxWidth()
            .focusRing(interaction, shape)
            .clip(shape)
            .background(bg, shape)
            .border(Tokens.hairline, if (selected) theme.accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { this.contentDescription = "${type.label}: $title${status?.let { ", ${it.label}" } ?: ""}${if (selected) ", selected" else ""}" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumbW = 52.dp * theme.aspect.ratio
        Box(Modifier.width(thumbW).height(52.dp).clip(RoundedCornerShape(Tokens.radiusCard))) { Artwork(artwork, type, Modifier.fillMaxSize(), glyphSize = 18.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (type != MediaType.UNKNOWN) MediaBadge(type)
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaLine(meta)
        }
        if (status != null) StatusPill(status, compact = true)
        if (trailing != null) trailing()
    }
}

/**
 * A horizontally-scrolling content rail with a section header. LazyRow, so a 200-item
 * rail composes only what is visible; it bleeds to the screen edges with the header
 * inset by [padding]. The header is themed by the surrounding scope; each card themes
 * itself.
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
    padding: Dp = Tokens.screenPadding,
    trailing: (@Composable () -> Unit)? = null,
    key: ((T) -> Any)? = null,
    card: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(title, Modifier.padding(horizontal = padding), subtitle = subtitle, trailing = trailing)
        when (state) {
            RailState.Loading -> Row(Modifier.padding(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(Tokens.gridGap)) { repeat(4) { MediaCardSkeleton(skeletonAspect, skeletonWidth) } }
            is RailState.Failed -> Notice(state.message, Modifier.padding(horizontal = padding), tone = Tokens.danger)
            is RailState.Loaded -> if (state.items.isEmpty()) Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted, modifier = Modifier.padding(horizontal = padding))
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.s3), contentPadding = PaddingValues(horizontal = padding)) {
                items(state.items, key = key) { card(it) }
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
    artwork: String? = null,
    status: LibraryStatus? = null,
    primary: (@Composable () -> Unit)? = null,
    secondary: (@Composable () -> Unit)? = null,
    height: Dp = 320.dp,
) = MediaScope(type) {
    val theme = LocalMediaTheme.current
    val shape = RoundedCornerShape(Tokens.radiusCard)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(Tokens.surface1).border(Tokens.hairline, Tokens.border, shape)) {
        Artwork(artwork, type, Modifier.fillMaxSize(), glyphSize = 72.dp)
        // Layered scrim: bottom-up darkening, plus a left-to-right one so the text column reads on any art.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Tokens.bgBase.copy(alpha = 0.55f), Tokens.bgBase.copy(alpha = 0.96f)))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Tokens.bgBase.copy(alpha = 0.7f), Tokens.bgBase.copy(alpha = 0.25f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomStart).background(Brush.horizontalGradient(listOf(theme.accent, theme.accentGradientEnd, Color.Transparent))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaBadge(type)
                if (kicker != null) Text(kicker.uppercase(), style = MaterialTheme.typography.labelSmall, color = theme.accentGradientEnd, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (status != null) StatusPill(status)
            }
            Text(title, style = MaterialTheme.typography.displaySmall, color = Tokens.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(meta, color = Tokens.textPrimary.copy(alpha = 0.85f))
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (primary != null) primary()
                if (secondary != null) secondary()
            }
        }
    }
}

@Composable
fun HeroSkeleton(height: Dp = 320.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Tokens.radiusCard))) {
        Skeleton(Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Skeleton(Modifier.width(80.dp).height(14.dp)); Skeleton(Modifier.width(220.dp).height(28.dp)); Skeleton(Modifier.width(160.dp).height(12.dp))
            Skeleton(Modifier.width(120.dp).height(40.dp), RectangleShape)
        }
    }
}
