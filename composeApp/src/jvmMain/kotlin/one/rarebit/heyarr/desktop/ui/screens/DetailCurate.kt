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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.heyarr.SidecarAction
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.mcp.Explanation
import one.rarebit.heyarr.core.mcp.ReleaseAttributes
import one.rarebit.heyarr.core.mcp.ReleaseToExplain
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.library.PrimaryAsset
import one.rarebit.heyarr.desktop.library.Season
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.ui.components.Cell
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.ReasonList
import one.rarebit.heyarr.ui.components.RejectedBy
import one.rarebit.heyarr.ui.components.RuleCode
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Section
import one.rarebit.heyarr.ui.components.SidecarActions
import one.rarebit.heyarr.ui.components.SidecarOptions
import one.rarebit.heyarr.ui.components.SidecarOutcome
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.components.TableColumn
import one.rarebit.heyarr.ui.components.verdictColor
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens

/** Manage → captions and artwork: what is held per episode, and the honest limits of what the node can fetch. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun SidecarsPanel(session: AppSession, state: DetailState, seasons: List<Season>, type: MediaType) {
    val scope = rememberCoroutineScope()
    val runner = remember(state.workId) { SidecarRunner() }
    val assets = state.assets.orEmpty()
    val subs = assets.filter { Series.isSubtitle(it) }
    val art = assets.filter { it.role == "artwork" }
    Panel("Captions & artwork") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                HeyarrIcons.Captions,
                contentDescription = null,
                tint = Tokens.textMuted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "${subs.size} caption file${if (subs.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textPrimary,
            )
            if (seasons.isNotEmpty()) {
                Text(
                    "· ${seasons.sumOf { s ->
                        s.episodes.count { it.subtitles.isEmpty() && it.isPlayable }
                    }} held episodes without captions",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textMuted,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                HeyarrIcons.Image,
                contentDescription = null,
                tint = Tokens.textMuted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "${art.size} artwork file${if (art.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textPrimary,
            )
            if (seasons.isNotEmpty()) {
                Text(
                    "· ${seasons.sumOf { s ->
                        s.episodes.count { it.thumbnail == null }
                    }} episodes without a thumbnail",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textMuted,
                )
            }
        }
        SidecarActions(
            SidecarOptions.of(type, session.mode),
            busy = runner.busy,
            outcome = runner.outcome,
            onAction = { action, language -> runner.run(session, scope, state.workId, action, language) },
        )
    }
}

/** "Would this be accepted?" — describe a release, get every rule back. Absent fields stay absent so they read as undetermined. */
@Composable
private fun ExplainPanel(session: AppSession, wants: List<DesiredItem>) {
    val scope = rememberCoroutineScope()
    val profiles = session.profiles
    var profile by remember(profiles) {
        mutableStateOf(
            wants.firstOrNull()?.let { w -> profiles.firstOrNull { it.id == w.qualityProfileId }?.name }
                ?: profiles.firstOrNull()?.name
                ?: "",
        )
    }
    var title by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var codec by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<McpResult<Explanation?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Describe a release and heyarr explains, rule by rule, whether the profile would accept it. Leave a field blank when you do not know — a blank reads as undetermined, a guess reads as a claim.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        ProfileChips(profiles.map { it.name }, profile) { profile = it }
        Field("Title", title) { title = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Resolution (480/720/1080/2160)", resolution, Modifier.weight(1f)) {
                resolution =
                    it.filter { c -> c.isDigit() }
            }
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
                val rel = releaseToExplain(title, resolution, source, codec, size)
                session.io { a.explain(profile, listOf(rel)) }.onSuccess { result = it }
                busy = false
            }
        }, icon = HeyarrIcons.Verified, compact = true, enabled = !busy && profile.isNotBlank())
        ExplainResult(result)
    }
}

/** The release as described; a blank or unparsable field stays absent so it reads as undetermined. */
private fun releaseToExplain(
    title: String,
    resolution: String,
    source: String,
    codec: String,
    size: String,
): ReleaseToExplain = ReleaseToExplain(
    "candidate",
    title.ifBlank {
        "untitled release"
    },
    ReleaseAttributes(
        resolution = resolution.toIntOrNull(),
        source = source,
        videoCodec = codec,
        sizeBytes = size.toLongOrNull(),
    ),
)

