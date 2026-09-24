package one.rarebit.heyarr.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.playback.AudioState
import one.rarebit.heyarr.mobile.theme.LocalMediaTheme
import one.rarebit.heyarr.mobile.theme.MediaScope
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Artwork
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.IconButtonRound
import one.rarebit.heyarr.mobile.ui.components.Notice
import one.rarebit.heyarr.mobile.ui.components.SectionHeader
import one.rarebit.heyarr.mobile.ui.components.clockShort

/** The full audio screen: cover, transport, scrubber, and the queue — the music accent throughout. */
@Composable
fun AudioQueueScreen(
    state: AudioState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipTo: (Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    BackHandler(onBack = onBack)
    MediaScope(MediaType.MUSIC) {
        val theme = LocalMediaTheme.current
        Column(modifier.fillMaxSize().background(Tokens.bgBase).safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Back", onBack, icon = Icons.Rounded.ArrowBack)
                Spacer(Modifier.weight(1f))
                IconButtonRound(Icons.Rounded.Stop, "Stop", onStop, size = 40.dp)
            }
            Box(Modifier.fillMaxWidth(0.7f).aspectRatio(1f).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(Tokens.radiusCard))) {
                Artwork(item.artworkUrl, MediaType.MUSIC, Modifier.fillMaxSize(), contentDescription = item.album, glyphSize = 64.dp)
            }
            Text(item.title, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            Text(listOfNotNull(item.artist, item.album).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted)
            state.error?.let { Notice("Playback error: $it", tone = Tokens.danger, modifier = Modifier.padding(top = 8.dp)) }
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: state.fraction, onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { if (state.durationMs > 0) onSeek((it * state.durationMs).toLong()) }; dragging = null },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Position" }, enabled = state.durationMs > 0,
                colors = SliderDefaults.colors(thumbColor = theme.accentGradientEnd, activeTrackColor = theme.accent, inactiveTrackColor = Tokens.surface3),
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(clockShort(state.positionMs), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                Text(clockShort(state.durationMs), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                IconButtonRound(Icons.Rounded.SkipPrevious, "Previous", onPrevious, size = 44.dp)
                IconButtonRound(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", onTogglePlay, size = 64.dp, filled = true)
                IconButtonRound(Icons.Rounded.SkipNext, "Next", onNext, size = 44.dp, enabled = state.hasNext)
            }
            SectionHeader("Queue", subtitle = "${state.queue.size} tracks")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(state.queue, key = { i, it -> "$i:${it.assetId}" }) { i, track ->
                    val current = i == state.index
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.radiusInput)).background(if (current) theme.tint(0.16f) else Tokens.surface1).clickable { onSkipTo(i) }.padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics { contentDescription = "${track.title}${if (current) ", playing" else ""}" },
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (current) "▶" else "%02d".format(i + 1), style = MaterialTheme.typography.labelMedium, color = if (current) theme.accentGradientEnd else Tokens.textMuted, modifier = Modifier.width(28.dp))
                        Text(track.title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
