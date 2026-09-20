package one.rarebit.heyarr.desktop.ui.screens

import one.rarebit.heyarr.desktop.ui.components.icon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.ArtworkLoader
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.desktop.state.SearchController
import one.rarebit.heyarr.core.state.SearchFilter
import one.rarebit.heyarr.core.state.SearchGrouping
import one.rarebit.heyarr.core.state.SearchRow
import one.rarebit.heyarr.core.state.SearchSection
import one.rarebit.heyarr.core.state.Segment
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.desktop.theme.LocalMediaTheme
import one.rarebit.heyarr.desktop.theme.MediaScope
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.EmptyState
import one.rarebit.heyarr.desktop.ui.components.FilterChip
import one.rarebit.heyarr.desktop.ui.components.GhostButton
import one.rarebit.heyarr.desktop.ui.components.IconButtonRound
import one.rarebit.heyarr.desktop.ui.components.Kbd
import one.rarebit.heyarr.desktop.ui.components.MediaRow
import one.rarebit.heyarr.desktop.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.desktop.ui.components.Notice
import one.rarebit.heyarr.desktop.ui.components.PrimaryButton
import one.rarebit.heyarr.desktop.ui.components.SecondaryButton
import one.rarebit.heyarr.desktop.ui.components.SectionHeader

/**
 * Universal search — one box, every media kind, results grouped by type and streamed in
 * per segment as each `search_content` call lands. ↑/↓ move a selection through the
 * flattened rows, Enter opens it, Esc clears; Ctrl+F (handled by the shell)
 * lands the focus here. Recent searches are local to this machine and say so.
 */
