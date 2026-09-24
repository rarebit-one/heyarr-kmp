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
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Field
import one.rarebit.heyarr.mobile.ui.components.FilterChip
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.PrimaryButton

/** A pending Want: either an existing work by id, or a title the library has never seen. */
data class WantRequest(val workId: String?, val title: String)

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
    var year by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(MediaType.MOVIE) }
    var profile by remember(session.profiles) { mutableStateOf(session.profiles.firstOrNull { it.name == session.defaultProfile }?.name ?: session.profiles.firstOrNull()?.name ?: session.defaultProfile) }
    var monitor by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val byTitle = req.workId == null
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Tokens.surface1, contentColor = Tokens.textPrimary) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (byTitle) "Want by title" else "Want “${req.title}”", style = MaterialTheme.typography.headlineSmall, color = Tokens.textPrimary)
            if (byTitle) {
                Field("Title", title) { title = it }
                Field("Year (optional)", year, Modifier.width(160.dp), keyboard = KeyboardType.Number) { year = it.filter { c -> c.isDigit() }.take(4) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (t in MediaType.SEARCHABLE) FilterChip(t.label, type == t, { type = t }) }
                Text("Created from the title with the same normalisation a scan uses, so wanting it now and scanning it later converge on one work.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            }
            Text("Quality profile — the standard this want is measured against", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
            if (session.profiles.isEmpty()) Text("No profiles loaded yet — the default profile name is used.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (p in session.profiles) FilterChip(p.name, profile == p.name, { profile = p.name }) }
            session.profiles.firstOrNull { it.name == profile }?.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip("Keep looking for something better", monitor, { monitor = !monitor }) }
            Field("Reason (a note for whoever reads this in six months)", reason) { reason = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Want", {
                    if (!byTitle) { session.want(req.workId!!, req.title, profile) { onClose() }; return@PrimaryButton }
                    busy = true
                    scope.launch {
                        session.io { session.api.wantTitle(title.trim(), type, profile, year.toIntOrNull(), monitor, reason.ifBlank { null }) }.onSuccess { r ->
                            when (r) {
                                is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Wanted “${title.trim()}”", "Measured against the $profile profile."); session.refreshIndex(); onClose() }
                                is McpResult.Refused -> session.refused(r)
                            }
                        }
                        busy = false
                    }
                }, icon = Icons.Rounded.Add, enabled = !busy && profile.isNotBlank() && (!byTitle || title.isNotBlank()))
                GhostButton("Cancel", onClose)
            }
        }
    }
}
