package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.ArchiveActivity
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens

/** Full-height session transport; the cover uses the same authenticated artwork path as the catalog. */
@Composable
fun AudioDock(session: AppSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val playback = session.playback
    val item = playback.current ?: return
    val ps = playback.player.state
    val work by produceState<one.rarebit.heyarr.desktop.library.WorkDetail?>(null, item.workId, session.generation) {
        value = session.io { session.api?.work(item.workId) }.getOrNull()
    }
    val cover by rememberCover(
        session,
        playback.type,
        item.title,
        work?.artworkPath,
        work?.work?.year,
        work?.work?.artist ?: work?.work?.author,
    )
    val accent = LocalMediaTheme.current.accentGradientEnd
    Column(
        modifier.width(280.dp).fillMaxHeight().background(Tokens.surface1).border(Tokens.hairline, Tokens.border)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Headphones, null, tint = accent, modifier = Modifier.size(20.dp))
            Text(
                "Listening now",
                style = MaterialTheme.typography.titleSmall,
                color = Tokens.textPrimary,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButtonRound(Icons.Rounded.Close, "Stop audio", playback::stop)
        }
        Artwork(
            cover.bitmap,
            playback.type,
            Modifier.fillMaxWidth().aspectRatio(1f),
            contentDescription = "Cover for ${item.title}",
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.subtitle ?: item.title, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
            Text(item.title, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        val error = playback.startError ?: ps.error
        if (error != null) {
            Notice(error)
        } else if (ps.buffering || playback.pendingStart) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArchiveActivity(LocalAppearance.current.reduceMotion)
                Text("Buffering…", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
            }
        }
        var dragging by remember(item.assetId) { mutableStateOf<Float?>(null) }
        Column {
            Slider(value = dragging ?: ps.fraction, onValueChange = { dragging = it }, onValueChangeFinished = {
                dragging?.let { playback.player.seekFraction(it.toDouble()) }
                dragging = null
            }, enabled = ps.duration > 0, modifier = Modifier.semantics { contentDescription = "Audio position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clockShort(ps.position), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                Text(
                    if (ps.duration >
                        0
                    ) {
                        clockShort(ps.duration)
                    } else {
                        "—"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.textMuted,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previous = playback.previousAudio()
            IconButtonRound(
                Icons.Rounded.SkipPrevious,
                "Previous track",
                { previous?.let(playback::play) },
                enabled =
                previous != null,
                size = 44.dp,
            )
            IconButtonRound(
                if (ps.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                if (ps.paused) "Play audio" else "Pause audio",
                playback.player::togglePause,
                size = 48.dp,
                filled = true,
            )
            val next = playback.next()
            IconButtonRound(Icons.Rounded.SkipNext, "Next track", {
                next?.let(playback::play)
            }, enabled = next != null, size = 44.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolumeUp, null, tint = Tokens.textMuted, modifier = Modifier.size(20.dp))
            Slider(
                value = ps.volume.toFloat().coerceIn(0f, 100f),
                onValueChange = { playback.player.setVolume(it.toDouble()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Audio volume" },
            )
        }
        GhostButton("Player details", onOpen, icon = Icons.Rounded.OpenInFull)
        val currentIndex = playback.audioQueue.indexOfFirst { it.assetId == item.assetId }
        val upcoming = if (currentIndex >= 0) playback.audioQueue.drop(currentIndex + 1) else emptyList()
        Text("Up next", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        if (upcoming.isEmpty()) {
            Text(
                "End of queue",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
            )
        }
        for (track in upcoming) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.subtitle ?: track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButtonRound(Icons.Rounded.PlayArrow, "Play ${track.subtitle ?: track.title}", {
                    playback.play(track)
                })
            }
        }
    }
}
