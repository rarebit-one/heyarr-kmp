package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.playback.PlaybackPreference
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.Panel

/**
 * Which copy of a film plays when a work holds several, in two layers (heyarr-core#887): the
 * person's default, synced to all their devices through encrypted personal state, and this
 * phone's override, kept here. A copy picked in the player's Version menu still wins for that
 * film.
 */
@Suppress("FunctionNaming") // a composable is PascalCase by Compose convention
@Composable
internal fun PlaybackPanel(session: AppSession) {
    val account = session.accountPlayback
    val default = account.preference ?: PlaybackPreference()
    val override = session.playbackOverride
    val effective = PlaybackPreference.resolve(account.preference, override)
    Panel("Playback") {
        Label("Your default · all your devices")
        ChipRow {
            for (q in QualityPreference.entries) {
                FilterChip(
                    q.label,
                    default.quality == q,
                    { account.onChange(default.copy(quality = q)) },
                    enabled = account.enabled,
                )
            }
        }
        Label("Cap")
        ChipRow {
            for (h in listOf(null) + PlaybackPreference.CAP_HEIGHTS) {
                FilterChip(
                    h?.let { "≤${it}p" } ?: "No cap",
                    default.maxHeight == h,
                    { account.onChange(default.copy(maxHeight = h)) },
                    enabled = account.enabled,
                )
            }
        }
        if (!account.enabled) {
            Note(
                "Your default is kept in your encrypted personal state; this device has no key to it, " +
                    "so only the setting below applies.",
            )
        }
        Label("On this phone")
        ChipRow {
            FilterChip("Use my default", override == null, { session.updatePlaybackOverride(null) })
            for (q in QualityPreference.entries) {
                FilterChip(q.label, override == q, { session.updatePlaybackOverride(q) })
            }
        }
        Note(
            "Playing here: ${effective.label}. When a film is held in more than one copy, fit this screen plays " +
                "the tallest that fits, an SDR copy first on a screen without HDR. Best quality takes the tallest " +
                "copy whatever the screen; data saver stops at 720p. A cap holds under all three. A copy you pick " +
                "in the player's Version menu is remembered for that film on this phone.",
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
}

@Suppress("FunctionNaming")
@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
}

@Suppress("FunctionNaming")
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}
