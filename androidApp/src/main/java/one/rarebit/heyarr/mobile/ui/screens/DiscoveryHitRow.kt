package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.runtime.Composable
import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.ui.components.MediaRow
import one.rarebit.heyarr.ui.components.GetItButton

/** "Get it" for something found outside the library, by title: a one-click want, or the Want sheet. */
typealias GetItByTitle = (title: String, year: Int?, type: MediaType) -> Unit

/**
 * A title the library does not hold yet, found by discovery: Get it asks heyarr for a copy, and a
 * tap on the row opens the full Want sheet for a choice of profile. Once asked for this session it
 * reads "On its way" and offers nothing more, so a second tap cannot send the same want twice.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun DiscoveryHitRow(
    session: AppSession,
    hit: DiscoveryHit,
    onWantTitle: (String, Int?, MediaType?) -> Unit,
    onGetItTitle: GetItByTitle,
) {
    val type = MediaType.from(hit.type)
    val requested = session.titleKey(hit.title, hit.year, type) in session.requestedTitles
    MediaRow(
        title = hit.title,
        type = type,
        onOpen = { onWantTitle(hit.title, hit.year, type.takeIf { it != MediaType.UNKNOWN }) },
        subtitle = hit.overview,
        meta = listOf(hit.year?.toString()),
        artwork = hit.posterUrl,
        availability = if (requested) Availability.ON_ITS_WAY else Availability.GETTABLE,
        trailing = {
            if (!requested) GetItButton({ onGetItTitle(hit.title, hit.year, type) }, compact = true, title = hit.title)
        },
    )
}
