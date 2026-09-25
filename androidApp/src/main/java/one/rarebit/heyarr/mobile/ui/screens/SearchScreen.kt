package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.core.mcp.EpisodeHit
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.SearchFilter
import one.rarebit.heyarr.core.state.SearchGrouping
import one.rarebit.heyarr.core.state.SearchRow
import one.rarebit.heyarr.core.state.Segment
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.SearchController
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.MediaRow
import one.rarebit.heyarr.mobile.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.EmptyState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope

/**
 * Universal search — one box, every media kind, results grouped by type and streamed in
 * per segment as each `search_content` call lands (ported from heyarr-desktop's
 * `SearchScreen`). Recent searches are local to this phone and say so.
 */
@Composable
fun SearchScreen(
    session: AppSession,
    search: SearchController,
    onOpen: (Route) -> Unit,
    onWant: (workId: String, title: String) -> Unit,
    onPlayEpisode: (EpisodeHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var recent by remember { mutableStateOf(session.recent.load()) }
    val sections = search.sections

    fun open(row: SearchRow) {
        session.recent.push(search.query).also { recent = it }
        when (row) {
            is SearchRow.WorkRow -> onOpen(
                detailRoute(row.hit.workId, MediaType.from(row.hit.contentType), row.hit.title, from = "Search"),
            )

            is SearchRow.EpisodeRow -> row.hit.workId?.let {
                onOpen(
                    detailRoute(
                        it,
                        MediaType.SERIES,
                        row.hit.workTitle ?: row.hit.title,
                        from = "Search",
                    ),
                )
            }

            is SearchRow.SourceRow -> row.source.workId?.let {
                onOpen(detailRoute(it, MediaType.from(row.source.type), row.source.title, from = "Search"))
            }
        }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = Tokens.screenPadding).padding(top = Tokens.s4),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchBox(value = search.query, onValueChange = search::updateQuery, onSubmit = {
            search.submit()
            session.recent.push(search.query).also { recent = it }
        }, onClear = { search.updateQuery("") })
        SearchFilterChips(search)
        when {
            search.isIdle -> IdlePane(recent, onPick = { search.updateQuery(it) }, onClear = {
                session.recent.clear()
                recent = emptyList()
            })

            SearchGrouping.empty(sections) -> NoResultsPane(session, search.query, onWant)

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                for (section in sections) {
                    if (section.segment is Segment.Loaded && section.rows.isEmpty()) continue
                    item(key = "h:" + section.type) {
                        MediaScope(section.type) {
                            SectionHeader(
                                section.title,
                                Modifier.padding(top = 12.dp, bottom = 6.dp),
                                subtitle = (section.segment as? Segment.Loaded)?.let {
                                    if (it.truncated) "Showing the first ${it.rows.size} — narrow the query for more." else null
                                },
                            )
                        }
                    }
                    when (val seg = section.segment) {
                        Segment.Pending -> item(key = "p:" + section.type) { MediaRowSkeleton(2) }

                        is Segment.Failed -> item(key = "f:" + section.type) {
                            Notice("Couldn't search ${section.title.lowercase()}: ${seg.message}", tone = Tokens.danger)
                        }

                        is Segment.Loaded -> items(seg.rows, key = {
                            it.key
                        }) { row ->
                            ResultRow(session, row, onOpen = {
                                open(row)
                            }, onWant = onWant, onPlayEpisode = onPlayEpisode)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    session: AppSession,
    row: SearchRow,
    onOpen: () -> Unit,
    onWant: (String, String) -> Unit,
    onPlayEpisode: (EpisodeHit) -> Unit,
) {
    when (row) {
        is SearchRow.WorkRow -> WorkResultRow(session, row, onOpen, onWant)

        is SearchRow.EpisodeRow -> MediaRow(
            title = row.hit.title,
            type = row.type,
            onOpen = onOpen,
            subtitle = row.hit.workTitle,
            meta = listOf(row.hit.kind, if (row.hit.blobHash != null) "file held" else "no file"),
            status = if (row.hit.blobHash != null) LibraryStatus.IN_LIBRARY else null,
            trailing = {
                if (row.hit.blobHash !=
                    null
                ) {
                    IconButtonRound(Icons.Rounded.PlayArrow, "Play ${row.hit.title}", {
                        onPlayEpisode(row.hit)
                    }, size = 36.dp, filled = true)
                }
            },
        )

        is SearchRow.SourceRow -> {
            val cover by rememberCover(session, row.type, row.source.title, null, feedRef = row.source.feedRef)
            MediaRow(
                title = row.source.title,
                type = row.type,
                onOpen = onOpen,
                subtitle = row.source.feedRef,
                meta = listOf(
                    row.source.type,
                    "${row.source.itemsArchived}/${row.source.itemsKnown} archived",
                    row.source.health,
                ),
                artwork = cover.url,
                status = LibraryStatus.IN_LIBRARY,
            )
        }
    }
}

/** One chip per filter, each counting its section's rows once they land. */
@Composable
private fun SearchFilterChips(search: SearchController) {
    val sections = search.sections
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in SearchFilter.entries) {
            val count = if (f ==
                SearchFilter.ALL
            ) {
                null
            } else {
                sections.firstOrNull { f.admits(it.type) }?.rows?.size?.takeIf { it > 0 }
            }
            MediaScope(f.type ?: MediaType.MOVIE) {
                FilterChip(f.label, search.filter == f, { search.filter = f }, count = count)
            }
        }
    }
}

/** A work hit: cover, meta, library status, and Want while it is not tracked. */
@Composable
private fun WorkResultRow(
    session: AppSession,
    row: SearchRow.WorkRow,
    onOpen: () -> Unit,
    onWant: (String, String) -> Unit,
) {
    val hit = row.hit
    val cover by rememberCover(session, row.type, hit.title, hit.artworkPath, hit.year, hit.creator)
    val status = session.index.statusOf(hit.workId)
    MediaRow(
        title = hit.title,
        type = row.type,
        onOpen = onOpen,
        subtitle = hit.creator,
        meta = listOf(
            hit.year?.toString(),
            hit.attributes["runtime"],
            hit.attributes["album"],
            hit.attributes["series"],
        ),
        artwork = cover.url,
        status = status,
        trailing = {
            if (status ==
                LibraryStatus.NOT_TRACKED
            ) {
                IconButtonRound(Icons.Rounded.Add, "Want ${hit.title}", {
                    onWant(hit.workId, hit.title)
                }, size = 36.dp)
            }
        },
    )
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, onClear: () -> Unit) {
    val accent = LocalMediaTheme.current.accent
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .background(Tokens.surface1, shape)
            .border(if (focused) 2.dp else Tokens.hairline, if (focused) accent else Tokens.border, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = if (focused) accent else Tokens.textMuted,
            modifier = Modifier.size(20.dp),
        )
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Search movies, series, music, books, podcasts…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textDisabled,
                )
            }
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tokens.textPrimary),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().semantics { this.contentDescription = "Universal search" },
                interactionSource = interaction,
            )
        }
        if (value.isNotEmpty()) IconButtonRound(Icons.Rounded.Close, "Clear search", onClear, size = 32.dp)
    }
}

