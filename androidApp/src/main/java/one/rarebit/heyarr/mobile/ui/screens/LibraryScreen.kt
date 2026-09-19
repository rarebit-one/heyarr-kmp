package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.mobile.heyarr.JobInfo
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.Variants
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.LibraryStatus
import one.rarebit.heyarr.mobile.state.Toast
import one.rarebit.heyarr.mobile.theme.MediaScope
import one.rarebit.heyarr.mobile.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Cell
import one.rarebit.heyarr.mobile.ui.components.DataTable
import one.rarebit.heyarr.mobile.ui.components.EmptyState
import one.rarebit.heyarr.mobile.ui.components.ErrorState
import one.rarebit.heyarr.mobile.ui.components.FilterChip
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.IconButtonRound
import one.rarebit.heyarr.mobile.ui.components.MediaCard
import one.rarebit.heyarr.mobile.ui.components.MediaCardSkeleton
import one.rarebit.heyarr.mobile.ui.components.MediaRow
import one.rarebit.heyarr.mobile.ui.components.Notice
import one.rarebit.heyarr.mobile.ui.components.SecondaryButton
import one.rarebit.heyarr.mobile.ui.components.Section
import one.rarebit.heyarr.mobile.ui.components.SectionHeader
import one.rarebit.heyarr.mobile.ui.components.Skeleton
import one.rarebit.heyarr.mobile.ui.components.StatusPill
import one.rarebit.heyarr.mobile.ui.components.TableColumn
import one.rarebit.heyarr.mobile.ui.components.icon
import one.rarebit.heyarr.mobile.ui.components.rememberCover
import one.rarebit.heyarr.mobile.ui.components.verdictColor

/** A pseudo-kind for the default filter: the media kinds, no feeds or documents. */
val MEDIA = MediaType.UNKNOWN
private val MEDIA_KINDS = setOf(MediaType.MOVIE, MediaType.SERIES, MediaType.MUSIC, MediaType.BOOK, MediaType.AUDIOBOOK, MediaType.PODCAST)

class LibraryState {
    var tab by mutableStateOf(0)
    val downloads = DownloadsState()
    var works by mutableStateOf<List<Work>?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    /** null = every kind; [MEDIA] = films, series, music, books (the default — feeds are the archive, not the shelf). */
    var type by mutableStateOf<MediaType?>(MEDIA)
    var status by mutableStateOf<LibraryStatus?>(null)
    var grid by mutableStateOf(true)
}

/**
 * Library — everything the node catalogues, filterable by media type and by the
 * want-derived status, in a grid or list (ported from heyarr-desktop's `LibraryScreen`),
 * with a Downloads tab (the wants in flight and the job queue) and, on an enrolled
 * phone, this device's decrypted playlists.
 */
