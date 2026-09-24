package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.library.PrimaryAsset
import one.rarebit.heyarr.desktop.library.Season
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.core.mcp.Explanation
import one.rarebit.heyarr.core.mcp.ReleaseAttributes
import one.rarebit.heyarr.core.mcp.ReleaseToExplain
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.ReasonList
import one.rarebit.heyarr.ui.components.RejectedBy
import one.rarebit.heyarr.ui.components.RuleCode
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.components.verdictColor
import one.rarebit.heyarr.ui.components.Cell
import one.rarebit.heyarr.ui.components.TableColumn
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.Section

/** Curate → captions and artwork: what is held per episode, and the honest limits of what the node can fetch. */
@Composable
private fun SidecarsPanel(session: AppSession, state: DetailState, seasons: List<Season>, wants: List<DesiredItem>) {
    val assets = state.assets.orEmpty()
    val subs = assets.filter { Series.isSubtitle(it) }
    val art = assets.filter { it.role == "artwork" }
    Panel("Captions & artwork") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ClosedCaption, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(16.dp))
            Text("${subs.size} caption file${if (subs.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
            if (seasons.isNotEmpty()) Text("· ${seasons.sumOf { s -> s.episodes.count { it.subtitles.isEmpty() && it.isPlayable } }} held episodes without captions", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(16.dp))
            Text("${art.size} artwork file${if (art.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
            if (seasons.isNotEmpty()) Text("· ${seasons.sumOf { s -> s.episodes.count { it.thumbnail == null } }} episodes without a thumbnail", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        Notice("heyarr's tool surface has no caption or artwork search yet: sidecars arrive with a release or a scan. Asking the indexers again (Releases) is the only fetch this node can queue.", tone = Tokens.slate)
    }
}

/** "Would this be accepted?" — describe a release, get every rule back. Absent fields stay absent so they read as undetermined. */
@Composable
private fun ExplainPanel(session: AppSession, wants: List<DesiredItem>) {
    val scope = rememberCoroutineScope()
    val profiles = session.profiles
    var profile by remember(profiles) { mutableStateOf(wants.firstOrNull()?.let { w -> profiles.firstOrNull { it.id == w.qualityProfileId }?.name } ?: profiles.firstOrNull()?.name ?: "") }
    var title by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var codec by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<McpResult<Explanation?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    Panel("Score a release") {
        Text("Describe a release and heyarr explains, rule by rule, whether the profile would accept it. Leave a field blank when you do not know — a blank reads as undetermined, a guess reads as a claim.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (p in profiles) FilterChip(p.name, profile == p.name, { profile = p.name }) }
        Field("Title", title) { title = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Resolution (480/720/1080/2160)", resolution, Modifier.weight(1f)) { resolution = it.filter { c -> c.isDigit() } }
            Field("Source (remux/bluray/web-dl/…)", source, Modifier.weight(1f)) { source = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Video codec", codec, Modifier.weight(1f)) { codec = it }
            Field("Size (bytes)", size, Modifier.weight(1f)) { size = it.filter { c -> c.isDigit() } }
        }
        PrimaryButton("Explain", {
            val a = session.api ?: return@PrimaryButton
            busy = true
            scope.launch {
                val rel = ReleaseToExplain("candidate", title.ifBlank { "untitled release" }, ReleaseAttributes(resolution = resolution.toIntOrNull(), source = source, videoCodec = codec, sizeBytes = size.toLongOrNull()))
                session.io { a.explain(profile, listOf(rel)) }.onSuccess { result = it }
                busy = false
            }
        }, icon = Icons.Rounded.Verified, compact = true, enabled = !busy && profile.isNotBlank())
        when (val r = result) {
            null -> {}
            is McpResult.Refused -> Notice("explain_release: ${r.message}", tone = Tokens.danger)
            is McpResult.Ok -> r.value?.ranked?.firstOrNull()?.let { ranked ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ranked.accepted) "Would be accepted" else "Would be rejected", style = MaterialTheme.typography.titleSmall, color = verdictColor(if (ranked.accepted) "pass" else "fail"))
                    Text("score ${ranked.score}${if (ranked.terminal) " · terminal" else ""}", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                }
                RejectedBy(ranked.rejectedBy)
                ReasonList(ranked.reasons)
            } ?: Text("No verdict returned.", color = Tokens.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, placeholder: String? = null, secret: Boolean = false, onChange: (String) -> Unit) {
    val accent = LocalMediaTheme.current.accent
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        Box(
            Modifier.fillMaxWidth().background(Tokens.surface2, RoundedCornerShape(Tokens.radiusInput))
                .border(if (focused) 2.dp else Tokens.hairline, if (focused) accent else Tokens.border, RoundedCornerShape(Tokens.radiusInput))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Tokens.textDisabled)
            BasicTextField(
                value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tokens.textPrimary), cursorBrush = SolidColor(accent),
                interactionSource = interaction, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
    }
}

/**
 * The curation surface as tables, one column, each section collapsible: what each want
 * is measured against and where it stands; every held file with the verdict the profile
 * gave it (rules behind a click); the indexer candidates with Acquire; a release scorer;
 * health; captions and artwork; the works the scanner minted for the same title; the
 * identifiers; the raw file list. Nothing here is paraphrased — rule codes, states and
 * the node's detail lines are shown as sent.
 */
@Composable
internal fun CurateTab(session: AppSession, detail: WorkDetail, type: MediaType, wants: List<DesiredItem>, state: DetailState, seasons: List<Season>, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    val assets = state.assets.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // 1. Status
        Section("Wants & status", subtitle = if (wants.isEmpty()) "Not wanted — nothing measures this work" else "${wants.size} want${if (wants.size == 1) "" else "s"} on this work", trailing = { GhostButton("Refresh", reload) }) {
            DataTable(
                columns = listOf(TableColumn("Scope", width = 90.dp), TableColumn("Profile", width = 120.dp), TableColumn("State", width = 120.dp), TableColumn("Content", width = 110.dp), TableColumn("Placement", 1.2f), TableColumn("Upgrade", 1.4f), TableColumn("Monitor", width = 120.dp, alignEnd = true)),
                rowCount = wants.size, emptyText = "Not wanted. Want it (or a season) to see every rule heyarr would apply.",
            ) { r, c ->
                val w = wants[r]
                val sat = (state.satisfaction[w.id] as? McpResult.Ok)?.value
                when (c) {
                    0 -> Cell(w.id.takeIf { it.startsWith("pending:") }?.let { "sending…" } ?: w.scope, muted = true)
                    1 -> Cell(session.profiles.firstOrNull { it.id == w.qualityProfileId }?.name ?: w.qualityProfileId ?: "?", mono = true)
                    2 -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    3 -> Text(sat?.contentSatisfaction?.replace('_', ' ') ?: (w.content ?: "…"), style = MaterialTheme.typography.labelMedium, color = verdictColor(if ((sat?.contentSatisfaction ?: w.content) == "satisfied") "pass" else "fail"))
                    4 -> Cell(sat?.let { if (it.placementUnproven) "unproven (single node)" else it.placementSatisfaction } ?: (w.placement ?: "…"), muted = true)
                    5 -> Cell(sat?.let { (if (it.upgradeEligible) "eligible" else it.upgradeStatus.replace('_', ' ')) + (it.upgradeDetail.takeIf { d -> d.isNotBlank() }?.let { d -> " — $d" } ?: "") } ?: (w.detail ?: ""), muted = true, maxLines = 2)
                    6 -> FilterChip(if (w.monitor) "Monitoring" else "Off", w.monitor, {
                        val a = session.api ?: return@FilterChip
                        scope.launch { session.io { a.monitor(w.id, !w.monitor) }.onSuccess { res -> if (res is McpResult.Refused) session.refused(res) else session.refreshIndex() } }
                    })
                }
            }
            for (w in wants) (state.satisfaction[w.id] as? McpResult.Refused)?.let { Notice("get_content_satisfaction: ${it.message}", tone = Tokens.danger) }
        }

        // 2. Held files with verdicts
        val verdicts = wants.flatMap { w -> (state.satisfaction[w.id] as? McpResult.Ok)?.value?.assets.orEmpty() }.associateBy { it.assetId }
        val held = assets.filter { it.isPrimaryRole && it.blobHash != null }
        Section("Held files", subtitle = "${held.size} playable file${if (held.size == 1) "" else "s"} · ${verdicts.size} judged against a profile") {
            DataTable(
                columns = listOf(TableColumn("File", 3f), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("Verdict", width = 110.dp), TableColumn("Score", width = 60.dp, alignEnd = true), TableColumn("Rejected by", 1.6f)),
                rowCount = held.size, emptyText = "Nothing held for this work.",
                detailLabel = { r -> held[r].filename ?: held[r].id },
                detail = { r -> verdicts[held[r].id]?.let { ReasonList(it.reasons) } ?: Text("No verdict — this file is not measured by any want.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) },
            ) { r, c ->
                val t = held[r]; val v = verdicts[t.id]
                when (c) {
                    0 -> Cell(t.filename ?: t.id)
                    1 -> Cell(t.sizeBytes?.let { PrimaryAsset.formatBytes(it) } ?: "", muted = true)
                    2 -> Text(when { v == null -> "unmeasured"; v.accepted -> "accepted"; else -> "rejected" }, style = MaterialTheme.typography.labelMedium, color = verdictColor(when { v == null -> ""; v.accepted -> "pass"; else -> "fail" }))
                    3 -> Cell(v?.score?.toString() ?: "", muted = true, mono = true)
                    4 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (x in v?.rejectedBy.orEmpty().take(2)) RuleCode(x.rule, tone = Tokens.danger); if ((v?.rejectedBy?.size ?: 0) > 2) Cell("+${v!!.rejectedBy.size - 2}", muted = true) }
                }
            }
        }

        // 3. Indexer candidates
        val cands = wants.flatMap { w -> state.candidates[w.id].orEmpty().map { w to it } }
        Section("Indexer candidates", subtitle = if (wants.isEmpty()) "Want it first — candidates belong to a want" else "${cands.size} from the last search", trailing = {
            if (wants.isNotEmpty()) SecondaryButton("Search indexers now", {
                val a = session.api ?: return@SecondaryButton
                scope.launch { session.io { a.searchReleases(wants.first().id) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued", "Indexers answer within a minute; the Downloads tab under Library shows the job."); is McpResult.Refused -> session.refused(r) } } }
            }, icon = Icons.Rounded.Search, compact = true)
        }) {
            DataTable(
                columns = listOf(TableColumn("Release", 3f), TableColumn("Provider", width = 110.dp), TableColumn("Score", width = 60.dp, alignEnd = true), TableColumn("Verdict", width = 100.dp), TableColumn("", width = 110.dp, alignEnd = true)),
                rowCount = cands.size, emptyText = if (wants.isEmpty()) "No want, no candidates." else "The last search found nothing${wants.firstOrNull()?.detail?.let { " — $it" } ?: ""}.",
                detailLabel = { r -> cands[r].second.title },
                detail = { r -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { RejectedBy(cands[r].second.rejectedBy); ReasonList(cands[r].second.reasons) } },
            ) { r, c ->
                val (w, cand) = cands[r]
                when (c) {
                    0 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Cell(cand.title); if (cand.selected) Text("selected", style = MaterialTheme.typography.labelSmall, color = LocalMediaTheme.current.accentGradientEnd) }
                    1 -> Cell(cand.provider ?: "", muted = true, mono = true)
                    2 -> Cell(cand.score.toString(), muted = true, mono = true)
                    3 -> Text(if (cand.accepted) "accepted" else "rejected", style = MaterialTheme.typography.labelMedium, color = verdictColor(if (cand.accepted) "pass" else "fail"))
                    4 -> PrimaryButton("Acquire", {
                        val a = session.api ?: return@PrimaryButton
                        scope.launch {
                            state.busy = cand.candidateId
                            session.io { a.acquire(w.id, cand.candidateId) }.onSuccess { res -> when (res) { is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Acquiring", cand.title); session.refreshIndex(); reload() }; is McpResult.Refused -> session.refused(res) } }
                            state.busy = null
                        }
                    }, icon = Icons.Rounded.Download, compact = true, enabled = state.busy == null)
                }
            }
        }

        // 4. Score a release
        Section("Score a release", subtitle = "Ask the profile about a release you are looking at", initiallyOpen = false) { ExplainPanel(session, wants) }

        // 5. Health
        val hash = detail.primaryAsset?.blobHash
        Section("Health", subtitle = hash?.let { "primary blob ${it.take(20)}…" } ?: "no held bytes to check", trailing = {
            if (hash != null) SecondaryButton("Verify bytes now", {
                val a = session.api ?: return@SecondaryButton
                scope.launch { session.io { a.verifyBlob(hash) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Verification queued", "Re-hashing runs as a job; see Library → Downloads."); is McpResult.Refused -> session.refused(r) } } }
            }, icon = Icons.Rounded.Verified, compact = true)
        }) {
            when (val r = state.replicas) {
                null -> if (hash != null) Skeleton(Modifier.fillMaxWidth().height(40.dp)) else Text("Nothing to check.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                is McpResult.Refused -> Notice("get_replica_status: ${r.message}", tone = Tokens.danger)
                is McpResult.Ok -> DataTable(columns = listOf(TableColumn("Peer", 1f), TableColumn("Copy", width = 120.dp), TableColumn("Verified", width = 100.dp)), rowCount = r.value.size, emptyText = "No replica report — on a single-node fabric there is nowhere for bytes to converge to.") { i, c ->
                    val rep = r.value[i]
                    when (c) { 0 -> Cell(rep.peer); 1 -> Cell(rep.state, mono = true); 2 -> Text(if (rep.verified) "yes" else "no", style = MaterialTheme.typography.labelMedium, color = verdictColor(if (rep.verified) "pass" else "undetermined")) }
                }
            }
        }

        // 6. Captions & artwork
        Section("Captions & artwork", initiallyOpen = false) { SidecarsPanel(session, state, seasons, wants) }

        // 7. Also catalogued as
        if (state.variants.isNotEmpty()) Section("Also catalogued as", subtitle = "Works the scanner minted for this title's download folders (heyarr-core#470) — hidden from listings, folded here") {
            DataTable(columns = listOf(TableColumn("Work", 2f), TableColumn("Season", width = 80.dp), TableColumn("", width = 90.dp, alignEnd = true)), rowCount = state.variants.size) { i, c ->
                val v = state.variants[i]
                when (c) { 0 -> Cell(v.title); 1 -> Cell(Variants.seasonOf(v)?.let { "S$it" } ?: "", mono = true, muted = true); 2 -> GhostButton("Open", { state.openVariant(v) }) }
            }
        }

        // 8. Details
        Section("Identifiers", initiallyOpen = false) {
            DataTable(columns = listOf(TableColumn("Key", width = 140.dp), TableColumn("Value", 1f)), rowCount = 3 + state.externalIds.size) { i, c ->
                val rows = listOf("work id" to detail.work.id, "work key" to (detail.work.workKey ?: "—"), "type" to type.label) + state.externalIds.map { it.source to it.value }
                val (k, v) = rows[i]
                when (c) { 0 -> Cell(k, muted = true, mono = true); 1 -> Cell(v, mono = true) }
            }
        }

        // 9. Files
        Section("All files", subtitle = "${assets.size} scanned", initiallyOpen = false) {
            DataTable(columns = listOf(TableColumn("File", 3f), TableColumn("Role", width = 90.dp), TableColumn("Type", width = 130.dp), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("", width = 90.dp)), rowCount = assets.size, emptyText = "No files scanned.") { i, c ->
                val t = assets[i]
                when (c) {
                    0 -> Cell(t.filename ?: t.id, color = if (t.isPlayable || t.role == "artwork") Tokens.textPrimary else Tokens.textDisabled)
                    1 -> Cell(t.role ?: "primary", mono = true, muted = true)
                    2 -> Cell(t.mime ?: "", mono = true, muted = true)
                    3 -> Cell(t.sizeBytes?.let { PrimaryAsset.formatBytes(it) } ?: "", muted = true)
                    4 -> if (t.missingSince != null) Text("missing", style = MaterialTheme.typography.labelSmall, color = Tokens.danger) else Cell("")
                }
            }
        }
    }
}
