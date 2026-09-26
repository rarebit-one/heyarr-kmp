@file:Suppress("FunctionNaming")

package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.MediaBadge
import one.rarebit.heyarr.ui.components.MetaLine
import one.rarebit.heyarr.ui.components.Notice
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

// The parts of [MediaCard] and [Hero]: the card's art and book spine, its
// caption, its long-press menu, and the hero's scrim.

/**
 * A card's art at its aspect, with a book's spine shadow; [overlay] adds the corner badges.
 */
@Composable
internal fun CardArtBox(
    modifier: Modifier,
    artwork: String?,
    type: MediaType,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalMediaTheme.current
    Box(modifier) {
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
        overlay()
    }
}

/** A card's title, subtitle and meta line under its art. */
@Composable
internal fun CardCaption(title: String, subtitle: String?, meta: List<String?>) {
    Column(
        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
}

/**
 * A card's long-press menu: Open, Want when [onWant] is set, then the caller's [actions].
 * Each closes the menu first.
 */
@Composable
internal fun CardMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onWant: (() -> Unit)?,
    actions: List<CardAction>,
) {
    val theme = LocalMediaTheme.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.background(Tokens.surface3)) {
        DropdownMenuItem(text = {
            Text("Open", color = Tokens.textPrimary)
        }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null, tint = Tokens.textMuted) }, onClick = {
            onDismiss()
            onOpen()
        })
        if (onWant != null) {
            DropdownMenuItem(text = {
                Text("Want", color = Tokens.textPrimary)
            }, leadingIcon = { Icon(Icons.Rounded.Add, null, tint = theme.accentGradientEnd) }, onClick = {
                onDismiss()
                onWant()
            })
        }
        for (a in actions) {
            DropdownMenuItem(
                text = {
                    Text(a.label, color = Tokens.textPrimary)
                },
                leadingIcon = a.icon?.let { ic ->
                    @Composable { Icon(ic, null, tint = Tokens.textMuted) }
                },
                onClick = {
                    onDismiss()
                    a.onClick()
                },
            )
        }
    }
}

/**
 * The hero's layered scrim: bottom-up darkening, plus a left-to-right one so the text
 * column reads on any art; then the accent rule along the bottom edge.
 */
@Composable
internal fun BoxScope.HeroScrim() {
    val theme = LocalMediaTheme.current
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color.Transparent, Tokens.bgBase.copy(alpha = 0.55f), Tokens.bgBase.copy(alpha = 0.96f)),
            ),
        ),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                listOf(Tokens.bgBase.copy(alpha = 0.7f), Tokens.bgBase.copy(alpha = 0.25f), Color.Transparent),
            ),
        ),
    )
    Box(
        Modifier.fillMaxWidth().height(
            3.dp,
        ).align(
            Alignment.BottomStart,
        ).background(Brush.horizontalGradient(listOf(theme.accent, theme.accentGradientEnd, Color.Transparent))),
    )
}
