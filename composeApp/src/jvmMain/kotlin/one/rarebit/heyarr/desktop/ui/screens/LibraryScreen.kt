@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewList
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
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.availability
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
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens

/** What a want is getting: shown on a shelf as "on the way", never as a work to play. */
private val COMING = setOf(Availability.ON_ITS_WAY, Availability.NOT_AVAILABLE)

/** A pseudo-kind for the default filter: the four media kinds, no feeds or documents. */
val MEDIA = MediaType.UNKNOWN
internal val MEDIA_KINDS =
    setOf(MediaType.MOVIE, MediaType.SERIES, MediaType.MUSIC, MediaType.BOOK, MediaType.AUDIOBOOK, MediaType.PODCAST)

/** One catalog fetch serves every shelf; each screen keeps its own filters and layout. */
class LibraryContents {
    var generation = -1
    var requestId = 0
    var works by mutableStateOf<List<Work>?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
}

class LibraryState(private val contents: LibraryContents = LibraryContents()) {
    val downloads = DownloadsState()
    var generation: Int
        get() = contents.generation
        set(value) {
            contents.generation = value
        }
    var requestId: Int
        get() = contents.requestId
        set(value) {
            contents.requestId = value
        }
    var works: List<Work>?
        get() = contents.works
        set(value) {
            contents.works = value
        }
    var error: String?
        get() = contents.error
        set(value) {
            contents.error = value
        }
    var loading: Boolean
        get() = contents.loading
        set(value) {
            contents.loading = value
        }

    /** null = every kind; [MEDIA] = films, series, music, books (the default — feeds are the Archive, not the shelf). */
    var type by mutableStateOf<MediaType?>(MEDIA)
    var status by mutableStateOf<LibraryStatus?>(null)
    var grid by mutableStateOf(true)

    fun resetFor(newGeneration: Int) {
        if (generation == newGeneration) return
        generation = newGeneration
        requestId++
        works = null
        error = null
        loading = false
    }
}

/**
 * Library — everything the node catalogues, filterable by media type and by the
 * want-derived status, in a virtualised grid or list. Documents (archived articles)
 * are a kind of their own here so the shelf is not swamped by feed items.
 */
@Composable
fun LibraryScreen(
    session: AppSession,
    state: LibraryState,
    onOpen: (Route) -> Unit,
    onWant: (String, String, MediaType) -> Unit,
    modifier: Modifier = Modifier,
    experience: Experience? = null,
) {
    val scope = rememberCoroutineScope()
    fun load() = loadLibrary(session, state, scope)
    // Loaded once per node: a new URL or token (session.generation) throws the cached answer away.
    LaunchedEffect(state, session.generation, state.loading) {
        state.resetFor(session.generation)
        if (state.works == null && state.error == null && !state.loading) {
            load()
        }
    }

    val variants = remember(state.works) { Variants.variantIds(state.works.orEmpty()) }
    val all = state.works.orEmpty().filter {
        it.id !in variants &&
            (experience == null || MediaType.from(it.kind) in experience.kinds)
    }
    // A shelf (Watch / Listen / Read) is for playing: only works with something to play. What a
    // want has asked for but not yet got is counted, and lives in Manage → Downloads.
    val shelf = experience != null
    val availabilityOf: (Work) -> Availability = { it.availability(session.index.statusOf(it.id)) }
    val browsable = if (shelf) all.filter { availabilityOf(it) == Availability.READY } else all
    // Only what a want is actually getting counts as on the way; a guest has no want index to say.
    val pending = if (shelf && !session.isGuest) all.count { availabilityOf(it) in COMING } else 0
    val counts = browsable.groupingBy { MediaType.from(it.kind) }.eachCount()
    val filtered = filterWorks(browsable, state, experience) { session.index.statusOf(it) }

    val openWork: (Work) -> Unit = { w ->
        onOpen(Route.Detail(w.id, MediaType.from(w.kind), w.title, from = experience?.title ?: "Library"))
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader(
            experience?.title ?: "Library",
            icon = (experience?.kinds?.firstOrNull() ?: MediaType.MOVIE).icon(),
            subtitle = state.works?.let { "${filtered.size} of ${browsable.size} works" },
            trailing = { LibraryTrailing(state, pending, { onOpen(Route.Downloads) }, ::load) },
        )
        LibraryFilterBar(state, browsable, counts, experience)
        when {
            state.error != null && state.works == null -> ErrorState("Couldn't load the library", state.error, ::load)

            state.works == null -> LibrarySkeleton(
                gridCell(state, experience),
                experience?.kinds?.firstOrNull() ?: state.type ?: MediaType.BOOK,
            )

            filtered.isEmpty() -> libraryEmpty(browsable.isEmpty(), experience).let { (title, detail) ->
                EmptyState(title, detail = detail)
            }

            state.grid -> LibraryGrid(
                session,
                filtered,
                gridCell(state, experience),
                openWork,
                onWant.takeUnless {
                    shelf
                },
            )

            else -> LibraryList(session, filtered, openWork, shelf)
        }
    }
}

/** The empty state's title and detail: filters hiding works, an empty shelf, or an empty library. */
private fun libraryEmpty(libraryIsEmpty: Boolean, experience: Experience?): Pair<String, String> = when {
    !libraryIsEmpty -> "Nothing matches these filters" to "Clear a filter to see more."

    experience != null -> "Nothing here to ${experience.title.lowercase()} yet" to
        "Search for something and choose Get it, and it appears here once heyarr has a copy."

    else -> "The library is empty" to "Scan a library root on the node, or Want something and let heyarr find it."
}

