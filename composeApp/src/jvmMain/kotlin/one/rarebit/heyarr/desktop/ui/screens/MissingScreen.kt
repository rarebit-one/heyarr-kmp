package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.mcp.Want
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.MediaRow
import one.rarebit.heyarr.ui.components.ArchiveVignette
import one.rarebit.heyarr.ui.components.EditorialRule
import one.rarebit.heyarr.ui.components.EmptyState
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.theme.Tokens

class MissingState {
    /** The session generation this state was loaded for. */
    var generation = -1
    var missing by mutableStateOf<List<Want>?>(null)
    var upgrades by mutableStateOf<List<Want>?>(null)
    var error by mutableStateOf<String?>(null)
    var tab by mutableStateOf(0)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var busy by mutableStateOf(false)
}

/**
 * Missing / Wanted — `get_missing_content` and `get_upgrade_candidates`, with bulk
 * actions over a selection: search indexers now (`search_releases`), monitor on/off
 * (`monitor_content`). Plus "Want by title" for content the library has never seen.
 */
@Composable
fun MissingScreen(
    session: AppSession,
    state: MissingState,
    onOpen: (Route) -> Unit,
    onWantTitle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    fun load() {
        val a = session.api ?: return
        state.error = null
        scope.launch {
            session.io { a.missing() }.fold(onSuccess = { state.missing = it }, onFailure = {
                state.error =
                    it.message
            })
        }
        scope.launch {
            session.io { a.upgradeCandidates() }.fold(onSuccess = { state.upgrades = it }, onFailure = {
                state.error =
                    it.message
            })
        }
    }
    // Loaded once per node: a new URL or token (session.generation) throws the cached answer away.
    LaunchedEffect(session.generation) {
        if (state.missing == null || state.generation != session.generation) {
            state.generation = session.generation
            load()
        }
    }

    val list = (if (state.tab == 0) state.missing else state.upgrades)
    val sel = state.selected.filter { id -> list?.any { it.desiredItemId == id } == true }.toSet()

    fun bulk(label: String, action: (String) -> McpResult<*>) {
        val a = session.api ?: return
        if (sel.isEmpty()) return
        scope.launch {
            state.busy = true
            var ok = 0
            var refused = 0
            for (id in sel) {
                session.io { action(id) }.onSuccess { r ->
                    if (r is McpResult.Refused) {
                        refused++
                        session.refused(r)
                    } else {
                        ok++
                    }
                }
            }
            state.busy = false
            session.toast(
                if (refused ==
                    0
                ) {
                    Toast.Kind.SUCCESS
                } else {
                    Toast.Kind.INFO
                },
                "$label: $ok done${if (refused > 0) ", $refused refused" else ""}",
            )
            session.refreshIndex()
            load()
        }
        @Suppress("UNUSED_VARIABLE")
        val unused = a
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Missing & wanted", subtitle = "What should exist, and what could be better", trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Refresh", ::load, icon = HeyarrIcons.Refresh)
                PrimaryButton("Want by title", onWantTitle, icon = HeyarrIcons.Add, compact = true)
            }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip("Missing", state.tab == 0, { state.tab = 0 }, count = state.missing?.size)
            FilterChip("Could be better", state.tab == 1, { state.tab = 1 }, count = state.upgrades?.size)
            Spacer(Modifier.weight(1f))
            if (list != null && list.isNotEmpty()) {
                GhostButton(if (sel.size == list.size) "Select none" else "Select all", {
                    state.selected = if (sel.size == list.size) emptySet() else list.map { it.desiredItemId }.toSet()
                })
            }
        }
        // The bulk bar appears only once something is selected, so triage starts with the list.
        if (sel.isNotEmpty()) {
            BulkBar(
                count = sel.size,
                busy = state.busy,
                onSearch = { session.api?.let { a -> bulk("Search queued") { id -> a.searchReleases(id) } } },
                onMonitor = { on ->
                    session.api?.let { a ->
                        bulk(if (on) "Monitoring on" else "Monitoring off") { id -> a.monitor(id, on) }
                    }
                },
                onClear = { state.selected = emptySet() },
            )
        }
        when {
            state.error != null && list == null -> ErrorState("Couldn't load wants", state.error, ::load)

            list == null -> MediaRowSkeleton(6)

            list.isEmpty() -> EmptyState(
                if (state.tab ==
                    0
                ) {
                    "Nothing is missing"
                } else {
                    "Nothing to upgrade"
                },
                vignette = ArchiveVignette.EMPTY,
                detail = if (state.tab ==
                    0
                ) {
                    "Every want is satisfied. Want something new by title, or from Search."
                } else {
                    "No satisfied, monitored want has room to improve under its profile."
                },
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.desiredItemId }) { w ->
                    if (w.desiredItemId != list.first().desiredItemId) {
                        EditorialRule(Modifier.padding(start = 60.dp, end = 8.dp), dashed = true)
                    }
                    val checked = w.desiredItemId in sel
                    MediaRow(
                        w.subject,
                        MediaType.UNKNOWN,
                        onOpen = {
                            w.workId?.let { onOpen(Route.Detail(it, MediaType.UNKNOWN, w.title, from = "Missing")) }
                        },
                        subtitle = listOfNotNull(
                            w.context,
                            w.qualityProfile?.let {
                                "profile $it"
                            },
                            w.reason,
                        ).joinToString("  ·  "),
                        meta = listOf(
                            w.state.lowercase().replace('_', ' '),
                            if (w.monitor) "monitored" else "not monitored",
                        ),
                        status = LibraryStatus.ofState(w.state),
                        selected = checked,
                        trailing = {
                            IconButtonRound(
                                if (checked) HeyarrIcons.CheckBox else HeyarrIcons.CheckBoxBlank,
                                if (checked) "Deselect ${w.subject}" else "Select ${w.subject}",
                                {
                                    state.selected =
                                        if (checked) {
                                            state.selected - w.desiredItemId
                                        } else {
                                            state.selected +
                                                w.desiredItemId
                                        }
                                },
                                size = 32.dp,
                            )
                        },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/** The selection's actions, on a raised strip that only exists while something is selected. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun BulkBar(
    count: Int,
    busy: Boolean,
    onSearch: () -> Unit,
    onMonitor: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Tokens.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count selected", style = MaterialTheme.typography.labelMedium, color = Tokens.textPrimary)
        Spacer(Modifier.weight(1f))
        SecondaryButton("Search now", onSearch, icon = HeyarrIcons.Search, compact = true, enabled = !busy)
        SecondaryButton("Monitor", { onMonitor(true) }, compact = true, enabled = !busy)
        SecondaryButton("Unmonitor", { onMonitor(false) }, compact = true, enabled = !busy)
        GhostButton("Clear", onClear)
    }
}
