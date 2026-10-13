package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Experience
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.MediaCard
import one.rarebit.heyarr.desktop.ui.components.MediaCardSkeleton
import one.rarebit.heyarr.desktop.ui.components.MediaRow
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.EmptyState
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.icon
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.Tokens

// The Library screen's header actions, tabs and filter chips ([LibraryScreen]).

/** Refresh, and grid or list. */
@Composable
internal fun LibraryActions(state: LibraryState, onRefresh: () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton("Refresh", onRefresh, icon = HeyarrIcons.Refresh, enabled = !state.loading)
        IconButtonRound(HeyarrIcons.GridView, "Grid view", { state.grid = true }, filled = state.grid)
        IconButtonRound(HeyarrIcons.ListView, "List view", { state.grid = false }, filled = !state.grid)
    }
}

/** The header's trailing controls: a way to what is still on its way (on a shelf), then view and refresh. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun LibraryTrailing(state: LibraryState, pending: Int, onPending: () -> Unit, onRefresh: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pending > 0) GhostButton("$pending on the way", onPending, icon = HeyarrIcons.Forward)
        LibraryActions(state, onRefresh)
    }
}

/** The kind chips: Media (or All, inside an experience), then each kind with its count, in its accent. */
@Composable
internal fun KindFilterChips(
    state: LibraryState,
    all: List<Work>,
    counts: Map<MediaType, Int>,
    experience: Experience?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            if (experience ==
                null
            ) {
                "Media"
            } else {
                "All"
            },
            state.type == MEDIA,
            { state.type = MEDIA },
            count = all.count {
                MediaType.from(it.kind) in
                    (experience?.kinds ?: MEDIA_KINDS)
            }.takeIf { it > 0 },
        )
        for (t in (
            experience?.kinds
                ?: setOf(
                    MediaType.MOVIE,
                    MediaType.SERIES,
                    MediaType.MUSIC,
                    MediaType.BOOK,
                    MediaType.AUDIOBOOK,
                    MediaType.PODCAST,
                )
            )) {
            MediaScope(t) {
                FilterChip(t.plural, state.type == t, {
                    state.type = if (state.type ==
                        t
                    ) {
                        MEDIA
                    } else {
                        t
                    }
                }, icon = t.icon(), count = counts[t]?.takeIf { it > 0 })
            }
        }
        if (experience == null) ExtraKindChips(state, counts, all.size)
    }
}

/** Outside an experience: archived feeds, and everything including documents. */
@Composable
internal fun ExtraKindChips(state: LibraryState, counts: Map<MediaType, Int>, total: Int) {
    MediaScope(MediaType.FEED) {
        FilterChip(
            "Feeds",
            state.type == MediaType.FEED,
            {
                state.type =
                    if (state.type ==
                        MediaType.FEED
                    ) {
                        MEDIA
                    } else {
                        MediaType.FEED
                    }
            },
            icon = MediaType.FEED.icon(),
            count = counts[MediaType.FEED]?.takeIf {
                it >
                    0
            },
        )
    }
    FilterChip(
        "Everything",
        state.type == null,
        { state.type = null },
        count = total.takeIf {
            it >
                0
        },
    )
}

/** Filter by the want-derived library status. */
@Composable
internal fun StatusFilterChips(state: LibraryState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Status", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
        FilterChip("Any", state.status == null, { state.status = null })
        for (s in LibraryStatus.entries) {
            FilterChip(s.label, state.status == s, {
                state.status =
                    if (state.status == s) null else s
            })
        }
    }
}

/**
 * One filter bar for the library: the media kinds, then status, separated
 * by hairlines and scrolling together on a narrow window, instead of three rows of chips.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun LibraryFilterBar(
    state: LibraryState,
    all: List<Work>,
    counts: Map<MediaType, Int>,
    experience: Experience?,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindFilterChips(state, all, counts, experience)
        // Want status is a curator filter: Manage → Library has it, a shelf does not.
        if (experience == null) {
            BarDivider()
            StatusFilterChips(state)
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun BarDivider() {
    Box(Modifier.padding(horizontal = 6.dp).width(Tokens.hairline).height(20.dp).background(Tokens.border))
}
