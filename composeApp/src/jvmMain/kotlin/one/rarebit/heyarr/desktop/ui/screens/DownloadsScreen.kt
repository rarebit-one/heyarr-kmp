package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.heyarr.JobInfo
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.Cell
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Section
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.components.TableColumn
import one.rarebit.heyarr.ui.components.verdictColor
import one.rarebit.heyarr.ui.theme.Tokens

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
 * percentage — a transfer's progress lives in Transmission, which heyarr does not
 * relay yet. Refreshes every 10 s while open.
 */
@Composable
fun DownloadsScreen(session: AppSession, state: DownloadsState, onOpen: (Route) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    fun load() {
        val a = session.api ?: return
        state.error = null
        scope.launch {
            session.io { a.desired() }.fold(onSuccess = { state.desired = it }, onFailure = { state.error = it.message })
            session.io { a.jobs(40) }.onSuccess { state.jobs = it }
            val wanted = state.desired.orEmpty().mapNotNull { it.workId }.distinct().filter { it !in state.titles }
            if (wanted.isNotEmpty()) session.io { a.works() }.onSuccess { works -> state.titles = state.titles + works.associate { it.id to it.title } }
        }
    }
    LaunchedEffect(session.config) {
        while (isActive) {
            load()
            delay(10_000)
        }
    }

    val inFlight = state.desired?.filter { it.state != "FULLY_SATISFIED" && it.state != "AVAILABLE" }.orEmpty()
    val recentJobs = state.jobs.orEmpty().sortedByDescending { it.updatedAt ?: "" }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Notice("The node reports each want's acquisition state and phase, not a percentage — a transfer's byte count lives in the download client (Transmission) and heyarr does not relay it yet.", tone = Tokens.slate)
        Section("Acquiring", subtitle = "${inFlight.size} wants not yet satisfied", trailing = { GhostButton("Refresh", ::load, icon = Icons.Rounded.Refresh) }) {
            when {
                state.error != null && state.desired == null -> Notice("Couldn't load wants: ${state.error}", tone = Tokens.danger)

                state.desired == null -> Skeleton(Modifier.fillMaxWidth().height(80.dp))

                else -> DataTable(
                    columns = listOf(TableColumn("Want", 2.2f), TableColumn("Status", width = 110.dp), TableColumn("Phase", width = 110.dp), TableColumn("Client", width = 80.dp), TableColumn("Detail", 2.4f), TableColumn("", width = 130.dp, alignEnd = true)),
                    rowCount = inFlight.size,
                    emptyText = "Nothing in flight — every want is satisfied.",
                ) { r, c ->
                    val w = inFlight[r]
                    when (c) {
                        0 -> Cell(state.titles[w.workId] ?: w.workId ?: w.id)

                        1 -> StatusPill(LibraryStatus.ofState(w.state))

                        2 -> Cell(w.phase ?: "—", muted = true, mono = true)

                        3 -> Cell(if (w.state == "SELECTED") "handed off" else "—", muted = true)

                        4 -> Cell(w.detail ?: "", muted = true, maxLines = 2)

                        5 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val wid = w.workId
                            if (wid != null) GhostButton("Open", { onOpen(Route.Detail(wid, MediaType.UNKNOWN, state.titles[wid], from = "Downloads", curate = true)) })
                            SecondaryButton("Search", {
                                session.api?.let { a ->
                                    scope.launch {
                                        session.io { a.searchReleases(w.id) }.onSuccess { res ->
                                            when (res) {
                                                is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued")
                                                is McpResult.Refused -> session.refused(res)
                                            }
                                        }
                                    }
                                }
                            }, icon = Icons.Rounded.Search, compact = true)
                        }
                    }
                }
            }
        }
        Section("Job queue", subtitle = "The node's recent work — searches, ingests, probes, scans", initiallyOpen = true) {
            when (val jobs = state.jobs) {
                null -> Skeleton(Modifier.fillMaxWidth().height(80.dp))

                else -> DataTable(
                    columns = listOf(TableColumn("Job", 1.4f), TableColumn("State", width = 100.dp), TableColumn("Attempts", width = 80.dp, alignEnd = true), TableColumn("Updated", width = 150.dp), TableColumn("Last error", 2f)),
                    rowCount = minOf(recentJobs.size, 25),
                    emptyText = "The queue is empty.",
                ) { r, c ->
                    val j = recentJobs[r]
                    when (c) {
                        0 -> Cell(j.type.replace('_', ' '), mono = true)

                        1 -> Text(
                            j.state,
                            style = MaterialTheme.typography.labelMedium,
                            color = verdictColor(
                                when (j.state) {
                                    "succeeded" -> "pass"
                                    "dead", "failed" -> "fail"
                                    "running", "leased" -> "undetermined"
                                    else -> ""
                                },
                            ),
                        )

                        2 -> Cell("${j.attempts}", muted = true)

                        3 -> Cell(j.updatedAt?.replace('T', ' ')?.take(16) ?: "", muted = true, mono = true)

                        4 -> Cell(j.lastError ?: "", muted = j.lastError == null, color = Tokens.danger, maxLines = 2)
                    }
                }
            }
        }
    }
}
