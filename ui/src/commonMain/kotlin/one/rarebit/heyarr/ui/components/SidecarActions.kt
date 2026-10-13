package one.rarebit.heyarr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.heyarr.SidecarAction
import one.rarebit.heyarr.ui.theme.Tokens

/** Which levers a work offers: captions for video, a cover lookup for music and books. */
data class SidecarOptions(val video: Boolean, val enrichable: Boolean, val canWrite: Boolean)

/** What the last request came back with, in the node's words or counts. */
data class SidecarOutcome(val text: String, val refused: Boolean)

/**
 * The caption and cover levers for one work (see [SidecarAction]): extract the captions a video
 * already carries, fetch captions in a language from the node's subtitle provider, or look a
 * music or book cover up. Stateless apart from the language being typed; the app owns the I/O.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun SidecarActions(
    options: SidecarOptions,
    busy: Boolean,
    outcome: SidecarOutcome?,
    onAction: (SidecarAction, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var language by rememberSaveable { mutableStateOf("en") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !options.canWrite -> Hint("Sign in to fetch captions or covers.")

            !options.video && !options.enrichable -> Hint(
                "The node has no caption or cover lookup for this kind of work.",
            )

            else -> Unit
        }
        if (options.canWrite && options.video) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                SecondaryButton(
                    SidecarAction.EXTRACT_CAPTIONS.label,
                    { onAction(SidecarAction.EXTRACT_CAPTIONS, null) },
                    icon = HeyarrIcons.Captions,
                    compact = true,
                    enabled = !busy,
                )
                Field("Language", language, Modifier.width(96.dp), placeholder = "en") { typed ->
                    language = typed.filter { it.isLetter() }.take(2).lowercase()
                }
                SecondaryButton(
                    SidecarAction.FETCH_CAPTIONS.label,
                    { onAction(SidecarAction.FETCH_CAPTIONS, language) },
                    icon = HeyarrIcons.Download,
                    compact = true,
                    enabled = !busy && language.length == 2,
                )
            }
            Hint("Film and series artwork comes from the metadata provider when heyarr identifies the work.")
        }
        if (options.canWrite && options.enrichable) {
            SecondaryButton(
                SidecarAction.FETCH_COVER.label,
                { onAction(SidecarAction.FETCH_COVER, null) },
                icon = HeyarrIcons.Image,
                compact = true,
                enabled = !busy,
            )
        }
        if (busy) ArchiveLoading("Asking heyarr…")
        outcome?.let { Notice(it.text, tone = if (it.refused) Tokens.danger else Tokens.slate) }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
}
