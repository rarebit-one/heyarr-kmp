package one.rarebit.heyarr.desktop.ui.components

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
import androidx.compose.foundation.layout.BoxScope
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
import one.rarebit.heyarr.ui.components.MediaBadge
import one.rarebit.heyarr.ui.components.MetaLine
import one.rarebit.heyarr.ui.components.Notice
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

/** The hover-revealed Want button; once wanted it reads "Wanted" and stays disabled. */
@Composable
internal fun CardWantButton(status: LibraryStatus?, onWant: () -> Unit, modifier: Modifier) {
    Box(modifier.padding(8.dp)) {
        PrimaryButton(
            if (status == LibraryStatus.NOT_TRACKED ||
                status == null
            ) {
                "Want"
            } else {
                "Wanted"
            },
            onWant,
            icon = if (status == LibraryStatus.NOT_TRACKED ||
                status == null
            ) {
                Icons.Rounded.Add
            } else {
                Icons.Rounded.Check
            },
            compact = true,
            enabled =
            status == LibraryStatus.NOT_TRACKED || status == null,
        )
    }
}

/** A card's title, optional subtitle and meta line under the art. */
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
 * The hero's layered scrim — bottom-up darkening, plus a left-to-right one so the text column
 * reads on any art — and the accent line along its bottom edge.
 */
@Composable
internal fun BoxScope.HeroScrims() {
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
                listOf(Tokens.bgBase.copy(alpha = 0.85f), Tokens.bgBase.copy(alpha = 0.35f), Color.Transparent),
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

/** The hero's kicker line: the media badge, optional work context, and the library status. */
@Composable
internal fun HeroKicker(type: MediaType, kicker: String?, status: LibraryStatus?) {
    val theme = LocalMediaTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaBadge(type)
        if (kicker !=
            null
        ) {
            Text(
                kicker.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = theme.accentGradientEnd,
            )
        }
        if (status != null) StatusPill(status)
    }
}
