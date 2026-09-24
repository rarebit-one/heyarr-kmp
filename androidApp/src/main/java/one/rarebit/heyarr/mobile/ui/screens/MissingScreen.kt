package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.core.mcp.Want
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.mobile.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.EmptyState
import one.rarebit.heyarr.mobile.ui.components.ErrorState
import one.rarebit.heyarr.mobile.ui.components.FilterChip
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.IconButtonRound
import one.rarebit.heyarr.mobile.ui.components.MediaRow
import one.rarebit.heyarr.mobile.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.mobile.ui.components.Panel
import one.rarebit.heyarr.mobile.ui.components.PrimaryButton
import one.rarebit.heyarr.mobile.ui.components.SecondaryButton
import one.rarebit.heyarr.mobile.ui.components.SectionHeader

class MissingState {
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
 * Wanting a single season lives on the series' detail (`POST /desired` scope=edition).
 */
@Composable
fun MissingScreen(session: AppSession, state: MissingState, onOpen: (Route) -> Unit, onWantTitle: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    fun load() {
        state.error = null
        scope.launch { session.io { session.api.missing() }.fold(onSuccess = { state.missing = it }, onFailure = { state.error = it.message }) }
        scope.launch { session.io { session.api.upgradeCandidates() }.fold(onSuccess = { state.upgrades = it }, onFailure = { state.error = it.message }) }
    }
    LaunchedEffect(Unit) { if (state.missing == null) load() }

    val list = (if (state.tab == 0) state.missing else state.upgrades)
    val sel = state.selected.filter { id -> list?.any { it.desiredItemId == id } == true }.toSet()

    fun bulk(label: String, action: (String) -> McpResult<*>) {
        if (sel.isEmpty()) return
        scope.launch {
            state.busy = true
            var ok = 0; var refused = 0
            for (id in sel) {
                session.io { action(id) }.onSuccess { r -> if (r is McpResult.Refused) { refused++; session.refused(r) } else ok++ }
            }
            state.busy = false
            session.toast(if (refused == 0) Toast.Kind.SUCCESS else Toast.Kind.INFO, "$label: $ok done${if (refused > 0) ", $refused refused" else ""}")
            session.refreshIndex(); load()
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = Tokens.screenPadding).padding(top = Tokens.s4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Missing & wanted", subtitle = "What should exist, and what could be better", trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButtonRound(Icons.Rounded.Refresh, "Refresh", ::load, size = 36.dp)
                PrimaryButton("Want by title", onWantTitle, icon = Icons.Rounded.Add, compact = true)
            }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Missing", state.tab == 0, { state.tab = 0 }, count = state.missing?.size)
            FilterChip("Could be better", state.tab == 1, { state.tab = 1 }, count = state.upgrades?.size)
        }
        if (list != null && list.isNotEmpty()) Panel("Bulk actions", trailing = {
            GhostButton(if (sel.size == list.size) "Select none" else "Select all", { state.selected = if (sel.size == list.size) emptySet() else list.map { it.desiredItemId }.toSet() })
        }) {
            Text("${sel.size} selected", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("Search now", { bulk("Search queued") { id -> session.api.searchReleases(id) } }, icon = Icons.Rounded.Search, compact = true, enabled = sel.isNotEmpty() && !state.busy)
                SecondaryButton("Monitor on", { bulk("Monitoring on") { id -> session.api.monitor(id, true) } }, compact = true, enabled = sel.isNotEmpty() && !state.busy)
                SecondaryButton("Monitor off", { bulk("Monitoring off") { id -> session.api.monitor(id, false) } }, compact = true, enabled = sel.isNotEmpty() && !state.busy)
            }
        }
        when {
            state.error != null && list == null -> ErrorState("Couldn't load wants", state.error, ::load)
            list == null -> MediaRowSkeleton(6)
            list.isEmpty() -> EmptyState(if (state.tab == 0) "Nothing is missing" else "Nothing to upgrade", detail = if (state.tab == 0) "Every want is satisfied. Want something new by title, or from Search." else "No satisfied, monitored want has room to improve under its profile.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.desiredItemId }) { w ->
                    val checked = w.desiredItemId in sel
                    MediaRow(
                        w.title, MediaType.UNKNOWN, onOpen = { w.workId?.let { onOpen(detailRoute(it, MediaType.UNKNOWN, w.title, from = "Missing")) } },
                        subtitle = listOfNotNull(w.qualityProfile?.let { "profile $it" }, w.reason).joinToString("  ·  "),
                        meta = listOf(w.state.lowercase().replace('_', ' '), if (w.monitor) "monitored" else "not monitored"),
                        status = LibraryStatus.ofState(w.state), selected = checked,
                        trailing = {
                            IconButtonRound(if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, if (checked) "Deselect ${w.title}" else "Select ${w.title}", {
                                state.selected = if (checked) state.selected - w.desiredItemId else state.selected + w.desiredItemId
                            }, size = 36.dp)
                        },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
