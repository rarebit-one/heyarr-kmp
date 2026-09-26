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

// The sections of the Curate tab ([CurateTab]), top to bottom. Each is one collapsible
// table; the coroutine scope is the tab's, so an action outlives a collapsed section.

/** 1. Status: what each want is measured against and where it stands. */
@Composable
internal fun WantsSection(
    session: AppSession,
    wants: List<DesiredItem>,
    state: DetailState,
    scope: CoroutineScope,
    reload: () -> Unit,
) {
    Section("Overview", subtitle = if (wants.isEmpty()) "Not wanted — nothing measures this work" else "${wants.size} want${if (wants.size == 1) "" else "s"} on this work", trailing = {
        GhostButton("Refresh", reload)
    }) {
        DataTable(
            columns = listOf(
                TableColumn("Scope", width = 90.dp),
                TableColumn("Profile", width = 120.dp),
                TableColumn("State", width = 120.dp),
                TableColumn("Content", width = 110.dp),
                TableColumn("Placement", 1.2f),
                TableColumn("Upgrade", 1.4f),
                TableColumn("Monitor", width = 120.dp, alignEnd = true),
            ),
            rowCount = wants.size,
            emptyText = "Not wanted. Want it (or a season) to see every rule heyarr would apply.",
        ) { r, c ->
            val w = wants[r]
            WantCell(session, w, (state.satisfaction[w.id] as? McpResult.Ok)?.value, c, scope)
        }
        for (w in wants) {
            (state.satisfaction[w.id] as? McpResult.Refused)?.let {
                Notice("get_content_satisfaction: ${it.message}", tone = Tokens.danger)
            }
        }
    }
}

/** One cell of the wants table: column [c] of want [w], with its satisfaction [sat] once it has loaded. */
@Composable
private fun WantCell(session: AppSession, w: DesiredItem, sat: Satisfaction?, c: Int, scope: CoroutineScope) {
    when (c) {
        0 -> Cell(w.id.takeIf { it.startsWith("pending:") }?.let { "sending…" } ?: w.scope, muted = true)

        1 -> Cell(
            session.profiles.firstOrNull {
                it.id == w.qualityProfileId
            }?.name ?: w.qualityProfileId ?: "?",
            mono = true,
        )

        2 -> WantStateCell(w)

        3 -> WantContentCell(w, sat)

        4 -> Cell(placementText(w, sat), muted = true)

        5 -> Cell(upgradeText(w, sat), muted = true, maxLines = 2)

        6 -> MonitorChip(session, w, scope)
    }
}

/** Where a want's copies stand; a single-node fabric cannot prove placement, and says so. */
private fun placementText(w: DesiredItem, sat: Satisfaction?): String = sat?.let {
    if (it.placementUnproven) "unproven (single node)" else it.placementSatisfaction
}
    ?: (w.placement ?: "…")

/** Whether a better release would replace the held one, with the node's detail line as sent. */
private fun upgradeText(w: DesiredItem, sat: Satisfaction?): String = sat?.let {
    (if (it.upgradeEligible) "eligible" else it.upgradeStatus.replace('_', ' ')) +
        (it.upgradeDetail.takeIf { d -> d.isNotBlank() }?.let { d -> " — $d" } ?: "")
}
    ?: (w.detail ?: "")

@Composable
private fun MonitorChip(session: AppSession, w: DesiredItem, scope: CoroutineScope) {
    FilterChip(if (w.monitor) "Monitoring" else "Off", w.monitor, {
        val a = session.api ?: return@FilterChip
        scope.launch {
            session.io {
                a.monitor(w.id, !w.monitor)
            }.onSuccess { res ->
                if (res is McpResult.Refused) session.refused(res) else session.refreshIndex()
            }
        }
    })
}

@Composable
private fun WantStateCell(w: DesiredItem) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatusPill(LibraryStatus.ofState(w.state))
        // While a transfer is in flight, how far it has got.
        w.downloadProgress?.let { p ->
            Text(
                "${(p * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
    }
}

@Composable
private fun WantContentCell(w: DesiredItem, sat: Satisfaction?) {
    Text(
        sat?.contentSatisfaction?.replace('_', ' ') ?: (w.content ?: "…"),
        style = MaterialTheme.typography.labelMedium,
        color = verdictColor(
            if ((
                    sat?.contentSatisfaction
                        ?: w.content
                    ) ==
                "satisfied"
            ) {
                "pass"
            } else {
                "fail"
            },
        ),
    )
}

/** 2. Held files with the verdict the profile gave each (rules behind a click). */
@Composable
internal fun HeldFilesSection(wants: List<DesiredItem>, state: DetailState) {
    val assets = state.assets.orEmpty()
    val verdicts = wants.flatMap { w ->
        (state.satisfaction[w.id] as? McpResult.Ok)?.value?.assets.orEmpty()
    }.associateBy { it.assetId }
    val held = assets.filter { it.isPrimaryRole && it.blobHash != null }
    Section(
        "Held files",
        subtitle = "${held.size} playable file${if (held.size == 1) "" else "s"} · ${verdicts.size} judged against a profile",
    ) {
        DataTable(
            columns = listOf(
                TableColumn("File", 3f),
                TableColumn("Size", width = 80.dp, alignEnd = true),
                TableColumn("Verdict", width = 110.dp),
                TableColumn("Score", width = 60.dp, alignEnd = true),
                TableColumn("Rejected by", 1.6f),
            ),
            rowCount = held.size,
            emptyText = "Nothing held for this work.",
            detailLabel = { r -> held[r].filename ?: held[r].id },
            detail = { r ->
                verdicts[held[r].id]?.let { ReasonList(it.reasons) }
                    ?: Text(
                        "No verdict — this file is not measured by any want.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.textMuted,
                    )
            },
        ) { r, c ->
            val t = held[r]
            HeldFileCell(t, verdicts[t.id], c)
        }
    }
}

@Composable
private fun HeldFileCell(t: Track, v: AssetVerdict?, c: Int) {
    when (c) {
        0 -> Cell(t.filename ?: t.id)

        1 -> Cell(t.sizeBytes?.let { PrimaryAsset.formatBytes(it) } ?: "", muted = true)

        2 -> HeldVerdictCell(v)

        3 -> Cell(v?.score?.toString() ?: "", muted = true, mono = true)

        4 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (x in v?.rejectedBy.orEmpty().take(2)) RuleCode(x.rule, tone = Tokens.danger)
            if ((v?.rejectedBy?.size ?: 0) > 2) Cell("+${v!!.rejectedBy.size - 2}", muted = true)
        }
    }
}

/** The profile's verdict on one held file; a file no want measures says so rather than passing. */
@Composable
private fun HeldVerdictCell(v: AssetVerdict?) {
    Text(
        when {
            v == null -> "unmeasured"
            v.accepted -> "accepted"
            else -> "rejected"
        },
        style = MaterialTheme.typography.labelMedium,
        color = verdictColor(
            when {
                v == null -> ""
                v.accepted -> "pass"
                else -> "fail"
            },
        ),
    )
}
