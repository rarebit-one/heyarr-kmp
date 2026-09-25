package one.rarebit.heyarr.desktop.ui.screens

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
import one.rarebit.heyarr.core.mcp.AssetVerdict
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.library.PrimaryAsset
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.music.Track
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.ui.components.Cell
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.ReasonList
import one.rarebit.heyarr.ui.components.RejectedBy
import one.rarebit.heyarr.ui.components.RuleCode
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Section
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.components.TableColumn
import one.rarebit.heyarr.ui.components.verdictColor
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens

/** 3. Indexer candidates from each want's last search, with Acquire. */
@Composable
internal fun CandidatesSection(
    session: AppSession,
    wants: List<DesiredItem>,
    state: DetailState,
    scope: CoroutineScope,
    reload: () -> Unit,
) {
    val cands = wants.flatMap { w -> state.candidates[w.id].orEmpty().map { w to it } }
    val acquire: (DesiredItem, Candidate) -> Unit = acquire@{ w, cand ->
        val a = session.api ?: return@acquire
        scope.launch {
            state.busy = cand.candidateId
            session.io { a.acquire(w.id, cand.candidateId) }.onSuccess { res ->
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
    Section("Indexer candidates", subtitle = if (wants.isEmpty()) "Want it first — candidates belong to a want" else "${cands.size} from the last search", trailing = {
        if (wants.isNotEmpty()) SearchIndexersButton(session, wants, scope)
    }) {
        DataTable(
            columns = listOf(
                TableColumn("Release", 3f),
                TableColumn("Provider", width = 110.dp),
                TableColumn("Score", width = 60.dp, alignEnd = true),
                TableColumn("Verdict", width = 100.dp),
                TableColumn("", width = 110.dp, alignEnd = true),
            ),
            rowCount = cands.size,
            emptyText = if (wants.isEmpty()) {
                "No want, no candidates."
            } else {
                "The last search found nothing${wants.firstOrNull()?.detail?.let {
                    " — $it"
                } ?: ""}."
            },
            detailLabel = { r -> cands[r].second.title },
            detail = { r ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RejectedBy(cands[r].second.rejectedBy)
                    ReasonList(cands[r].second.reasons)
                }
            },
        ) { r, c ->
            val (w, cand) = cands[r]
            CandidateCell(cand, c, acquireEnabled = state.busy == null) { acquire(w, cand) }
        }
    }
}

@Composable
private fun SearchIndexersButton(session: AppSession, wants: List<DesiredItem>, scope: CoroutineScope) {
    SecondaryButton("Search indexers now", {
        val a = session.api ?: return@SecondaryButton
        scope.launch {
            session.io { a.searchReleases(wants.first().id) }.onSuccess { r ->
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

@Composable
private fun CandidateCell(cand: Candidate, c: Int, acquireEnabled: Boolean, onAcquire: () -> Unit) {
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

        4 -> PrimaryButton(
            "Acquire",
            onAcquire,
            icon = Icons.Rounded.Download,
            compact = true,
            enabled = acquireEnabled,
        )
    }
}

/** 5. Health: where the primary blob's copies stand, and a re-hash on demand. */
@Composable
internal fun HealthSection(session: AppSession, detail: WorkDetail, state: DetailState, scope: CoroutineScope) {
    val hash = detail.primaryAsset?.blobHash
    Section(
        "Health",
        subtitle = hash?.let {
            "primary blob ${it.take(20)}…"
        } ?: "no held bytes to check",
        trailing = {
            if (hash != null) VerifyBytesButton(session, hash, scope)
        },
    ) {
        ReplicaTable(state.replicas, hash)
    }
}

@Composable
private fun VerifyBytesButton(session: AppSession, hash: String, scope: CoroutineScope) {
    SecondaryButton("Verify bytes now", {
        val a = session.api ?: return@SecondaryButton
        scope.launch {
            session.io { a.verifyBlob(hash) }.onSuccess { r ->
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

@Composable
private fun ReplicaTable(replicas: McpResult<List<Replica>>?, hash: String?) {
    when (val r = replicas) {
        null -> if (hash !=
            null
        ) {
            Skeleton(Modifier.fillMaxWidth().height(40.dp))
        } else {
            Text("Nothing to check.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }

        is McpResult.Refused -> Notice("get_replica_status: ${r.message}", tone = Tokens.danger)

        is McpResult.Ok -> DataTable(
            columns = listOf(
                TableColumn("Peer", 1f),
                TableColumn("Copy", width = 120.dp),
                TableColumn("Verified", width = 100.dp),
            ),
            rowCount = r.value.size,
            emptyText = "No replica report — on a single-node fabric there is nowhere for bytes to converge to.",
        ) {
                i,
                c,
            ->
            val rep = r.value[i]
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
}

/** 7. Also catalogued as: the works the scanner minted for the same title. */
@Composable
internal fun VariantsSection(state: DetailState) {
    Section(
        "Also catalogued as",
        subtitle = "Works the scanner minted for this title's download folders (heyarr-core#470) — hidden from listings, folded here",
    ) {
        DataTable(
            columns = listOf(
                TableColumn("Work", 2f),
                TableColumn("Season", width = 80.dp),
                TableColumn("", width = 90.dp, alignEnd = true),
            ),
            rowCount = state.variants.size,
        ) {
                i,
                c,
            ->
            val v = state.variants[i]
            when (c) {
                0 -> Cell(v.title)
                1 -> Cell(Variants.seasonOf(v)?.let { "S$it" } ?: "", mono = true, muted = true)
                2 -> GhostButton("Open", { state.openVariant(v) })
            }
        }
    }
}

/** 8. Details: the node's own identifiers, then the external ones. */
@Composable
internal fun IdentifiersSection(detail: WorkDetail, type: MediaType, state: DetailState) {
    Section("Identifiers", initiallyOpen = false) {
        DataTable(
            columns = listOf(TableColumn("Key", width = 140.dp), TableColumn("Value", 1f)),
            rowCount =
            3 + state.externalIds.size,
        ) { i, c ->
            val rows =
                listOf(
                    "work id" to detail.work.id,
                    "work key" to (detail.work.workKey ?: "—"),
                    "type" to type.label,
                ) +
                    state.externalIds.map { it.source to it.value }
            val (k, v) = rows[i]
            when (c) {
                0 -> Cell(k, muted = true, mono = true)
                1 -> Cell(v, mono = true)
            }
        }
    }
}

/** 9. Files: the raw scanned file list. */
@Composable
internal fun AllFilesSection(assets: List<Track>) {
    Section("All files", subtitle = "${assets.size} scanned", initiallyOpen = false) {
        DataTable(
            columns = listOf(
                TableColumn("File", 3f),
                TableColumn("Role", width = 90.dp),
                TableColumn("Type", width = 130.dp),
                TableColumn("Size", width = 80.dp, alignEnd = true),
                TableColumn("", width = 90.dp),
            ),
            rowCount = assets.size,
            emptyText = "No files scanned.",
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

                3 -> Cell(t.sizeBytes?.let { PrimaryAsset.formatBytes(it) } ?: "", muted = true)

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