/** Load (or reload) every work the node catalogues. */
private fun loadLibrary(session: AppSession, state: LibraryState, scope: CoroutineScope) {
    val a = session.api ?: return
    if (state.loading) return
    val requestId = ++state.requestId
    state.loading = true
    state.error = null
    scope.launch {
        try {
            val result = session.io { a.works() }
            if (requestId == state.requestId) {
                result.fold(onSuccess = { state.works = it }, onFailure = { state.error = it.message })
            }
        } finally {
            if (requestId == state.requestId) state.loading = false
        }
    }
}

/** The works the kind and status filters admit; [MEDIA] means the experience's kinds, or the four media kinds. */
private fun filterWorks(
    all: List<Work>,
    state: LibraryState,
    experience: Experience?,
    statusOf: (String) -> LibraryStatus?,
): List<Work> = all.filter { w ->
    // MEDIA is MediaType.UNKNOWN standing in for "the media kinds", so it must never fall
    // through to the equality test: an unknown-kind work would then pass the Media filter
    // while the Media chip (which counts MEDIA_KINDS) left it out — "3074 of 3691" over a
    // chip that said 3073 on a live node.
    val kindMatches = when (state.type) {
        null -> true
        MEDIA -> MediaType.from(w.kind) in (experience?.kinds ?: MEDIA_KINDS)
        else -> MediaType.from(w.kind) == state.type
    }
    kindMatches && (state.status == null || statusOf(w.id) == state.status)
}

/** The grid's cell width: wide for film and series (and the Watch experience), poster-wide otherwise. */
private fun gridCell(state: LibraryState, experience: Experience?): Dp = if (state.type == MediaType.MOVIE ||
    state.type == MediaType.SERIES ||
    experience == Experience.WATCH
) {
    280.dp
} else {
    Tokens.posterWidth
}

/** Twelve skeleton cards, in the aspect the page will fill with. */
@Composable
private fun LibrarySkeleton(cell: Dp, aspectOf: MediaType) {
    LazyVerticalGrid(
        GridCells.Adaptive(cell),
        horizontalArrangement = Arrangement.spacedBy(Tokens.gridGap),
        verticalArrangement = Arrangement.spacedBy(Tokens.gridGap),
    ) {
        items(12) {
            MediaCardSkeleton(
                aspect = one.rarebit.heyarr.ui.theme.MediaThemes.of(
                    aspectOf,
                ).aspect,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun LibraryGrid(
    session: AppSession,
    works: List<Work>,
    cell: Dp,
    onOpenWork: (Work) -> Unit,
    /** Want on hover, for Manage → Library; null on a shelf, which shows the consumer face instead. */
    onWant: ((String, String, MediaType) -> Unit)?,
) {
    // Group by card shape when the shelf mixes them, so wide, poster and square cards each fill
    // their own rows instead of leaving holes between them.
    val groups = works.groupBy { MediaThemes.of(MediaType.from(it.kind)).aspect }
        .toSortedMap(compareBy { SHELF_ORDER.indexOf(it) })
    LazyVerticalStaggeredGrid(
        StaggeredGridCells.Adaptive(cell),
        horizontalArrangement = Arrangement.spacedBy(Tokens.gridGap),
        verticalItemSpacing = Tokens.gridGap,
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for ((aspect, group) in groups) {
            if (groups.size > 1) {
                item(key = "h:$aspect", span = StaggeredGridItemSpan.FullLine) {
                    SectionHeader(groupHeading(group), Modifier.padding(top = 8.dp))
                }
            }
            items(group, key = { it.id }) { w -> GridCard(session, w, onOpenWork, onWant) }
        }
    }
}

/** Films and series first, as on Home; then books; then music and audio. */
private val SHELF_ORDER = listOf(CardAspect.WIDE, CardAspect.POSTER, CardAspect.SQUARE)

/**
 * A shape group's heading, named by the media actually in it ("Films · Series", "Music · Feeds"):
 * in Everything, feeds and documents share the square shape with music, so the shape alone
 * would mislabel them.
 */
private fun groupHeading(group: List<Work>): String =
    group.map { MediaType.from(it.kind) }.distinct().sortedBy { it.ordinal }.joinToString(" · ") { it.plural }

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun GridCard(
    session: AppSession,
    w: Work,
    onOpenWork: (Work) -> Unit,
    onWant: ((String, String, MediaType) -> Unit)?,
) {
    val type = MediaType.from(w.kind)
    val status = session.index.statusOf(w.id)
    val shelf = onWant == null
    val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
    MediaCard(
        w.title,
        type,
        onOpen = { onOpenWork(w) },
        subtitle = w.artist ?: w.author,
        meta = listOf(w.year?.toString()),
        artwork = cover.bitmap,
        // A shelf shows the consumer face; Manage → Library keeps the want status and Want.
        status = status.takeUnless { shelf },
        onWant = onWant?.let { want -> { want(w.id, w.title, type) } },
        mode = session.mode,
        availability = w.availability(status).takeIf { shelf },
        width = null,
    )
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun LibraryList(session: AppSession, works: List<Work>, onOpenWork: (Work) -> Unit, shelf: Boolean) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(works, key = { it.id }) { w ->
            val type = MediaType.from(w.kind)
            val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
            MediaRow(
                w.title,
                type,
                onOpen = {
                    onOpenWork(w)
                },
                subtitle = w.artist ?: w.author,
                meta = listOf(
                    w.year?.toString(),
                    w.recency?.take(10),
                ),
                artwork = cover.bitmap,
                status = session.index.statusOf(w.id).takeUnless { shelf },
                availability = w.availability(session.index.statusOf(w.id)).takeIf { shelf },
            )
        }
    }
}
