package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.playback.AudioState
import one.rarebit.heyarr.mobile.playback.VideoSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope

/**
 * The persistent transport above the bottom bar while something plays and the player
 * screen is not showing — one bar for both players: the in-app ExoPlayer (a film, an
 * episode) and the audio queue (an album, an audiobook). Tap the title to return to
 * the full player; the close button stops playback.
 */
@UnstableApi
@Composable
fun NowPlayingBar(
    video: VideoSession,
    audio: AudioState,
    onOpen: () -> Unit,
    onAudioToggle: () -> Unit,
    onAudioNext: () -> Unit,
    onAudioSeek: (Long) -> Unit,
    onAudioStop: () -> Unit,
    onVideoNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val item = video.current
    if (item != null) {
        val ps = video.state
        MediaScope(MediaType.from(item.kind ?: "movie")) {
            Bar(
                title = item.title, subtitle = listOfNotNull(if (ps.durationMs > 0) "${clockShort(ps.positionMs)} / ${clockShort(ps.durationMs)}" else null, if (ps.buffering) "buffering…" else null).joinToString("  ·  "),
                artwork = item.artworkUrl, type = MediaType.from(item.kind ?: "movie"), fraction = ps.fraction, seekable = ps.durationMs > 0,
                paused = ps.paused, onOpen = onOpen, onSeekFraction = { video.seekFraction(it) },
                onBack = { video.seekBy(-10.0) }, onToggle = { video.togglePause() }, onForward = { video.seekBy(10.0) },
                onNext = onVideoNext, nextLabel = video.next()?.subtitle, onStop = { video.stop() }, modifier = modifier,
            )
        }
        return
    }
    val track = audio.item ?: return
    MediaScope(MediaType.MUSIC) {
        Bar(
            title = track.title, subtitle = listOfNotNull(track.artist, track.album, if (audio.durationMs > 0) "${clockShort(audio.positionMs)} / ${clockShort(audio.durationMs)}" else null).joinToString("  ·  "),
            artwork = track.artworkUrl, type = MediaType.MUSIC, fraction = audio.fraction, seekable = audio.durationMs > 0,
            paused = !audio.playing, onOpen = onOpen, onSeekFraction = { onAudioSeek((it * audio.durationMs).toLong()) },
            onBack = { onAudioSeek((audio.positionMs - 10_000).coerceAtLeast(0)) }, onToggle = onAudioToggle, onForward = { onAudioSeek((audio.positionMs + 10_000).coerceAtMost(audio.durationMs)) },
            onNext = if (audio.hasNext) onAudioNext else null, nextLabel = audio.queue.getOrNull(audio.index + 1)?.title, onStop = onAudioStop, modifier = modifier,
        )
    }
}

@Composable
private fun Bar(
    title: String,
    subtitle: String,
    artwork: String?,
    type: MediaType,
    fraction: Float,
    seekable: Boolean,
    paused: Boolean,
    onOpen: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onForward: () -> Unit,
    onNext: (() -> Unit)?,
    nextLabel: String?,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val theme = LocalMediaTheme.current
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().background(Tokens.surface1).border(Tokens.hairline, Tokens.border)) {
        var dragging by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(onSeekFraction)
                dragging = null
            },
            modifier = Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = "Position" },
            enabled = seekable,
            colors = SliderDefaults.colors(thumbColor = theme.accentGradientEnd, activeTrackColor = theme.accent, inactiveTrackColor = Tokens.surface3),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(Tokens.radiusCard))) { Artwork(artwork, type, Modifier.size(44.dp), glyphSize = 18.dp) }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(Tokens.radiusCard)).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen).semantics { contentDescription = "Open the player for $title" }.padding(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButtonRound(Icons.Rounded.Replay10, "Back 10 seconds", onBack, size = 36.dp, enabled = seekable)
            IconButtonRound(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Play" else "Pause", onToggle, size = 44.dp, filled = true)
            IconButtonRound(Icons.Rounded.Forward10, "Forward 10 seconds", onForward, size = 36.dp, enabled = seekable)
            if (onNext != null) IconButtonRound(Icons.Rounded.SkipNext, "Next: ${nextLabel ?: ""}", onNext, size = 36.dp)
            IconButtonRound(Icons.Rounded.Close, "Stop playback", onStop, size = 36.dp)
        }
    }
}

/** `1:23:45` / `12:34` from milliseconds. */
fun clockShort(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
