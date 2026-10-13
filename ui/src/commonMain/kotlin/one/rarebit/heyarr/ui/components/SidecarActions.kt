package one.rarebit.heyarr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.heyarr.SidecarAction
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens

/** Which levers a work offers: captions for video, a cover lookup for music and books, as [mode] allows. */
data class SidecarOptions(val video: Boolean, val enrichable: Boolean, val mode: ClientMode) {
    val captions: Boolean get() = video && SidecarAction.FETCH_CAPTIONS.allowedFor(mode)
    val cover: Boolean get() = enrichable && SidecarAction.FETCH_COVER.allowedFor(mode)

    companion object {
        fun of(type: MediaType, mode: ClientMode) = SidecarOptions(
            video = type == MediaType.MOVIE || type == MediaType.SERIES,
            enrichable = type == MediaType.MUSIC || type == MediaType.BOOK,
            mode = mode,
        )
    }
}

/** What the last request came back with, in the node's words or counts, and which endpoint refused it. */
data class SidecarOutcome(val text: String, val refused: Boolean, val tool: String? = null)

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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !options.video && !options.enrichable -> Hint(
                "The node has no caption or cover lookup for this kind of work.",
            )

            options.enrichable && !options.cover -> Hint("Sign in to look up covers.")

            else -> Unit
        }
        if (options.captions) CaptionControls(busy, onAction)
        if (options.video) {
            Hint("Film and series artwork comes from the metadata provider when heyarr identifies the work.")
        }
        if (options.cover) {
            SecondaryButton(
                SidecarAction.FETCH_COVER.label,
                { onAction(SidecarAction.FETCH_COVER, null) },
                icon = HeyarrIcons.Image,
                compact = true,
                enabled = !busy,
            )
        }
        if (busy) ArchiveLoading("Asking heyarr…")
        outcome?.let {
            Notice(
                it.text,
                tone = if (it.refused) Tokens.danger else Tokens.slate,
                detail = it.tool?.let { tool -> "Refused by $tool" },
            )
        }
    }
}

/** Extract, then a language and fetch; wraps onto further lines when a phone is too narrow for one. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
@OptIn(ExperimentalLayoutApi::class)
private fun CaptionControls(busy: Boolean, onAction: (SidecarAction, String?) -> Unit) {
    var language by rememberSaveable { mutableStateOf("en") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
}
