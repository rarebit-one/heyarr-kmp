package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.mcp.AssetVerdict
import one.rarebit.heyarr.core.mcp.Explanation
import one.rarebit.heyarr.core.mcp.ReleaseAttributes
import one.rarebit.heyarr.core.mcp.ReleaseToExplain
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.Season
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.music.isPrimaryRole
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.Cell
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.Field
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

// The top of the Curate tab ([CurateTab]): each want and where it stands, and every held file
// with the verdict a want's profile gave it.

/** Curate → 1. what each want is measured against and where it stands, with its monitor toggle. */
@Composable
internal fun WantsSection(
    session: AppSession,
    wants: List<DesiredItem>,
    state: DetailState,
    scope: CoroutineScope,
    reload: () -> Unit,
) {
    val subtitle =
        if (wants.isEmpty()) {
            "Not wanted — nothing measures this work"
        } else {
            "${wants.size} want${if (wants.size == 1) "" else "s"} on this work"
        }
    Section("Wants & status", subtitle = subtitle, trailing = {
        GhostButton("Refresh", reload)
    }) {
        DataTable(
            columns = listOf(
                TableColumn("Scope", width = 70.dp),
                TableColumn("Profile", width = 110.dp),
                TableColumn("State", width = 110.dp),
                TableColumn("Content", width = 110.dp),
                TableColumn("Placement", width = 140.dp),
                TableColumn("Upgrade", width = 220.dp),
                TableColumn("Monitor", width = 120.dp, alignEnd = true),
            ),
            rowCount = wants.size,
            emptyText = "Not wanted. Want it (or a season) to see every rule heyarr would apply.",
            minWidth = 900.dp,
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

/** One cell of the wants table: column [c] of want [w], read against its satisfaction [sat] when known. */
@Composable
private fun WantCell(session: AppSession, w: DesiredItem, sat: Satisfaction?, c: Int, scope: CoroutineScope) {
    when (c) {
        0 -> Cell(if (w.id.startsWith("pending:")) "sending…" else w.scope, muted = true)

        1 -> Cell(
            session.profiles.firstOrNull {
                it.id == w.qualityProfileId
            }?.name ?: w.qualityProfileId ?: "?",
            mono = true,
        )

        2 -> StatusPill(LibraryStatus.ofState(w.state))

        3 -> Text(
            wantContentLabel(sat, w),
            style = MaterialTheme.typography.labelMedium,
            color = verdictColor(wantContentTone(sat, w)),
        )

        4 -> Cell(wantPlacementLabel(sat, w), muted = true)

        5 -> Cell(wantUpgradeLabel(sat, w), muted = true, maxLines = 2)

        6 -> FilterChip(if (w.monitor) "Monitoring" else "Off", w.monitor, { toggleMonitor(session, w, scope) })
    }
}

/** Whether the held content satisfies the want, as the node words it (a pending want shows its own field). */
private fun wantContentLabel(sat: Satisfaction?, w: DesiredItem): String =
    sat?.contentSatisfaction?.replace('_', ' ') ?: (w.content ?: "…")

private fun wantContentTone(sat: Satisfaction?, w: DesiredItem): String = if ((
        sat?.contentSatisfaction
            ?: w.content
        ) ==
    "satisfied"
) {
    "pass"
} else {
    "fail"
}

/** Where the copies stand; a single-node fabric cannot prove placement, and says so. */
private fun wantPlacementLabel(sat: Satisfaction?, w: DesiredItem): String = sat?.let {
    if (it.placementUnproven) "unproven (single node)" else it.placementSatisfaction
}
    ?: (w.placement ?: "…")

/** Whether a better copy is still sought, with the node's detail line verbatim. */
private fun wantUpgradeLabel(sat: Satisfaction?, w: DesiredItem): String = sat?.let {
    (if (it.upgradeEligible) "eligible" else it.upgradeStatus.replace('_', ' ')) +
        (it.upgradeDetail.takeIf { d -> d.isNotBlank() }?.let { d -> " — $d" } ?: "")
}
    ?: (w.detail ?: "")

/** Flip the want's monitor flag; a refusal is shown verbatim, success refreshes the index. */
private fun toggleMonitor(session: AppSession, w: DesiredItem, scope: CoroutineScope) {
    scope.launch {
        session.io {
            session.api.monitor(w.id, !w.monitor)
        }.onSuccess { res ->
            if (res is McpResult.Refused) session.refused(res) else session.refreshIndex()
        }
    }
}

/** Curate → 2. every held file with the verdict a want's profile gave it (the rules behind a tap). */
@Composable
internal fun HeldFilesSection(wants: List<DesiredItem>, state: DetailState) {
    val verdicts = wants.flatMap { w ->
        (state.satisfaction[w.id] as? McpResult.Ok)?.value?.assets.orEmpty()
    }.associateBy { it.assetId }
    val held = state.assets.orEmpty().filter { it.isPrimaryRole && it.blobHash != null }
    Section(
        "Held files",
        subtitle = "${held.size} playable file${if (held.size == 1) "" else "s"} · " +
            "${verdicts.size} judged against a profile",
    ) {
        DataTable(
            columns = listOf(
                TableColumn("File", width = 260.dp),
                TableColumn("Size", width = 80.dp, alignEnd = true),
                TableColumn("Verdict", width = 100.dp),
                TableColumn("Score", width = 60.dp, alignEnd = true),
                TableColumn("Rejected by", width = 220.dp),
            ),
            rowCount = held.size,
            emptyText = "Nothing held for this work.",
            minWidth = 780.dp,
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

/** One cell of the held-files table: column [c] of file [t] and its verdict [v], if any want measured it. */
@Composable
private fun HeldFileCell(t: WorkAsset, v: AssetVerdict?, c: Int) {
    when (c) {
        0 -> Cell(t.filename ?: t.id)

        1 -> Cell(t.sizeBytes?.let { WorkAsset.formatBytes(it) } ?: "", muted = true)

        2 -> HeldVerdict.of(v).let {
            Text(it.label, style = MaterialTheme.typography.labelMedium, color = verdictColor(it.tone))
        }

        3 -> Cell(v?.score?.toString() ?: "", muted = true, mono = true)

        4 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (x in v?.rejectedBy.orEmpty().take(2)) RuleCode(x.rule, tone = Tokens.danger)
            if ((v?.rejectedBy?.size ?: 0) > 2) Cell("+${v!!.rejectedBy.size - 2}", muted = true)
        }
    }
}

/** A held file's verdict: unmeasured when no want measures it, else what the profile said. */
private enum class HeldVerdict(val label: String, val tone: String) {
    UNMEASURED("unmeasured", ""),
    ACCEPTED("accepted", "pass"),
    REJECTED("rejected", "fail"),
    ;

    companion object {
        fun of(v: AssetVerdict?): HeldVerdict = when {
            v == null -> UNMEASURED
            v.accepted -> ACCEPTED
            else -> REJECTED
        }
    }
}