/** The quality profiles to score against, one chip each; [selected] is the chosen profile's name. */
@Composable
private fun ProfileChips(names: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (name in names) {
            FilterChip(
                name,
                selected == name,
                { onPick(name) },
            )
        }
    }
}

/** What explain_release answered: the verdict, its score, and every rule behind it. */
@Composable
private fun ExplainResult(result: McpResult<Explanation?>?) {
    when (val r = result) {
        null -> {}

        is McpResult.Refused -> Notice("explain_release: ${r.message}", tone = Tokens.danger)

        is McpResult.Ok -> r.value?.ranked?.firstOrNull()?.let { ranked ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (ranked.accepted) "Would be accepted" else "Would be rejected",
                    style = MaterialTheme.typography.titleSmall,
                    color = verdictColor(if (ranked.accepted) "pass" else "fail"),
                )
                Text(
                    "score ${ranked.score}${if (ranked.terminal) " · terminal" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.textMuted,
                )
            }
            RejectedBy(ranked.rejectedBy)
            ReasonList(ranked.reasons)
        } ?: Text("No verdict returned.", color = Tokens.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    val accent = LocalMediaTheme.current.accent
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        Box(
            Modifier.fillMaxWidth().background(Tokens.surface2, RoundedCornerShape(Tokens.radiusInput))
                .border(
                    if (focused) 2.dp else Tokens.hairline,
                    if (focused) accent else Tokens.border,
                    RoundedCornerShape(Tokens.radiusInput),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty() &&
                placeholder != null
            ) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Tokens.textDisabled)
            }
            BasicTextField(
                value,
                onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tokens.textPrimary),
                cursorBrush = SolidColor(accent),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
    }
}

/**
 * The management half of a work, as the brief splits it: **Releases** (each want's state, the
 * files held with their verdicts, and the indexer candidates with Acquire), **Rules** (score a
 * release against the active profile), and **Health** (replicas, captions and artwork, the
 * works the scanner minted for the same title, identifiers and the raw file list). Nothing here
 * is paraphrased — rule codes, states and the node's detail lines are shown as sent.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun ReleasesTab(session: AppSession, wants: List<DesiredItem>, state: DetailState, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        WantsSection(session, wants, state, scope, reload)
        HeldFilesSection(wants, state)
        CandidatesSection(session, wants, state, scope, reload)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun RulesTab(session: AppSession, wants: List<DesiredItem>) {
    Section("Rules", subtitle = "Score a release against the active profile") {
        ExplainPanel(session, wants)
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList") // Compose naming; the health sections each need their own slice.
internal fun HealthTab(
    session: AppSession,
    detail: WorkDetail,
    type: MediaType,
    wants: List<DesiredItem>,
    state: DetailState,
    seasons: List<Season>,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        HealthSection(session, detail, state, scope)
        Section("Captions & artwork", initiallyOpen = false) { SidecarsPanel(session, state, seasons, type) }
        if (state.variants.isNotEmpty()) VariantsSection(state)
        IdentifiersSection(detail, type, state)
        AllFilesSection(state.assets.orEmpty())
    }
}

/** One work's caption/cover request in flight, and what the node answered last. */
private class SidecarRunner {
    var busy by mutableStateOf(false)
    var outcome by mutableStateOf<SidecarOutcome?>(null)

    fun run(session: AppSession, scope: CoroutineScope, workId: String, action: SidecarAction, language: String?) {
        val api = session.api ?: return
        busy = true
        scope.launch {
            outcome = session.io { api.sidecar(action, workId, language) }.fold(
                onSuccess = { r ->
                    when (r) {
                        is McpResult.Ok -> SidecarOutcome(r.value.message, refused = false)
                        is McpResult.Refused -> SidecarOutcome(r.message, refused = true, tool = r.tool)
                    }
                },
                onFailure = { SidecarOutcome(it.message ?: "Couldn't reach heyarr", refused = true) },
            )
            busy = false
        }
    }
}
