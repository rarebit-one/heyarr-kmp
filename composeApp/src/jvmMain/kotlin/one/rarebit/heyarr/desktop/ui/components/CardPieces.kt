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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.MediaBadge
import one.rarebit.heyarr.ui.components.MetaLine
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.RailState
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.SectionLabel
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
                HeyarrIcons.Add
            } else {
                HeyarrIcons.Check
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

// The hero scrim's stops: dark at the text column's edge, clear by 70% across.
private const val HERO_SCRIM_EDGE = 0.94f
private const val HERO_SCRIM_MID = 0.7f
private const val HERO_SCRIM_MID_STOP = 0.38f
private const val HERO_SCRIM_CLEAR_STOP = 0.7f
private const val HERO_FOOT_FADE = 0.35f

/**
 * The hero's scrim: darkness from the left edge only, where the text column sits, so the art
 * reads full-bleed across the rest of the frame; and a soft fade at the foot into the page.
 */
@Composable
internal fun BoxScope.HeroScrims() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Tokens.bgBase.copy(alpha = HERO_SCRIM_EDGE),
                HERO_SCRIM_MID_STOP to Tokens.bgBase.copy(alpha = HERO_SCRIM_MID),
                HERO_SCRIM_CLEAR_STOP to Color.Transparent,
            ),
        ),
    )
    Box(
        Modifier.fillMaxWidth().fillMaxHeight(HERO_FOOT_FADE).align(Alignment.BottomStart).background(
            Brush.verticalGradient(listOf(Color.Transparent, Tokens.bgBase.copy(alpha = HERO_SCRIM_MID))),
        ),
    )
}

/** The hero's eyebrow: an ochre square and the tracked kicker ("FROM YOUR LIBRARY"), or the media type. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun HeroKicker(type: MediaType, kicker: String?) {
    SectionLabel(kicker ?: type.label)
}

/** Under the title: the media glyph and type, the facts that matter, and the library status, as text not boxes. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun HeroFacts(type: MediaType, meta: List<String?>, status: LibraryStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(type.icon(), contentDescription = null, tint = Tokens.textPrimary, modifier = Modifier.size(16.dp))
        MetaLine(listOf(type.label.uppercase()) + meta, color = Tokens.textPrimary.copy(alpha = 0.85f))
        if (status != null) StatusPill(status)
    }
}
