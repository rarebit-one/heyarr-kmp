package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.Cell
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.ReasonList
import one.rarebit.heyarr.ui.components.RejectedBy
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Section
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.TableColumn
import one.rarebit.heyarr.ui.components.verdictColor
import one.rarebit.heyarr.ui.theme.LocalMediaTheme

// The lower sections of the Curate tab ([CurateTab]): indexer candidates, health, variants,
// identifiers and the raw file list. Each takes the tab's coroutine scope where it acts, so
// an in-flight call lives exactly as long as the tab does.

/** Curate → 3. the indexer candidates of every want, each with Acquire (the rules behind a tap). */
@Composable
internal fun CandidatesSection(
    session: AppSession,
    wants: List<DesiredItem>,
    state: DetailState,
    scope: CoroutineScope,
    reload: () -> Unit,
) {
    val cands = wants.flatMap { w -> state.candidates[w.id].orEmpty().map { w to it } }
    val acquire: (DesiredItem, Candidate) -> Unit = { w, cand ->
        scope.launch {
            state.busy = cand.candidateId
            session.io { session.api.acquire(w.id, cand.candidateId) }.onSuccess { res ->
                when (res) {
                    is McpResult.Ok -> {
                        session.toast(Toast.Kind.SUCCESS, "Acquiring", cand.title)
                        session.refreshIndex()
                        reload()
                    }

                    is McpResult.Refused -> session.refused(res)
                }
            }
            state.busy = null
        }
    }
    val subtitle =
        if (wants.isEmpty()) {
            "Want it first — candidates belong to a want"
        } else {
            "${cands.size} from the last search"
        }
    Section("Indexer candidates", subtitle = subtitle, trailing = {
        if (wants.isNotEmpty()) SearchNowButton(session, wants.first(), scope)
    }) {
        DataTable(
            columns = listOf(
                TableColumn("Release", width = 260.dp),
                TableColumn("Provider", width = 100.dp),
                TableColumn("Score", width = 60.dp, alignEnd = true),
                TableColumn("Verdict", width = 90.dp),
                TableColumn("", width = 120.dp, alignEnd = true),
            ),
            rowCount = cands.size,
            emptyText = if (wants.isEmpty()) {
                "No want, no candidates."
            } else {
                "The last search found nothing${wants.firstOrNull()?.detail?.let {
                    " — $it"
                } ?: ""}."
            },
            minWidth = 780.dp,
            detailLabel = { r -> cands[r].second.title },
            detail = { r ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RejectedBy(cands[r].second.rejectedBy)
                    ReasonList(cands[r].second.reasons)
                }
            },
        ) { r, c ->
            val (w, cand) = cands[r]
            CandidateCell(cand, c, enabled = state.busy == null) { acquire(w, cand) }
        }
    }
}

/** "Search now": ask the indexers again for [want]; the job shows under Library → Downloads. */
@Composable
private fun SearchNowButton(session: AppSession, want: DesiredItem, scope: CoroutineScope) {
    SecondaryButton("Search now", {
        scope.launch {
            session.io { session.api.searchReleases(want.id) }.onSuccess { r ->
                when (r) {
                    is McpResult.Ok -> session.toast(
                        Toast.Kind.INFO,
                        "Search queued",
                        "Indexers answer within a minute; the Downloads tab under Library shows the job.",
                    )

                    is McpResult.Refused -> session.refused(r)
                }
            }
        }
    }, icon = Icons.Rounded.Search, compact = true)
}

/** One cell of the candidates table: column [c] of [cand]; the last column is its Acquire button. */
@Composable
private fun CandidateCell(cand: Candidate, c: Int, enabled: Boolean, onAcquire: () -> Unit) {
    when (c) {
        0 -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cell(cand.title)
            if (cand.selected) {
                Text(
                    "selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMediaTheme.current.accentGradientEnd,
                )
            }
        }

        1 -> Cell(cand.provider ?: "", muted = true, mono = true)

        2 -> Cell(cand.score.toString(), muted = true, mono = true)

        3 -> Text(
            if (cand.accepted) "accepted" else "rejected",
            style = MaterialTheme.typography.labelMedium,
            color = verdictColor(if (cand.accepted) "pass" else "fail"),
        )

        4 -> PrimaryButton("Acquire", onAcquire, icon = Icons.Rounded.Download, compact = true, enabled = enabled)
    }
}

