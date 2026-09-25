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
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.icon
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.Tokens

/** A pseudo-kind for the default filter: the four media kinds, no feeds or documents. */
val MEDIA = MediaType.UNKNOWN
internal val MEDIA_KINDS =
    setOf(MediaType.MOVIE, MediaType.SERIES, MediaType.MUSIC, MediaType.BOOK, MediaType.AUDIOBOOK, MediaType.PODCAST)

class LibraryState {
    /** The session generation this state was loaded for. */
    var generation = -1
    var tab by mutableStateOf(0)
    val downloads = DownloadsState()
    var works by mutableStateOf<List<Work>?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    /** null = every kind; [MEDIA] = films, series, music, books (the default — feeds are the Archive, not the shelf). */
    var type by mutableStateOf<MediaType?>(MEDIA)
    var status by mutableStateOf<LibraryStatus?>(null)
    var grid by mutableStateOf(true)
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
    LaunchedEffect(state, session.generation) {
        if (state.works == null || state.generation != session.generation) {
            state.generation = session.generation
            load()
        }
    }

    val variants = remember(state.works) { Variants.variantIds(state.works.orEmpty()) }
    val all = state.works.orEmpty().filter {
        it.id !in variants &&
            (experience == null || MediaType.from(it.kind) in experience.kinds)
    }
    val counts = all.groupingBy { MediaType.from(it.kind) }.eachCount()
    val filtered = filterWorks(all, state, experience) { session.index.statusOf(it) }

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
            subtitle = if (state.works ==
                null
            ) {
                null
            } else {
                "${filtered.size} of ${all.size} works"
            },
            trailing = { LibraryActions(state, ::load) },
        )
        if (experience == null) LibraryTabs(state)
        if (experience == null && state.tab == 1) {
            DownloadsScreen(session, state.downloads, onOpen)
            return@Column
        }
        KindFilterChips(state, all, counts, experience)
        StatusFilterChips(state)
        when {
            state.error != null && state.works == null -> ErrorState("Couldn't load the library", state.error, ::load)

            state.works == null -> LibrarySkeleton(
                gridCell(state, experience),
                experience?.kinds?.firstOrNull() ?: state.type ?: MediaType.BOOK,
            )

            filtered.isEmpty() -> EmptyState(
                if (all.isEmpty()) "The library is empty" else "Nothing matches these filters",
                detail = if (all.isEmpty()) "Scan a library root on the node, or Want something and let heyarr find it." else "Clear a filter to see more.",
            )

            state.grid -> LibraryGrid(session, filtered, gridCell(state, experience), openWork, onWant)

            else -> LibraryList(session, filtered, openWork)
        }
    }
}

/** Load (or reload) every work the node catalogues. */
private fun loadLibrary(session: AppSession, state: LibraryState, scope: CoroutineScope) {
    val a = session.api ?: return
    state.loading = true
    state.error = null
    scope.launch {
        session.io { a.works() }.fold(onSuccess = { state.works = it }, onFailure = { state.error = it.message })
        state.loading = false
    }
}

/** The works the kind and status filters admit; [MEDIA] means the experience's kinds, or the four media kinds. */
private fun filterWorks(
    all: List<Work>,
    state: LibraryState,
    experience: Experience?,
    statusOf: (String) -> LibraryStatus?,
): List<Work> = all.filter { w ->
    (
        state.type == null || (
            state.type == MEDIA && MediaType.from(
                w.kind,
            ) in (experience?.kinds ?: MEDIA_KINDS)
            ) ||
            MediaType.from(w.kind) == state.type
        ) &&
        (state.status == null || statusOf(w.id) == state.status)
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
private fun LibraryGrid(
    session: AppSession,
    works: List<Work>,
    cell: Dp,
    onOpenWork: (Work) -> Unit,
    onWant: (String, String, MediaType) -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Adaptive(cell),
        horizontalArrangement = Arrangement.spacedBy(Tokens.gridGap),
        verticalArrangement = Arrangement.spacedBy(Tokens.gridGap),
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(works, key = { it.id }) { w ->
            val type = MediaType.from(w.kind)
            val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
            MediaCard(
                w.title, type, onOpen = {
                    onOpenWork(w)
                }, subtitle = w.artist ?: w.author,
                meta = listOf(
                    w.year?.toString(),
                ),
                artwork = cover.bitmap, status = session.index.statusOf(w.id), onWant = {
                    onWant(w.id, w.title, type)
                }, mode = session.mode, width = Tokens.posterWidth,
            )
        }
    }
}

@Composable
private fun LibraryList(session: AppSession, works: List<Work>, onOpenWork: (Work) -> Unit) {
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
                status = session.index.statusOf(w.id),
            )
        }
    }
}
