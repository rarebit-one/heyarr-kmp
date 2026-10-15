package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.heyarr.SourceAudioTrack
import one.rarebit.heyarr.desktop.playback.EmbeddedPlayer
import one.rarebit.heyarr.desktop.playback.MpvTrack
import one.rarebit.heyarr.desktop.playback.PlaybackResume
import one.rarebit.heyarr.desktop.playback.PlayerState
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.PlaybackSession
import one.rarebit.heyarr.desktop.state.rememberArtwork
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Artwork
import one.rarebit.heyarr.desktop.ui.components.VideoSurface
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens
import java.awt.GraphicsEnvironment

// The player's controls ([PlayerScreen]): captions, audio, volume, status text, and the pop-out notice.

/** Captions as a menu: Off, then each track by language name, title and origin. */
@Composable
internal fun captionMenu(ps: PlayerState, p: EmbeddedPlayer) {
    var open by remember { mutableStateOf(false) }
    val current = ps.subtitles.firstOrNull { it.id == ps.subtitleId }
    Box {
        SecondaryButton(
            if (ps.subtitles.isEmpty()) "No captions" else current?.let { trackLabel(it) } ?: "Captions off",
            { open = !open },
            icon = HeyarrIcons.Captions,
            compact = true,
            enabled = ps.subtitles.isNotEmpty(),
        )
        if (ps.subtitles.isNotEmpty() &&
            ps.subtitles.size > 1
        ) {
            Text(
                "+${ps.subtitles.size - 1}",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = {
            open = false
        }, modifier = Modifier.background(Tokens.surface3)) {
            if (ps.subtitles.isNotEmpty()) {
                CaptionMenuItems(ps) { id ->
                    p.setSubtitle(id)
                    open = false
                }
            }
        }
    }
}

/** Source tracks remain visible even when a transcode exposes only one track to mpv. */
@Composable
internal fun audioMenu(ps: PlayerState, p: EmbeddedPlayer, playback: PlaybackSession, session: AppSession) {
    val tracks = playback.audioTracks
    if (tracks.size <= 1 && ps.audio.size <= 1) return
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selected = tracks.firstOrNull { it.ordinal == playback.selectedAudioTrack }
    val label = if (selected?.commentary == true) {
        selected.title?.takeIf { it.isNotBlank() } ?: "Commentary"
    } else {
        languageName(selected?.language) ?: selected?.title
            ?: ps.audio.firstOrNull { it.id == ps.audioId }?.let(::trackLabel)
    }
    Box {
        SecondaryButton(
            "Audio · ${label ?: "Track"}",
            { open = !open },
            icon = HeyarrIcons.Volume,
            compact = true,
            enabled = ps.loaded && !playback.audioSwitching,
            contentDescription = "Choose audio track",
        )
        DropdownMenu(expanded = open, onDismissRequest = {
            open = false
        }, modifier = Modifier.background(Tokens.surface3)) {
            Text(
                "Audio",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (tracks.isNotEmpty()) {
                sourceAudioItems(tracks, playback.selectedAudioTrack) { ordinal ->
                    open = false
                    switchSourceAudio(ordinal, p, playback, session, scope)
                }
            } else {
                mpvAudioItems(ps, p) { open = false }
            }
        }
    }
}

@Composable
private fun sourceAudioItems(tracks: List<SourceAudioTrack>, selected: Int?, onPick: (Int) -> Unit) {
    for (track in tracks) {
        val name = listOfNotNull(
            languageName(track.language),
            track.title?.takeIf { it.isNotBlank() },
            if (track.commentary) "Commentary" else null,
        ).distinct().joinToString(" · ").ifBlank { "Track ${track.ordinal + 1}" }
        DropdownMenuItem(
            text = {
                Text(
                    name,
                    color = if (track.ordinal ==
                        selected
                    ) {
                        LocalMediaTheme.current.accentGradientEnd
                    } else {
                        Tokens.textPrimary
                    },
                )
            },
            onClick = { onPick(track.ordinal) },
        )
    }
}

@Composable
private fun mpvAudioItems(ps: PlayerState, p: EmbeddedPlayer, onPick: () -> Unit) {
    for (track in ps.audio) {
        DropdownMenuItem(
            text = {
                Text(
                    trackLabel(track),
                    color = if (track.id ==
                        ps.audioId
                    ) {
                        LocalMediaTheme.current.accentGradientEnd
                    } else {
                        Tokens.textPrimary
                    },
                )
            },
            onClick = {
                p.setAudio(track.id)
                onPick()
            },
        )
    }
}

private fun switchSourceAudio(
    ordinal: Int,
    p: EmbeddedPlayer,
    playback: PlaybackSession,
    session: AppSession,
    scope: CoroutineScope,
) {
    if (ordinal == playback.selectedAudioTrack) return
    val ps = p.state
    if (playback.currentTarget?.streamBaseUrl == null) {
        ps.audio.getOrNull(ordinal)?.id?.let { id ->
            p.setAudio(id)
            playback.selectedAudioTrack = ordinal
        }
    } else {
        playback.current?.let { item ->
            val resume = PlaybackResume(ps.position, ps.paused)
            scope.launch {
                playback.audioSwitching = true
                try {
                    session.io { playback.resolveAudioTarget(item, ordinal) }
                        .onSuccess { next ->
                            if (playback.current?.assetId == item.assetId) {
                                p.load(next.url, playback.title(item), next.durationSeconds, next.streamBaseUrl, resume)
                                playback.useTarget(next)
                                playback.refreshSubtitles()
                            }
                        }
                        .onFailure {
                            session.toast(
                                Toast.Kind.ERROR,
                                "Couldn't switch audio",
                                it.message ?: "Playback plan failed",
                            )
                        }
                } finally {
                    playback.audioSwitching = false
                }
            }
        }
    }
}

/** The caption tracks: Off, then each by language name, title and origin (sidecar or in the container). */
@Composable
private fun CaptionMenuItems(ps: PlayerState, onPick: (Int?) -> Unit) {
    Text(
        "Captions",
        style = MaterialTheme.typography.labelSmall,
        color = Tokens.textMuted,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    DropdownMenuItem(text = {
        Text(
            "Off",
            color = if (ps.subtitleId ==
                null
            ) {
                LocalMediaTheme.current.accentGradientEnd
            } else {
                Tokens.textPrimary
            },
        )
    }, onClick = {
        onPick(null)
    })
    for (t in ps.subtitles) {
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        trackLabel(t),
                        color = if (t.id ==
                            ps.subtitleId
                        ) {
                            LocalMediaTheme.current.accentGradientEnd
                        } else {
                            Tokens.textPrimary
                        },
                    )
                    Text(
                        listOfNotNull(
                            t.title,
                            if (t.external) "sidecar file" else "in the container",
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.textMuted,
                    )
                }
            },
            onClick = {
                onPick(t.id)
            },
        )
    }
}

