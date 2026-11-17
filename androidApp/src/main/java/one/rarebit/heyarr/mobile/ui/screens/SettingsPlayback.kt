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
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.Panel

/**
 * Which copy of a film this phone plays when a work holds several (heyarr-kmp#225). Kept on
 * this phone only: what fits is a fact about this screen and this connection. A copy picked
 * in the player's Version menu still wins for that film.
 */
@Suppress("FunctionNaming") // a composable is PascalCase by Compose convention
@Composable
internal fun PlaybackPanel(session: AppSession) {
    Panel("Playback") {
        Text("Prefer", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in QualityPreference.entries) {
                FilterChip(p.label, session.playbackPreference == p, { session.updatePlaybackPreference(p) })
            }
        }
        Text(
            "When a film is held in more than one copy, this phone plays the tallest that fits its screen, " +
                "an SDR copy first on a screen without HDR. Best quality takes the tallest copy whatever the " +
                "screen; data saver stops at 720p. A copy you pick in the player's Version menu is remembered " +
                "for that film on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}