@Composable
private fun IdlePane(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (recent.isEmpty()) {
            EmptyState(
                "Search everything at once",
                detail = "Movies, series, music and books come from the library; podcasts and feeds from what you follow. Results appear per type as each answer lands.",
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = Tokens.textMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Recent searches",
                    style = MaterialTheme.typography.titleSmall,
                    color = Tokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                GhostButton("Clear", onClear)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (q in recent) FilterChip(q, false, { onPick(q) })
            }
            Text(
                "Kept on this phone only — not synced anywhere, not part of your encrypted personal state.",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textDisabled,
            )
        }
    }
}

/** No library match: offer the honest next step — ask the metadata provider, quoting its refusal when there is none. */
@Composable
private fun NoResultsPane(session: AppSession, query: String, onWant: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var discovery by remember(query) { mutableStateOf<McpResult<List<DiscoveryHit>>?>(null) }
    var busy by remember(query) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EmptyState(
            "Nothing in the library matches “$query”",
            detail = "Search matches titles the library already knows. To bring in something new, ask the metadata provider or Want it by title from the Missing screen.",
            action = {
                SecondaryButton("Ask the metadata provider", icon = Icons.Rounded.TravelExplore, enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        session.io { session.api.discover(query) }.onSuccess { discovery = it }
                        busy = false
                    }
                })
            },
        )
        when (val d = discovery) {
            null -> {}

            is McpResult.Refused -> Notice(
                "discover_content: ${d.message}",
                detail = "Discovery needs a TVDB provider configured on the node (ADR-0058). Wanting by title still works.",
            )

            is McpResult.Ok -> if (d.value.isEmpty()) {
                Text(
                    "The provider found nothing for “$query”.",
                    color = Tokens.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (hit in d.value) {
                        MediaRow(
                            hit.title,
                            MediaType.SERIES,
                            onOpen = {
                            },
                            subtitle = hit.overview,
                            meta = listOf(
                                hit.year?.toString(),
                                hit.tvdbId?.let {
                                    "tvdb $it"
                                },
                            ),
                            status = LibraryStatus.NOT_TRACKED,
                        )
                    }
                }
            }
        }
    }
}
