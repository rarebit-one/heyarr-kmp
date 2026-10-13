package one.rarebit.heyarr.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
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
    MediaType.MOVIE -> HeyarrIcons.Film
    MediaType.SERIES -> HeyarrIcons.Series
    MediaType.BOOK -> HeyarrIcons.Book
    MediaType.AUDIOBOOK -> HeyarrIcons.Listen
    MediaType.PODCAST -> HeyarrIcons.Podcast
    MediaType.MUSIC -> HeyarrIcons.Music
    MediaType.FEED -> HeyarrIcons.Feed
    MediaType.UNKNOWN -> HeyarrIcons.Help
}

/**
 * A library status as a glyph and a label, with no box. Each status has its own shape — filled
 * square (in library), open square (wanted), triangle (missing), dashed square (not tracked) —
 * so it never rests on colour alone. Over artwork it sits on a faint scrim for legibility.
 */
@Composable
fun StatusPill(status: LibraryStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val accent = LocalMediaTheme.current.accentGradientEnd
    val touch = LocalHeyarrPlatform.current.touch
    val tone = when (status) {
        LibraryStatus.IN_LIBRARY -> accent
        LibraryStatus.WANTED -> Tokens.ochre
        LibraryStatus.MISSING -> Tokens.danger
        LibraryStatus.NOT_TRACKED -> Tokens.textMuted
    }
    // The phone's pill is a touch taller so the glyph-only (compact) form still reads on a card.
    val vertical = if (touch) (if (compact) 4.dp else 3.dp) else 2.dp
    Row(
        modifier.background(Tokens.bgBase.copy(alpha = 0.72f), RoundedCornerShape(Tokens.radiusChip))
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = vertical)
            .semantics { this.contentDescription = "Status: ${status.label}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusGlyph(status, tone)
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

/** The shape half of [StatusPill], on its own for rows that set the label themselves. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun StatusGlyph(status: LibraryStatus, tone: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(8.dp)) {
        val stroke = Stroke(width = 1.5.dp.toPx())
        val dash = 1.5.dp.toPx() // in dp, so the dashed (not tracked) square reads the same at every density
        val inset = stroke.width / 2f
        val box = Size(size.width - stroke.width, size.height - stroke.width)
        when (status) {
            LibraryStatus.IN_LIBRARY -> drawRect(tone)

            LibraryStatus.WANTED -> drawRect(tone, Offset(inset, inset), box, style = stroke)

            LibraryStatus.MISSING -> drawPath(
                Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                },
                tone,
            )

            LibraryStatus.NOT_TRACKED -> drawRect(
                tone,
                Offset(inset, inset),
                box,
                style = Stroke(stroke.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
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
