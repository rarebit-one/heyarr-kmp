package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.ui.components.AvailabilityMark
import one.rarebit.heyarr.ui.components.GetItButton
import one.rarebit.heyarr.ui.components.StatusPill

/** What a card knows about its work's state, and the actions it can offer on hover. */
internal class CardState(
    val availability: Availability?,
    val status: LibraryStatus?,
    val mode: ClientMode,
    val onGetIt: (() -> Unit)?,
    val onWant: (() -> Unit)?,
)

/**
 * The corner mark and the hover action over a card's art: the consumer face (availability and
 * Get it) when the card has an availability, otherwise the curator's (want status and Want).
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun BoxScope.CardStateOverlay(state: CardState, title: String, hovered: Boolean) {
    val corner = Modifier.align(Alignment.TopEnd).padding(8.dp)
    val availability = state.availability
    if (availability != null) {
        AvailabilityMark(availability, corner, compact = !hovered)
        if (state.onGetIt != null && availability.offersGetIt(state.mode) && hovered) {
            GetItButton(
                state.onGetIt,
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
                primary = true,
                compact = true,
                title = title,
            )
        }
        return
    }
    state.status?.let { StatusPill(it, corner, compact = !hovered) }
    if (state.onWant != null && wantVisible(state.mode, state.status) && hovered) {
        CardWantButton(state.status, state.onWant, Modifier.align(Alignment.BottomEnd))
    }
}

/** A row's state mark: availability when given (nothing when ready), else the want status. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun RowStateMark(availability: Availability?, status: LibraryStatus?) {
    when {
        availability != null -> AvailabilityMark(availability)
        status != null -> StatusPill(status)
    }
}