/** Mute, and the volume slider (to 130%, as mpv allows) in the media's accent. */
@Composable
internal fun VolumeControl(ps: PlayerState, p: EmbeddedPlayer) {
    val theme = LocalMediaTheme.current
    IconButtonRound(
        if (ps.muted ||
            ps.volume <= 0
        ) {
            HeyarrIcons.VolumeOff
        } else {
            HeyarrIcons.Volume
        },
        if (ps.muted) "Unmute (M)" else "Mute (M)",
        { p.toggleMute() },
        size = 32.dp,
    )
    Slider(
        value = (ps.volume / 100.0).toFloat().coerceIn(0f, 1.3f),
        onValueChange = {
            p.setVolume(it * 100.0)
        },
        valueRange = 0f..1.3f,
        modifier = Modifier.width(110.dp).semantics {
            contentDescription =
                "Volume ${ps.volume.toInt()}%"
        },
        colors = SliderDefaults.colors(
            thumbColor = theme.accent,
            activeTrackColor = theme.accent,
            inactiveTrackColor = Tokens.surface3,
        ),
    )
}

/** Starting, buffering or finished — or nothing while it simply plays. */
@Composable
internal fun PlaybackStatusText(ps: PlayerState) {
    // Warm-up and mid-stream stalls read differently: "starting…" is the first
    // fill (nothing shown yet); "buffering…" is a cache stall once it is going.
    if (ps.warmingUp) {
        Text("starting…", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
    } else if (ps.buffering || ps.stalled) {
        Text("buffering…", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
    }
    if (ps.eof) Text("finished", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
}

/** Popped out: playback runs in mpv's own window, and this is what the picture area says meanwhile. */
@Composable
internal fun PopoutNotice(modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            HeyarrIcons.OpenExternal,
            contentDescription = null,
            tint = Tokens.textMuted,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "Playing in a separate mpv window",
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.textPrimary,
        )
        Text(
            "The controls below still drive it; closing that window brings playback back in here.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}