@Composable
fun LibraryScreen(session: AppSession, state: LibraryState, onOpen: (Route) -> Unit, onWant: (String, String) -> Unit, modifier: Modifier = Modifier, onPlaylists: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    fun load() {
        state.loading = true; state.error = null
        scope.launch {
            session.io { session.api.works() }.fold(onSuccess = { state.works = it }, onFailure = { state.error = it.message })
            state.loading = false
        }
    }
    LaunchedEffect(Unit) { if (state.works == null) load() }

    val variants = remember(state.works) { Variants.variantIds(state.works.orEmpty()) }
    val all = state.works.orEmpty().filter { it.id !in variants }
    val counts = all.groupingBy { MediaType.from(it.kind) }.eachCount()
    val filtered = all.filter { w ->
        (state.type == null || (state.type == MEDIA && MediaType.from(w.kind) in MEDIA_KINDS) || MediaType.from(w.kind) == state.type) &&
            (state.status == null || session.index.statusOf(w.id) == state.status)
    }

    Column(modifier.fillMaxSize().padding(horizontal = Tokens.screenPadding).padding(top = Tokens.s4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Library", icon = Icons.Rounded.GridView, subtitle = if (state.works == null) null else "${filtered.size} of ${all.size} works", trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButtonRound(Icons.Rounded.Refresh, "Refresh", ::load, enabled = !state.loading, size = 36.dp)
                IconButtonRound(Icons.Rounded.GridView, "Grid view", { state.grid = true }, filled = state.grid, size = 36.dp)
                IconButtonRound(Icons.Rounded.ViewList, "List view", { state.grid = false }, filled = !state.grid, size = 36.dp)
            }
        })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Works", state.tab == 0, { state.tab = 0 })
            FilterChip("Downloads", state.tab == 1, { state.tab = 1 }, count = state.downloads.desired?.count { it.state != "FULLY_SATISFIED" && it.state != "AVAILABLE" })
            if (onPlaylists != null) FilterChip("Playlists", false, onPlaylists)
        }
        if (state.tab == 1) { DownloadsScreen(session, state.downloads, onOpen); return@Column }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Media", state.type == MEDIA, { state.type = MEDIA }, count = all.count { MediaType.from(it.kind) in MEDIA_KINDS }.takeIf { it > 0 })
            for (t in listOf(MediaType.MOVIE, MediaType.SERIES, MediaType.MUSIC, MediaType.BOOK, MediaType.PODCAST)) {
                MediaScope(t) { FilterChip(t.plural, state.type == t, { state.type = if (state.type == t) MEDIA else t }, icon = t.icon(), count = counts[t]?.takeIf { it > 0 }) }
            }
            MediaScope(MediaType.FEED) { FilterChip("Feeds", state.type == MediaType.FEED, { state.type = if (state.type == MediaType.FEED) MEDIA else MediaType.FEED }, icon = MediaType.FEED.icon(), count = counts[MediaType.FEED]?.takeIf { it > 0 }) }
            FilterChip("Everything", state.type == null, { state.type = null }, count = all.size.takeIf { it > 0 })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Status", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
            FilterChip("Any", state.status == null, { state.status = null })
            for (s in LibraryStatus.entries) FilterChip(s.label, state.status == s, { state.status = if (state.status == s) null else s })
        }
        when {
            state.error != null && state.works == null -> ErrorState("Couldn't load the library", state.error, ::load)
            state.works == null -> LazyVerticalGrid(GridCells.Adaptive(if (state.type == MediaType.MOVIE || state.type == MediaType.SERIES) 280.dp else Tokens.posterWidth), horizontalArrangement = Arrangement.spacedBy(Tokens.s3), verticalArrangement = Arrangement.spacedBy(Tokens.s3)) { items(9) { MediaCardSkeleton(width = Tokens.posterWidth, aspect = one.rarebit.heyarr.mobile.theme.MediaThemes.of(state.type ?: MediaType.BOOK).aspect) } }
            filtered.isEmpty() -> EmptyState(if (all.isEmpty()) "The library is empty" else "Nothing matches these filters", detail = if (all.isEmpty()) "Scan a library root on the node, or Want something and let heyarr find it." else "Clear a filter to see more.")
            state.grid -> LazyVerticalGrid(
                GridCells.Adaptive(if (state.type == MediaType.MOVIE || state.type == MediaType.SERIES) 280.dp else Tokens.posterWidth), horizontalArrangement = Arrangement.spacedBy(Tokens.s3), verticalArrangement = Arrangement.spacedBy(Tokens.s3),
                contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.id }) { w ->
                    val type = MediaType.from(w.kind)
                    val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
                    MediaCard(w.title, type, onOpen = { onOpen(detailRoute(w.id, type, w.title, from = "Library")) }, subtitle = w.artist ?: w.author, meta = listOf(w.year?.toString()), artwork = cover.url, status = session.index.statusOf(w.id), onWant = { onWant(w.id, w.title) }, width = null, modifier = Modifier.fillMaxWidth())
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { w ->
                    val type = MediaType.from(w.kind)
                    val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
                    MediaRow(w.title, type, onOpen = { onOpen(detailRoute(w.id, type, w.title, from = "Library")) }, subtitle = w.artist ?: w.author, meta = listOf(w.year?.toString(), w.recency?.take(10)), artwork = cover.url, status = session.index.statusOf(w.id))
                }
            }
        }
    }
}

class DownloadsState {
    var desired by mutableStateOf<List<DesiredItem>?>(null)
    var jobs by mutableStateOf<List<JobInfo>?>(null)
    var titles by mutableStateOf<Map<String, String>>(emptyMap())
    var error by mutableStateOf<String?>(null)
}

/**
 * Library → Downloads: what heyarr is doing about the wants right now. Two tables —
 * the wants in flight (`GET /desired`: acquisition state, phase, whether a download
 * client holds it, the node's own detail line) and the job queue (`GET /jobs`: type,
 * state, attempts, last error). Honest about the limit: the node reports STATE, not a
 * percentage — a transfer's progress lives in the download client, which heyarr does
 * not relay yet. Refreshes every 10 s while open.
 */
