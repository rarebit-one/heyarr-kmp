package one.rarebit.heyarr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * A work's [Availability] for someone browsing: nothing at all when it is ready to play, so a
 * shelf of things you have stays clean; otherwise the same shapes [StatusPill] uses (open square
 * for on its way, triangle for not available, dashed square for not in your library) with a
 * plain label, so the state never rests on colour alone.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun AvailabilityMark(availability: Availability, modifier: Modifier = Modifier, compact: Boolean = false) {
    val glyph = when (availability) {
        Availability.READY -> return
        Availability.ON_ITS_WAY -> LibraryStatus.WANTED
        Availability.NOT_AVAILABLE -> LibraryStatus.MISSING
        Availability.GETTABLE -> LibraryStatus.NOT_TRACKED
    }
    val tone = if (availability == Availability.ON_ITS_WAY) Tokens.ochre else Tokens.textMuted
    Row(
        modifier.background(Tokens.bgBase.copy(alpha = 0.72f), RoundedCornerShape(Tokens.radiusChip))
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 2.dp)
            .semantics { contentDescription = availability.label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusGlyph(glyph, tone)
        if (!compact) Text(availability.label, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1)
    }
}

/**
 * The one consumer action for a work that is not held: ask heyarr to get it. The app decides
 * how (a one-click want with the default profile, or the Want sheet when no profile fits);
 * show it only where [Availability.offersGetIt] says so.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun GetItButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = false,
    title: String? = null,
) {
    val description = title?.let { "Get $it" } ?: GET_IT
    if (primary) {
        PrimaryButton(
            GET_IT,
            onClick,
            modifier,
            icon = HeyarrIcons.Add,
            compact = compact,
            contentDescription = description,
        )
    } else {
        SecondaryButton(GET_IT, onClick, modifier, icon = HeyarrIcons.Add, compact = compact)
    }
}

private const val GET_IT = "Get it"
