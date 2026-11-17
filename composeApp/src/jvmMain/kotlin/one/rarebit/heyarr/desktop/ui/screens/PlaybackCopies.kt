package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.playback.Copy
import one.rarebit.heyarr.core.playback.CopyChoice
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.music.Track
import one.rarebit.heyarr.desktop.playback.PlaybackResume
import one.rarebit.heyarr.desktop.playback.PlayerState
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.FilmCopies
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.MetaLine
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens

// Which copy of a film plays on this machine (heyarr-kmp#225): the detail screen's copies
// list, the player's Version menu and Settings → Playback.

/** One held copy: its picture and size, its release tags, whether it plays here, and Play. */
@Composable
internal fun copyRow(copy: Copy<Track>, playsHere: Boolean, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                copy.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (playsHere) LocalMediaTheme.current.accentGradientEnd else Tokens.textPrimary,
            )
            MetaLine(Series.qualityTags(copy.asset).filterNot { copy.picture?.contains(it, ignoreCase = true) == true })
        }
        if (playsHere) Text("Plays here", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        SecondaryButton(
            "Play",
            onPlay,
            icon = HeyarrIcons.Play,
            compact = true,
            contentDescription = "Play ${copy.label}",
        )
    }
}

/** Play a copy picked on the detail screen, remembered for the work, with every copy offered to the player. */
internal fun playCopyFromDetail(
    session: AppSession,
    state: DetailState,
    detail: WorkDetail,
    choice: CopyChoice<Track>,
) {
    val copy = choice.picked
    val hash = copy.asset.blobHash ?: return
    session.chooseCopy(state.workId, copy.asset.id)
    session.playback.filmCopies = FilmCopies(state.workId, choice.copies)
    state.openPlayer(
        Route.Player(state.workId, copy.asset.id, hash, detail.work.title, typeHint = MediaType.MOVIE, from = "Back"),
    )
}

/**
 * The film's copies as a menu beside Audio and Captions: the button names the copy playing
 * (`1080p`), the menu lists every copy with its picture and size, and a pick loads that copy
 * from the current position and is remembered for the work on this machine. Hidden unless
 * the work holds two or more copies.
 */
@Composable
internal fun versionMenu(ps: PlayerState, session: AppSession) {
    val playback = session.playback
    val item = playback.current ?: return
    if (playback.copies.size < 2) return
    var open by remember { mutableStateOf(false) }
    val playing = playback.copies.firstOrNull { it.asset.id == item.assetId }
    Box {
        SecondaryButton(
            playing?.picture ?: "Version",
            { open = !open },
            compact = true,
            enabled = ps.loaded,
            contentDescription = "Version: ${playing?.label ?: "unknown"}",
        )
        DropdownMenu(expanded = open, onDismissRequest = {
            open = false
        }, modifier = Modifier.background(Tokens.surface3)) {
            Text(
                "Version",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            for (copy in playback.copies) {
                val selected = copy.asset.id == item.assetId
                DropdownMenuItem(
                    text = {
                        Text(
                            copy.label,
                            color = if (selected) LocalMediaTheme.current.accentGradientEnd else Tokens.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        val hash = copy.asset.blobHash
                        if (!selected && hash != null) {
                            session.chooseCopy(item.workId, copy.asset.id)
                            // Another file, so a fresh plan and load — picking up where this one was.
                            playback.pendingResume = PlaybackResume(ps.position, ps.paused)
                            playback.play(item.copy(assetId = copy.asset.id, blobHash = hash))
                        }
                    },
                )
            }
        }
    }
}

/**
 * Settings → Playback: which copy of a film this machine plays when a work holds several.
 * Kept on this machine only; a copy picked in the Version menu still wins for that film.
 */
@Composable
internal fun playbackPanel(session: AppSession) {
    Panel("Playback") {
        Text("Prefer", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in QualityPreference.entries) {
                FilterChip(p.label, session.playbackPreference == p, { session.updatePlaybackPreference(p) })
            }
        }
        Text(
            "When a film is held in more than one copy, this machine plays the tallest that fits its screen " +
                "(at most 1080p, which it decodes smoothly), an SDR copy first. Best quality takes the tallest " +
                "copy; data saver stops at 720p. A copy you pick in the player's Version menu is remembered " +
                "for that film here.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}
