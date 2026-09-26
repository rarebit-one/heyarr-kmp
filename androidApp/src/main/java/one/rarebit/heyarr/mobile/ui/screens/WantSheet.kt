package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.QualityProfile
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.Field
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.PrimaryButton

/** A pending Want: either an existing work by id, or a title the library has never seen. */
data class WantRequest(val workId: String?, val title: String, val year: Int? = null, val type: MediaType? = null)

/**
 * The Want sheet: pick a quality profile (required — "this should exist" with no
 * standard cannot be evaluated), optionally a note, and go. Work-by-id when opened from
 * a card; title + type when opened from Missing for something the library has never
 * seen. `want_content` either way; a refusal is quoted in a toast and the optimistic
 * card rolls back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WantSheet(session: AppSession, req: WantRequest, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(req.title) }
    var year by remember(req.year) { mutableStateOf(req.year?.toString().orEmpty()) }
    var type by remember(req.type) { mutableStateOf(req.type ?: MediaType.MOVIE) }
    var profile by remember(session.profiles) {
        mutableStateOf(
            session.profiles.firstOrNull {
                it.name == session.defaultProfile
            }?.name ?: session.profiles.firstOrNull()?.name
                ?: session.defaultProfile,
        )
    }
    var monitor by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val byTitle = req.workId == null
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Tokens.surface1, contentColor = Tokens.textPrimary) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (byTitle) "Want by title" else "Want “${req.title}”",
                style = MaterialTheme.typography.headlineSmall,
                color = Tokens.textPrimary,
            )
            if (byTitle) {
                Field("Title", title) { title = it }
                Field("Year (optional)", year, Modifier.width(160.dp), keyboard = KeyboardType.Number) {
                    year =
                        it.filter { c -> c.isDigit() }.take(4)
                }
                TitleTypeChoice(type) { type = it }
            }
            ProfileChoice(session.profiles, profile) { profile = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("Keep looking for something better", monitor, {
                    monitor =
                        !monitor
                })
            }
            Field("Reason (a note for whoever reads this in six months)", reason) { reason = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Want", {
                    if (!byTitle) {
                        session.want(req.workId!!, req.title, profile) { onClose() }
                        return@PrimaryButton
                    }
                    val want =
                        TitleWant(title.trim(), type, profile, year.toIntOrNull(), monitor, reason.ifBlank { null })
                    sendTitleWant(session, scope, want, { busy = it }, onClose)
                }, icon = Icons.Rounded.Add, enabled = !busy && profile.isNotBlank() && (!byTitle || title.isNotBlank()))
                GhostButton("Cancel", onClose)
            }
        }
    }
}

/** The quality profile a want is measured against, with its description. */
@Composable
private fun ProfileChoice(profiles: List<QualityProfile>, profile: String, onProfile: (String) -> Unit) {
    Text(
        "Quality profile — the standard this want is measured against",
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.textMuted,
    )
    if (profiles.isEmpty()) {
        Text(
            "No profiles loaded yet — the default profile name is used.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in profiles) {
            FilterChip(
                p.name,
                profile == p.name,
                { onProfile(p.name) },
            )
        }
    }
    profiles.firstOrNull {
        it.name == profile
    }?.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) }
}

/** The media type a by-title want creates, and how the work it creates will meet a later scan. */
@Composable
private fun TitleTypeChoice(type: MediaType, onType: (MediaType) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (t in MediaType.SEARCHABLE) {
            FilterChip(
                t.label,
                type == t,
                { onType(t) },
            )
        }
    }
    Text(
        "Created from the title with the same normalisation a scan uses, so wanting it now and scanning it later converge on one work.",
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.textMuted,
    )
}

/** A want by title, as the sheet's fields read when Want was pressed. */
private data class TitleWant(
    val title: String,
    val type: MediaType,
    val profile: String,
    val year: Int?,
    val monitor: Boolean,
    val reason: String?,
)

/** Send [want] (`want_title`); success toasts, refreshes the index and closes the sheet. */
private fun sendTitleWant(
    session: AppSession,
    scope: CoroutineScope,
    want: TitleWant,
    onBusy: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    onBusy(true)
    scope.launch {
        session.io {
            session.api.wantTitle(
                want.title,
                want.type,
                want.profile,
                want.year,
                want.monitor,
                want.reason,
            )
        }.onSuccess { r ->
            when (r) {
                is McpResult.Ok -> {
                    session.toast(
                        Toast.Kind.SUCCESS,
                        "Wanted “${want.title}”",
                        "Measured against the ${want.profile} profile.",
                    )
                    session.refreshIndex()
                    onClose()
                }

                is McpResult.Refused -> session.refused(r)
            }
        }
        onBusy(false)
    }
}
