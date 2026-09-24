package one.rarebit.heyarr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.LocalHeyarrPlatform
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens

// The card pieces both apps draw alike. The cards themselves (MediaCard, MediaRow, Rail,
// Hero, Artwork) stay in each app: the desktop decodes its own bitmaps and shows Want on
// hover, the phone loads through Coil and puts Want behind a long-press menu.

/** The glyph a type's placeholder art shows. */
@Suppress("DEPRECATION") // the non-AutoMirrored MenuBook / HelpOutline both apps have always drawn
fun MediaType.icon(): ImageVector = when (this) {
    MediaType.MOVIE -> Icons.Rounded.Movie
    MediaType.SERIES -> Icons.Rounded.Tv
    MediaType.BOOK -> Icons.Rounded.MenuBook
    MediaType.AUDIOBOOK -> Icons.Rounded.Headphones
    MediaType.PODCAST -> Icons.Rounded.Podcasts
    MediaType.MUSIC -> Icons.Rounded.MusicNote
    MediaType.FEED -> Icons.Rounded.RssFeed
    MediaType.UNKNOWN -> Icons.Rounded.HelpOutline
}

/** Library status as a small pill: In library (accent), Wanted (gold), Missing (danger), Not tracked (muted). */
@Composable
fun StatusPill(status: LibraryStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val accent = LocalMediaTheme.current.accentGradientEnd
    val touch = LocalHeyarrPlatform.current.touch
    val (tone, dot) = when (status) {
        LibraryStatus.IN_LIBRARY -> accent to accent
        LibraryStatus.WANTED -> Tokens.ratingGold to Tokens.ratingGold
        LibraryStatus.MISSING -> Tokens.danger to Tokens.danger
        LibraryStatus.NOT_TRACKED -> Tokens.textMuted to Tokens.textDisabled
    }
    // The phone's pill is a touch taller so the dot-only (compact) form still reads on a card.
    val vertical = if (touch) (if (compact) 4.dp else 3.dp) else 2.dp
    Row(
        modifier.background(Tokens.bgBase.copy(alpha = 0.72f), RectangleShape)
            .border(Tokens.hairline, tone.copy(alpha = 0.45f), RectangleShape)
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = vertical)
            .semantics { this.contentDescription = "Status: ${status.label}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(dot, RectangleShape))
        if (!compact) {
            Text(
                status.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = tone,
                maxLines = 1,
            )
        }
    }
}

/** Skeleton rows for a list. */
@Composable
fun MediaRowSkeleton(count: Int = 4) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Skeleton(Modifier.width(40.dp).height(52.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Skeleton(Modifier.fillMaxWidth(0.5f).height(12.dp))
                    Skeleton(Modifier.fillMaxWidth(0.3f).height(10.dp))
                }
            }
        }
    }
}

/** The state a rail can be in; the rail renders skeletons / an inline error / an empty line itself. */
sealed interface RailState<out T> {
    data object Loading : RailState<Nothing>
    data class Loaded<T>(val items: List<T>) : RailState<T>
    data class Failed(val message: String) : RailState<Nothing>
}