@Composable
fun SearchScreen(
    session: AppSession,
    search: SearchController,
    onOpen: (Route) -> Unit,
    onWant: (workId: String, title: String, type: MediaType) -> Unit,
    onWantTitle: WantByTitle,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    initialDiscover: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var discovering by remember { mutableStateOf(initialDiscover) }
    var recent by remember { mutableStateOf(session.recent.load()) }
    val sections = search.sections
    val rows = search.rows
    val listState = rememberLazyListState()

    fun open(row: SearchRow) {
        session.recent.push(search.query).also { recent = it }
        when (row) {
            is SearchRow.WorkRow -> onOpen(Route.Detail(row.hit.workId, MediaType.from(row.hit.contentType), row.hit.title, from = "Search"))
            is SearchRow.EpisodeRow -> row.hit.workId?.let { onOpen(Route.Detail(it, MediaType.SERIES, row.hit.workTitle ?: row.hit.title, from = "Search")) }
            is SearchRow.SourceRow -> row.source.workId?.let { onOpen(Route.Detail(it, MediaType.from(row.source.type), row.source.title, from = "Search")) }
        }
    }

    LaunchedEffect(search.selected) { if (search.selected >= 0) listState.animateScrollToItem(search.selected.coerceAtMost(maxOf(0, listState.layoutInfo.totalItemsCount - 1))) }

    Column(modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SearchBox(
            value = search.query,
            onValueChange = search::updateQuery,
            onSubmit = { if (!discovering) search.selectedRow()?.let(::open) ?: search.submit() },
            onMove = { if (!discovering) search.moveSelection(it) },
            onClear = { search.updateQuery("") },
            focusRequester = focusRequester,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Library", !discovering, { discovering = false }, icon = Icons.Rounded.Search)
            FilterChip("Discover", discovering, { discovering = true }, icon = Icons.Rounded.TravelExplore)
        }
        if (discovering) {
            ProviderSearchPane(session, search.query, onWantTitle)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in SearchFilter.entries) {
                val count = if (f == SearchFilter.ALL) null else sections.firstOrNull { f.admits(it.type) }?.rows?.size?.takeIf { it > 0 }
                MediaScope(f.type ?: MediaType.MOVIE) { FilterChip(f.label, search.filter == f, { search.filter = f; search.selected = -1 }, count = count) }
            }
        }
        when {
            search.isIdle -> IdlePane(recent, onPick = { search.updateQuery(it) }, onClear = { session.recent.clear(); recent = emptyList() })
            SearchGrouping.empty(sections) -> EmptyState("Nothing in the library matches “${search.query}”", detail = "Discover can find titles to add from the metadata catalogue.", action = { SecondaryButton("Discover titles", { discovering = true }, icon = Icons.Rounded.TravelExplore) })
            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                var index = 0
                for (section in sections) {
                    if (section.segment is Segment.Loaded && section.rows.isEmpty()) continue
                    item(key = "h:" + section.type) { MediaScope(section.type) { SectionHeader(section.title, icon = section.type.icon(), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp), subtitle = (section.segment as? Segment.Loaded)?.let { if (it.truncated) "Showing the first ${it.rows.size} — narrow the query for more." else null }) } }
                    when (val seg = section.segment) {
                        Segment.Pending -> item(key = "p:" + section.type) { MediaRowSkeleton(2) }
                        is Segment.Failed -> item(key = "f:" + section.type) { Notice("Couldn't search ${section.title.lowercase()}: ${seg.message}", tone = Tokens.danger) }
                        is Segment.Loaded -> {
                            val start = index
                            items(seg.rows, key = { it.key }) { row ->
                                val i = start + seg.rows.indexOf(row)
                                ResultRow(session, row, selected = i == search.selected, onOpen = { open(row) }, onWant = onWant)
                            }
                            index += seg.rows.size
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ResultRow(session: AppSession, row: SearchRow, selected: Boolean, onOpen: () -> Unit, onWant: (String, String, MediaType) -> Unit) {
    when (row) {
        is SearchRow.WorkRow -> {
            val hit = row.hit
            val cover by rememberCover(session, row.type, hit.title, hit.artworkPath, hit.year, hit.creator)
            val status = session.index.statusOf(hit.workId)
            MediaRow(
                title = hit.title, type = row.type, onOpen = onOpen, subtitle = hit.creator,
                meta = listOf(hit.year?.toString(), hit.attributes["runtime"], hit.attributes["album"], hit.attributes["series"]),
                artwork = cover.bitmap, status = status, selected = selected,
                trailing = {
                    // Want writes desired state (enrolled-only Surface.WANT): hide it for a
                    // guest — GuestGate is the single source of truth — and fall back to Open.
                    if (status == LibraryStatus.NOT_TRACKED && GuestGate.allows(session.mode, Surface.WANT)) PrimaryButton("Want", { onWant(hit.workId, hit.title, row.type) }, icon = Icons.Rounded.Add, compact = true, contentDescription = "Want ${hit.title}")
                    else SecondaryButton("Open", onOpen, compact = true)
                },
            )
        }
        is SearchRow.EpisodeRow -> MediaRow(
            title = row.hit.title, type = row.type, onOpen = onOpen, subtitle = row.hit.workTitle,
            meta = listOf(row.hit.kind, if (row.hit.blobHash != null) "file held" else "no file"),
            status = if (row.hit.blobHash != null) LibraryStatus.IN_LIBRARY else null, selected = selected,
        )
        is SearchRow.SourceRow -> {
            val cover by rememberCover(session, row.type, row.source.title, null, feedRef = row.source.feedRef)
            MediaRow(
                title = row.source.title, type = row.type, onOpen = onOpen, subtitle = row.source.feedRef,
                meta = listOf(row.source.type, "${row.source.itemsArchived}/${row.source.itemsKnown} archived", row.source.health),
                artwork = cover.bitmap, status = LibraryStatus.IN_LIBRARY, selected = selected,
            )
        }
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, onMove: (Int) -> Unit, onClear: () -> Unit, focusRequester: FocusRequester) {
    val accent = LocalMediaTheme.current.accent
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .background(Tokens.surface1, shape)
            .border(if (focused) 2.dp else Tokens.hairline, if (focused) accent else Tokens.border, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = if (focused) accent else Tokens.textMuted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search movies, series, music, books, podcasts…", style = MaterialTheme.typography.bodyLarge, color = Tokens.textDisabled)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tokens.textPrimary),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { onMove(1); true }
                            Key.DirectionUp -> { onMove(-1); true }
                            Key.Enter -> { onSubmit(); true }
                            Key.Escape -> { onClear(); true }
                            else -> false
                        }
                    }
                    .semantics { this.contentDescription = "Universal search" },
                interactionSource = interaction,
            )
        }
        if (value.isNotEmpty()) IconButtonRound(Icons.Rounded.Close, "Clear search", onClear, size = 28.dp)
        else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Kbd("Ctrl+F"); Kbd("↑↓"); Kbd("↵") }
    }
}

@Composable
private fun IdlePane(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (recent.isEmpty()) {
            EmptyState("Search everything at once", detail = "Movies, series, music and books come from the library; podcasts and feeds from what you follow. Results appear per type as each answer lands.")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Recent searches", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, modifier = Modifier.weight(1f))
                GhostButton("Clear", onClear)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (q in recent) FilterChip(q, false, { onPick(q) }) }
            Text("Kept on this machine only — heyarr's personal history is encrypted controller-side and not reachable from here.", style = MaterialTheme.typography.bodySmall, color = Tokens.textDisabled)
        }
    }
}

/** Metadata discovery shares the query but keeps provider answers separate from owned works. */
@Composable
private fun ProviderSearchPane(session: AppSession, query: String, onWantTitle: WantByTitle) {
    var result by remember(query, session.generation) { mutableStateOf<McpResult<List<DiscoveryHit>>?>(null) }
    var busy by remember(query, session.generation) { mutableStateOf(query.isNotBlank()) }
    LaunchedEffect(query, session.generation) {
        if (query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        val api = session.api
        result = if (api == null) null else session.io { api.discover(query.trim()) }.getOrNull()
        busy = false
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("Discover", icon = Icons.Rounded.TravelExplore, subtitle = "Find titles to add — series, movies, books and music the node's metadata providers know about but the library doesn't hold yet.") }
        item {
            if (query.isBlank()) Text("Enter a title above to explore the metadata catalogue.", color = Tokens.textMuted)
            else DiscoveryResults(session, query, result, busy, onWantTitle)
        }
    }
}
