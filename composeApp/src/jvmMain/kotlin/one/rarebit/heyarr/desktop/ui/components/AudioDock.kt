@file:Suppress("FunctionNaming") // Compose components follow the shared component naming convention.

package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.desktop.playback.PlayerState
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.PlaybackSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.ArchiveActivity
import one.rarebit.heyarr.ui.components.DashedDivider
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PixelCluster
import one.rarebit.heyarr.ui.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens

/** Full-height session transport; the cover uses the same authenticated artwork path as the catalog. */
@Composable
fun AudioDock(session: AppSession, onOpen: () -> Unit, modifier: Modifier = Modifier, companion: Boolean = false) {
    val playback = session.playback
    val item = playback.current ?: return
    val state = playback.player.state
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
    Column(
        modifier.width(280.dp).fillMaxHeight().background(Tokens.surface1).border(Tokens.hairline, Tokens.border)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AudioDockHeading(companion, playback)
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
        AudioDockStatus(playback.startError ?: state.error, state.buffering || playback.pendingStart)
        AudioDockSeek(playback, state, item.assetId)
        AudioDockTransport(playback, state)
        AudioDockVolume(playback, state.volume)
        GhostButton("Player details", onOpen, icon = Icons.Rounded.OpenInFull)
        AudioDockQueue(playback, item.assetId)
    }
}

@Composable
private fun AudioDockHeading(companion: Boolean, playback: PlaybackSession) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PixelCluster(Modifier.size(18.dp), color = LocalMediaTheme.current.accentGradientEnd)
        Text(
            if (companion) "Companion audio" else "Now playing",
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.textPrimary,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButtonRound(Icons.Rounded.Close, "Stop audio", playback::stop)
    }
}

@Composable
private fun AudioDockStatus(error: String?, buffering: Boolean) {
    if (error != null) {
        Notice(error)
    } else if (buffering) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArchiveActivity(LocalAppearance.current.reduceMotion)
            Text("Buffering…", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
        }
    }
}

@Composable
private fun AudioDockSeek(playback: PlaybackSession, state: PlayerState, assetId: String) {
    var dragging by remember(assetId) { mutableStateOf<Float?>(null) }
    Column {
        Slider(
            value = dragging ?: state.fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { playback.player.seekFraction(it.toDouble()) }
                dragging = null
            },
            enabled = state.duration > 0,
            modifier = Modifier.semantics { contentDescription = "Audio position" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockShort(state.position), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            Text(
                if (state.duration > 0) clockShort(state.duration) else "—",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
    }
}

@Composable
private fun AudioDockTransport(playback: PlaybackSession, state: PlayerState) {
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
            enabled = previous != null,
            size = 44.dp,
        )
        IconButtonRound(
            if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            if (state.paused) "Play audio" else "Pause audio",
            playback.player::togglePause,
            size = 48.dp,
            filled = true,
        )
        val next = playback.next()
        IconButtonRound(
            Icons.Rounded.SkipNext,
            "Next track",
            { next?.let(playback::play) },
            enabled = next != null,
            size = 44.dp,
        )
    }
}

@Composable
private fun AudioDockVolume(playback: PlaybackSession, volume: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeUp, null, tint = Tokens.textMuted, modifier = Modifier.size(20.dp))
        Slider(
            value = volume.toFloat().coerceIn(0f, 100f),
            onValueChange = { playback.player.setVolume(it.toDouble()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Audio volume" },
        )
    }
}

@Composable
private fun AudioDockQueue(playback: PlaybackSession, currentAssetId: String) {
    val currentIndex = playback.audioQueue.indexOfFirst { it.assetId == currentAssetId }
    val upcoming = if (currentIndex >= 0) playback.audioQueue.drop(currentIndex + 1) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Queue", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        if (upcoming.isEmpty()) {
            Text("End of queue", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        upcoming.forEachIndexed { index, track ->
            AudioQueueRow(track, playback)
            if (index < upcoming.lastIndex) DashedDivider()
        }
    }
}

@Composable
private fun AudioQueueRow(track: Route.Player, playback: PlaybackSession) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            track.subtitle ?: track.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButtonRound(Icons.Rounded.PlayArrow, "Play ${track.subtitle ?: track.title}", { playback.play(track) })
    }
}
