package one.rarebit.heyarr.desktop.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.OpenInFull
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
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.isListening
import androidx.compose.runtime.produceState
import one.rarebit.heyarr.desktop.state.PlaybackSession
import one.rarebit.heyarr.desktop.theme.LocalMediaTheme
import one.rarebit.heyarr.desktop.theme.MediaScope
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * The persistent transport at the foot of the window while something plays and the
 * player screen is not showing. The thumbnail is the live picture — the same mpv,
 * drawn small — so video keeps moving in the corner while you browse; music and
 * podcasts simply keep playing. Click the title or the expand button to return to
 * the full player; the close button stops playback.
 */
@Composable
fun NowPlayingBar(session: AppSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val playback = session.playback
    val item = playback.current ?: return
    val ps = playback.player.state
    MediaScope(playback.type) {
        val theme = LocalMediaTheme.current
        val interaction = remember { MutableInteractionSource() }
        Column(modifier.fillMaxWidth().background(Tokens.surface1).border(Tokens.hairline, Tokens.border)) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: ps.fraction, onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { playback.player.seekFraction(it.toDouble()) }; dragging = null },
                modifier = Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = "Position" }, enabled = ps.duration > 0,
                colors = SliderDefaults.colors(thumbColor = theme.accentGradientEnd, activeTrackColor = theme.accent, inactiveTrackColor = Tokens.surface3),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The live picture, small.
                if (playback.type.isListening()) {
                    val detail by produceState<one.rarebit.heyarr.desktop.library.WorkDetail?>(null, item.workId, session.generation) {
                        value = session.io { session.api?.work(item.workId) }.getOrNull()
                    }
                    val cover by rememberCover(session, playback.type, item.title, detail?.artworkPath, detail?.work?.year, detail?.work?.artist)
                    Artwork(cover.bitmap, playback.type, Modifier.width(50.dp).height(50.dp), contentDescription = "Cover for ${item.title}")
                } else VideoSurface(playback.player, Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(4.dp)))
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen).semantics { contentDescription = "Open the player for ${item.title}" }.padding(4.dp),
                ) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(item.subtitle, if (ps.duration > 0) "${clockShort(ps.position)} / ${clockShort(ps.duration)}" else null, if (ps.buffering) "buffering…" else null).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButtonRound(Icons.Rounded.Replay10, "Back 10 seconds", { playback.player.seekBy(-10.0) }, size = 32.dp)
                IconButtonRound(if (ps.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (ps.paused) "Play" else "Pause", { playback.player.togglePause() }, size = 40.dp, filled = true)
                IconButtonRound(Icons.Rounded.Forward10, "Forward 10 seconds", { playback.player.seekBy(10.0) }, size = 32.dp)
                val next = playback.next()
                if (next != null) IconButtonRound(Icons.Rounded.SkipNext, "Next: ${next.subtitle}", { playback.play(next) }, size = 32.dp)
                IconButtonRound(Icons.Rounded.OpenInFull, "Open player", onOpen, size = 32.dp)
                IconButtonRound(Icons.Rounded.Close, "Stop playback", { playback.stop() }, size = 32.dp)
            }
        }
    }
}

internal fun clockShort(s: Double): String {
    val t = s.toLong().coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
