package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.SearchHit
import one.rarebit.heyarr.core.mcp.Want
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Hero
import one.rarebit.heyarr.desktop.ui.components.HeroSkeleton
import one.rarebit.heyarr.desktop.ui.components.MediaCard
import one.rarebit.heyarr.desktop.ui.components.Rail
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.RailState
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens

// The Home screen's own rails ([HomeScreen]): one per media type, and followed sources.

/** One media type's rail, in that type's accent. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun TypeRail(session: AppSession, t: MediaType, rail: RailState<SearchHit>, onOpen: (Route) -> Unit) {
    MediaScope(t) {
        Rail(
            if (t == MediaType.BOOK) "For reading" else t.plural,
            rail,
            emptyText = "No ${t.plural.lowercase()} in the library yet.",
            skeletonAspect = MediaThemes.of(t).aspect,
            skeletonWidth = if (MediaThemes.of(t).aspect ==
                CardAspect.SQUARE
            ) {
                Tokens.squareWidth
            } else {
                Tokens.posterWidth
            },
            key = { it.workId },
        ) { hit ->
            val cover by rememberCover(
                session,
                MediaType.from(hit.contentType),
                hit.title,
                hit.artworkPath,
                hit.year,
                hit.creator,
            )
            val status = session.index.statusOf(hit.workId)
            MediaCard(
                hit.title, MediaType.from(hit.contentType), onOpen = {
                    onOpen(Route.Detail(hit.workId, t, hit.title, from = "Home"))
                },
                subtitle = hit.creator,
                meta = listOf(
                    hit.year?.toString(),
                ),
                artwork = cover.bitmap,
                mode = session.mode,
                availability = Availability.ofCatalogued(status),
                width = if (MediaThemes.of(t).aspect ==
                    CardAspect.SQUARE
                ) {
                    Tokens.squareWidth
                } else {
                    Tokens.posterWidth
                },
                showBadge = false,
            )
        }
    }
}