@Composable
fun DownloadsScreen(session: AppSession, state: DownloadsState, onOpen: (Route) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    fun load() {
        state.error = null
        scope.launch {
            session.io { session.api.desired() }.fold(onSuccess = { state.desired = it }, onFailure = { state.error = it.message })
            session.io { session.api.jobs(40) }.onSuccess { state.jobs = it }
            val wanted = state.desired.orEmpty().mapNotNull { it.workId }.distinct().filter { it !in state.titles }
            if (wanted.isNotEmpty()) session.io { session.api.works() }.onSuccess { works -> state.titles = state.titles + works.associate { it.id to it.title } }
        }
    }
    LaunchedEffect(Unit) { while (isActive) { load(); delay(10_000) } }

    val inFlight = state.desired?.filter { it.state != "FULLY_SATISFIED" && it.state != "AVAILABLE" }.orEmpty()
    val recentJobs = state.jobs.orEmpty().sortedByDescending { it.updatedAt ?: "" }

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Notice("The node reports each want's acquisition state and phase, not a percentage — a transfer's byte count lives in the download client and heyarr does not relay it yet.", tone = Tokens.slate) }
        item {
            Section("Acquiring", subtitle = "${inFlight.size} wants not yet satisfied", trailing = { GhostButton("Refresh", ::load, icon = Icons.Rounded.Refresh) }) {
                when {
                    state.error != null && state.desired == null -> Notice("Couldn't load wants: ${state.error}", tone = Tokens.danger)
                    state.desired == null -> Skeleton(Modifier.fillMaxWidth().height(80.dp))
                    else -> DataTable(
                        columns = listOf(TableColumn("Want", width = 200.dp), TableColumn("Status", width = 100.dp), TableColumn("Phase", width = 100.dp), TableColumn("Client", width = 90.dp), TableColumn("Detail", width = 240.dp), TableColumn("", width = 150.dp, alignEnd = true)),
                        rowCount = inFlight.size, emptyText = "Nothing in flight — every want is satisfied.", minWidth = 880.dp,
                    ) { r, c ->
                        val w = inFlight[r]
                        when (c) {
                            0 -> Cell(state.titles[w.workId] ?: w.workId ?: w.id)
                            1 -> StatusPill(LibraryStatus.ofState(w.state))
                            2 -> Cell(w.phase ?: "—", muted = true, mono = true)
                            3 -> Cell(if (w.managed == true || w.state == "SELECTED") "handed off" else "—", muted = true)
                            4 -> Cell(w.detail ?: "", muted = true, maxLines = 2)
                            5 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val wid = w.workId
                                if (wid != null) GhostButton("Open", { onOpen(detailRoute(wid, MediaType.UNKNOWN, state.titles[wid], from = "Downloads", curate = true)) })
                                SecondaryButton("Search", { scope.launch { session.io { session.api.searchReleases(w.id) }.onSuccess { res -> when (res) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued"); is McpResult.Refused -> session.refused(res) } } } }, icon = Icons.Rounded.Search, compact = true)
                            }
                        }
                    }
                }
            }
        }
        item {
            Section("Job queue", subtitle = "The node's recent work — searches, ingests, probes, scans", initiallyOpen = true) {
                when (val jobs = state.jobs) {
                    null -> Skeleton(Modifier.fillMaxWidth().height(80.dp))
                    else -> DataTable(
                        columns = listOf(TableColumn("Job", width = 150.dp), TableColumn("State", width = 90.dp), TableColumn("Attempts", width = 70.dp, alignEnd = true), TableColumn("Updated", width = 130.dp), TableColumn("Last error", width = 240.dp)),
                        rowCount = minOf(recentJobs.size, 25), emptyText = "The queue is empty.", minWidth = 720.dp,
                    ) { r, c ->
                        val j = recentJobs[r]
                        when (c) {
                            0 -> Cell(j.type.replace('_', ' '), mono = true)
                            1 -> Text(j.state, style = MaterialTheme.typography.labelMedium, color = verdictColor(when (j.state) { "succeeded" -> "pass"; "dead", "failed" -> "fail"; "running", "leased" -> "undetermined"; else -> "" }))
                            2 -> Cell("${j.attempts}", muted = true)
                            3 -> Cell(j.updatedAt?.replace('T', ' ')?.take(16) ?: "", muted = true, mono = true)
                            4 -> Cell(j.lastError ?: "", muted = j.lastError == null, color = Tokens.danger, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