/** Curate → 5. where the primary blob's copies stand, and a re-hash on demand. */
@Composable
internal fun HealthSection(session: AppSession, hash: String?, state: DetailState, scope: CoroutineScope) {
    Section(
        "Health",
        subtitle = hash?.let {
            "primary blob ${it.take(20)}…"
        } ?: "no held bytes to check",
        trailing = {
            if (hash != null) {
                SecondaryButton("Verify bytes", {
                    scope.launch {
                        session.io { session.api.verifyBlob(hash) }.onSuccess { r ->
                            when (r) {
                                is McpResult.Ok -> session.toast(
                                    Toast.Kind.INFO,
                                    "Verification queued",
                                    "Re-hashing runs as a job; see Library → Downloads.",
                                )

                                is McpResult.Refused -> session.refused(r)
                            }
                        }
                    }
                }, icon = Icons.Rounded.Verified, compact = true)
            }
        },
    ) {
        when (val r = state.replicas) {
            null -> if (hash !=
                null
            ) {
                Skeleton(Modifier.fillMaxWidth().height(40.dp))
            } else {
                Text("Nothing to check.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            }

            is McpResult.Refused -> Notice("get_replica_status: ${r.message}", tone = Tokens.danger)

            is McpResult.Ok -> ReplicaTable(r.value)
        }
    }
}

/** The replica report: each peer's copy and whether its bytes were verified. */
@Composable
private fun ReplicaTable(replicas: List<Replica>) {
    DataTable(
        columns = listOf(
            TableColumn("Peer", 1f),
            TableColumn("Copy", width = 110.dp),
            TableColumn("Verified", width = 80.dp),
        ),
        rowCount = replicas.size,
        emptyText = "No replica report — on a single-node fabric there is nowhere for bytes to converge to.",
    ) {
            i,
            c,
        ->
        val rep = replicas[i]
        when (c) {
            0 -> Cell(rep.peer)

            1 -> Cell(rep.state, mono = true)

            2 -> Text(
                if (rep.verified) "yes" else "no",
                style = MaterialTheme.typography.labelMedium,
                color = verdictColor(if (rep.verified) "pass" else "undetermined"),
            )
        }
    }
}

/** Curate → 7. the works the scanner minted for this title's download folders, each one tap away. */
@Composable
internal fun VariantsSection(work: Work, variants: List<Work>, onOpen: (Route) -> Unit) {
    Section(
        "Also catalogued as",
        subtitle = "Works the scanner minted for this title's download folders (heyarr-core#470) — hidden from listings, folded here",
    ) {
        DataTable(
            columns = listOf(
                TableColumn("Work", 2f),
                TableColumn("Season", width = 70.dp),
                TableColumn("", width = 80.dp, alignEnd = true),
            ),
            rowCount = variants.size,
        ) {
                i,
                c,
            ->
            val v = variants[i]
            when (c) {
                0 -> Cell(v.title)

                1 -> Cell(Variants.seasonOf(v)?.let { "S$it" } ?: "", mono = true, muted = true)

                2 -> GhostButton("Open", {
                    onOpen(detailRoute(v.id, MediaType.from(v.kind), v.title, from = work.title, curate = true))
                })
            }
        }
    }
}

/** Curate → 8. every identifier the node and the public sources hold for this work. */
@Composable
internal fun IdentifiersSection(work: Work, type: MediaType, state: DetailState) {
    Section("Identifiers", initiallyOpen = false) {
        val rows =
            listOf("work id" to work.id, "work key" to (work.workKey ?: "—"), "type" to type.label) +
                work.externalIds.map { it.key to it.value } +
                state.externalIds.map { it.source to it.value }
        DataTable(
            columns = listOf(TableColumn("Key", width = 110.dp), TableColumn("Value", 1f)),
            rowCount = rows.size,
        ) {
                i,
                c,
            ->
            val (k, v) = rows[i]
            when (c) {
                0 -> Cell(k, muted = true, mono = true)
                1 -> Cell(v, mono = true)
            }
        }
    }
}

/** Curate → 9. the raw file list as scanned, missing files flagged. */
@Composable
internal fun AllFilesSection(assets: List<WorkAsset>) {
    Section("All files", subtitle = "${assets.size} scanned", initiallyOpen = false) {
        DataTable(
            columns = listOf(
                TableColumn("File", width = 260.dp),
                TableColumn("Role", width = 80.dp),
                TableColumn("Type", width = 120.dp),
                TableColumn("Size", width = 80.dp, alignEnd = true),
                TableColumn("", width = 80.dp),
            ),
            rowCount = assets.size,
            emptyText = "No files scanned.",
            minWidth = 660.dp,
        ) {
                i,
                c,
            ->
            val t = assets[i]
            when (c) {
                0 -> Cell(
                    t.filename ?: t.id,
                    color = if (t.isPlayable ||
                        t.role == "artwork"
                    ) {
                        Tokens.textPrimary
                    } else {
                        Tokens.textDisabled
                    },
                )

                1 -> Cell(t.role ?: "primary", mono = true, muted = true)

                2 -> Cell(t.mime ?: "", mono = true, muted = true)

                3 -> Cell(t.sizeBytes?.let { WorkAsset.formatBytes(it) } ?: "", muted = true)

                4 -> if (t.missingSince !=
                    null
                ) {
                    Text("missing", style = MaterialTheme.typography.labelSmall, color = Tokens.danger)
                } else {
                    Cell("")
                }
            }
        }
    }
}
