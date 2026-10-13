package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.ui.components.DashedDivider
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.KeyValue
import one.rarebit.heyarr.ui.components.ReasonList
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionLabel
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * A work's context column: where its acquisition stands, and why the chosen release was chosen.
 * Both come straight from the node — the want's state and profile, the candidate the node marked
 * selected (or the best-scoring one, labelled as such), and that candidate's rule results with
 * their codes as sent. Nothing is inferred from a release name.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun DetailContext(
    session: AppSession,
    wants: List<DesiredItem>,
    state: DetailState,
    onWant: (String, String, MediaType) -> Unit,
    detail: WorkDetail,
) {
    AcquisitionStatus(session, wants, state, onWant, detail)
    WhyThisRelease(state)
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun AcquisitionStatus(
    session: AppSession,
    wants: List<DesiredItem>,
    state: DetailState,
    onWant: (String, String, MediaType) -> Unit,
    detail: WorkDetail,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Acquisition status")
        DashedDivider()
        if (wants.isEmpty()) {
            Text(
                "Not wanted — nothing is looking for a copy.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )
            SecondaryButton("Want", {
                onWant(detail.work.id, detail.work.title, MediaType.from(detail.work.kind))
            }, icon = HeyarrIcons.Add)
            return@Column
        }
        for (w in wants) {
            val profile = session.profiles.firstOrNull { it.id == w.qualityProfileId }?.name ?: w.qualityProfileId
            StatusPill(LibraryStatus.ofState(w.state))
            KeyValue("State", w.state.lowercase().replace('_', ' '))
            profile?.let { KeyValue("Profile", it) }
            KeyValue("Monitoring", if (w.monitor) "on — keeps looking for better" else "off")
            w.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            }
        }
        GhostButton("Releases", { state.tab = DetailTab.RELEASES }, icon = HeyarrIcons.Forward)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun WhyThisRelease(state: DetailState) {
    val all = state.candidates.values.flatten()
    val chosen = all.firstOrNull { it.selected }
    val shown: Candidate? = chosen ?: all.maxByOrNull { it.score }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Why this release?")
        DashedDivider()
        if (shown == null) {
            Text(
                "No release has been chosen yet. Releases lists what the indexers found.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )
            return@Column
        }
        Text(
            if (chosen != null) "SELECTED" else "BEST SCORING · NOT SELECTED",
            style = MaterialTheme.typography.labelSmall,
            color = if (chosen != null) Tokens.accent else Tokens.textMuted,
        )
        Text(
            shown.title,
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Score ${shown.score}", style = MaterialTheme.typography.labelMedium, color = Tokens.textPrimary)
            shown.provider?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted) }
            Text(
                if (shown.accepted) "accepted" else "rejected",
                style = MaterialTheme.typography.labelSmall,
                color = if (shown.accepted) Tokens.accent else Tokens.danger,
            )
        }
        ReasonList(shown.reasons.take(WHY_REASONS_SHOWN))
        GhostButton("View rule details", { state.tab = DetailTab.RULES }, icon = HeyarrIcons.Forward)
    }
}

/** The column shows the first few rule results; the Rules tab has the rest. */
private const val WHY_REASONS_SHOWN = 6
