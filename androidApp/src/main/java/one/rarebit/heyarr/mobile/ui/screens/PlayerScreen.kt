package one.rarebit.heyarr.mobile.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.playback.QueueEntry
import one.rarebit.heyarr.mobile.playback.VideoSession
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Artwork
import one.rarebit.heyarr.mobile.ui.components.clockShort
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope

/** Per-visit state of the player screen; playback itself lives in [VideoSession]. */
class PlayerScreenState {
    var renderers by mutableStateOf<List<Renderer>?>(null)
    var castOpen by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
}

/**
 * The in-app player: the picture (a Media3 `PlayerView` attached to the session's
 * ExoPlayer — no built-in controller, ours below), the transport, captions as a menu
 * with language names, cast to a renderer, and what's next. Playback belongs to the
 * session, so Back keeps it going in the now-playing bar; fullscreen is a first-class
 * toggle (landscape, system bars hidden, controls that fade while playing).
 */
@UnstableApi
@Composable
fun PlayerScreen(
    session: AppSession,
    video: VideoSession,
    state: PlayerScreenState,
    onBack: () -> Unit,
    onNext: (QueueEntry) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = video.current ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val ps = video.state
    val type = MediaType.from(item.kind ?: "movie")
    val fullscreen = video.fullscreen
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val immersive = fullscreen || (landscape && item.target.isVideo)
    val scope = rememberCoroutineScope()

    BackHandler { if (fullscreen) video.fullscreen = false else onBack() }
    LaunchedEffect(immersive, ps.paused, state.controlsVisible) {
        if (immersive && !ps.paused && state.controlsVisible) {
            delay(3500)
            state.controlsVisible = false
        }
        if (!immersive) state.controlsVisible = true
    }

    MediaScope(type) {
        if (immersive) {
            Box(
                modifier.fillMaxSize().background(Color.Black).clickable(
                    interactionSource = remember {
                        MutableInteractionSource()
                    },
                    indication = null,
                ) {
                    state.controlsVisible =
                        !state.controlsVisible
                },
            ) {
                Surface(video, item.target, Modifier.fillMaxSize())
                if (state.controlsVisible || ps.paused) {
                    Row(
                        Modifier.align(
                            Alignment.TopStart,
                        ).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButtonRound(Icons.Rounded.ArrowBack, if (fullscreen) "Exit fullscreen" else "Back", {
                            if (fullscreen) {
                                video.fullscreen =
                                    false
                            } else {
                                onBack()
                            }
                        }, size = 40.dp)
                        Text(
                            item.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TrackMenu(video)
                    }
                    Column(
                        Modifier.align(
                            Alignment.BottomCenter,
                        ).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Transport(video, ps, item.target, onNext = {
                            video.next()?.let(onNext)
                        }, onStop = onStop, fullscreenToggle = {
                            video.fullscreen =
                                !fullscreen
                        }, fullscreen = fullscreen)
                    }
                }
            }
            return@MediaScope
        }
        Column(modifier.fillMaxSize().background(Tokens.bgBase).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(
                    WindowInsets.safeDrawing,
                ).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton("Back", onBack, icon = Icons.Rounded.ArrowBack)
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButtonRound(Icons.Rounded.Cast, "Play on a renderer", {
                    state.castOpen = !state.castOpen
                    if (state.renderers ==
                        null
                    ) {
                        scope.launch { session.io { session.api.renderers() }.onSuccess { state.renderers = it } }
                    }
                }, size = 40.dp)
                if (item.target.isVideo) {
                    IconButtonRound(Icons.Rounded.Fullscreen, "Fullscreen", {
                        video.fullscreen =
                            true
                    }, size = 40.dp, filled = true)
                }
            }
            if (state.castOpen) {
                Box(Modifier.padding(horizontal = 12.dp)) {
                    CastRow(session, state, item.assetId, video)
                }
            }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).let {
                    if (item.target.isVideo) {
                        it.aspectRatio(16f / 9f)
                    } else {
                        it.height(200.dp)
                    }
                }.clip(RoundedCornerShape(Tokens.radiusCard)).background(Color.Black),
            ) {
                if (item.target.isVideo) {
                    Surface(video, item.target, Modifier.fillMaxSize())
                } else {
                    Artwork(item.artworkUrl, type, Modifier.fillMaxSize(), glyphSize = 64.dp)
                }
                if (ps.error !=
                    null
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Notice(ps.error, tone = Tokens.danger, modifier = Modifier.padding(24.dp))
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Transport(video, ps, item.target, onNext = {
                    video.next()?.let(onNext)
                }, onStop = onStop, fullscreenToggle = {
                    video.fullscreen =
                        true
                }, fullscreen = false)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${clockShort(ps.positionMs)} / ${clockShort(ps.durationMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.textPrimary,
                    )
                    if (ps.buffering) {
                        Text(
                            "buffering…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.textMuted,
                        )
                    }
                    if (ps.ended) {
                        Text(
                            "finished",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.textMuted,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TrackMenu(video)
                }
                val banner =
                    ps.issue
                        ?: (
                            if (ps.noFrame) {
                                one.rarebit.heyarr.mobile.playback.PlaybackDiagnostics.noFrameMessage(
                                    item.target,
                                )
                            } else {
                                null
                            }
                            )
                        ?: item.banner
                        ?: streamNote(item.target)
                if (banner !=
                    null
                ) {
                    Notice(banner, tone = if (ps.issue != null || ps.noFrame) Tokens.warning else Tokens.slate)
                }
            }
            val next = video.next()
            if (next != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader("Up next")
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().clip(
                            RoundedCornerShape(Tokens.radiusInput),
                        ).background(
                            Tokens.surface1,
                        ).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput))
                            .clickable(interactionSource = interaction, indication = null) {
                                onNext(next)
                            }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard))) {
                            Artwork(next.thumbnailPath, MediaType.SERIES, Modifier.fillMaxSize(), glyphSize = 18.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                next.subtitle ?: next.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = Tokens.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            next.sizeBytes?.let {
                                Text(
                                    WorkAsset.formatBytes(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Tokens.textMuted,
                                )
                            }
                        }
                        IconButtonRound(Icons.Rounded.SkipNext, "Play next", {
                            onNext(next)
                        }, size = 40.dp, filled = true)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@UnstableApi
@Composable
private fun Surface(video: VideoSession, target: PlaybackTarget, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                player = video.player
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> if (view.player !== video.player) view.player = video.player },
        modifier = modifier.semantics { contentDescription = if (target.isVideo) "Video" else "Audio" },
    )
}

@UnstableApi
@Composable
private fun Transport(
    video: VideoSession,
    ps: VideoSession.State,
    target: PlaybackTarget,
    onNext: (() -> Unit)?,
    onStop: () -> Unit,
    fullscreenToggle: () -> Unit,
    fullscreen: Boolean,
) {
    val theme = LocalMediaTheme.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val seekable = ps.durationMs > 0 && (target.seekable || target.restartSeekable)
    val buffered = ps.bufferedFraction
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Behind the Slider: the inactive rail, and over it a lighter band as far as
        // the stream has cached ahead (mirrors heyarr-desktop's SeekBar). Inset by the
        // thumb radius so it lines up with the track the Slider paints the played
        // portion onto; the Slider's own inactive track is transparent so this shows
        // through, but it still owns the active track and the thumb, so dragging stays
        // pixel-accurate.
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(4.dp)) {
            val y = size.height / 2f
            drawLine(
                Tokens.surface3,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            if (buffered > 0f) {
                drawLine(
                    theme.accent.copy(alpha = 0.35f),
                    Offset(0f, y),
                    Offset(size.width * buffered, y),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
        }
        Slider(
            value = dragging ?: ps.fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { video.seekFraction(it) }
                dragging = null
            },
            modifier = Modifier.fillMaxWidth().height(24.dp).semantics {
                contentDescription =
                    "Position ${clockShort(
                        ps.positionMs,
                    )} of ${clockShort(ps.durationMs)}, buffered ${(buffered * 100).toInt()} percent"
            },
            colors = SliderDefaults.colors(
                thumbColor = theme.accentGradientEnd,
                activeTrackColor = theme.accent,
                inactiveTrackColor = Color.Transparent,
            ),
            enabled = seekable,
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButtonRound(Icons.Rounded.Replay10, "Back 10 seconds", {
            video.seekBy(-10.0)
        }, size = 40.dp, enabled = seekable)
        IconButtonRound(if (ps.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (ps.paused) "Play" else "Pause", {
            video.togglePause()
        }, size = 52.dp, filled = true)
        IconButtonRound(Icons.Rounded.Forward10, "Forward 10 seconds", {
            video.seekBy(10.0)
        }, size = 40.dp, enabled = seekable)
        if (onNext != null &&
            video.next() != null
        ) {
            IconButtonRound(Icons.Rounded.SkipNext, "Next: ${video.next()?.subtitle ?: ""}", onNext, size = 40.dp)
        }
        IconButtonRound(Icons.Rounded.Stop, "Stop and close the player", onStop, size = 40.dp)
        Spacer(Modifier.weight(1f))
        if (target.isVideo) {
            IconButtonRound(
                if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (fullscreen) "Exit fullscreen" else "Fullscreen",
                fullscreenToggle,
                size = 40.dp,
                filled = !fullscreen,
            )
        }
    }
}

/** Captions as a menu: Off, then each track by language name (heyarr's `mov_text` tracks surface through Media3). */
@UnstableApi
@Composable
private fun TrackMenu(video: VideoSession) {
    val ps = video.state
    var open by remember { mutableStateOf(false) }
    val current = ps.textTracks.firstOrNull { it.id == ps.selectedTrackId }
    Box {
        SecondaryButton(if (ps.textTracks.isEmpty()) "No captions" else current?.label ?: "Captions off", {
            open = !open
        }, icon = Icons.Rounded.ClosedCaption, compact = true, enabled = ps.textTracks.isNotEmpty())
        DropdownMenu(expanded = open, onDismissRequest = {
            open = false
        }, modifier = Modifier.background(Tokens.surface3)) {
            Text(
                "Captions",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenuItem(text = {
                Text(
                    "Off",
                    color = if (ps.selectedTrackId ==
                        null
                    ) {
                        LocalMediaTheme.current.accentGradientEnd
                    } else {
                        Tokens.textPrimary
                    },
                )
            }, onClick = {
                video.selectText(null)
                open =
                    false
            })
            for (t in ps.textTracks) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                t.label,
                                color = if (t.id ==
                                    ps.selectedTrackId
                                ) {
                                    LocalMediaTheme.current.accentGradientEnd
                                } else {
                                    Tokens.textPrimary
                                },
                            )
                            t.language?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                            }
                        }
                    },
                    onClick = {
                        video.selectText(t)
                        open = false
                    },
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun CastRow(session: AppSession, state: PlayerScreenState, assetId: String?, video: VideoSession) {
    val scope = rememberCoroutineScope()
    Panel("Play on a renderer", trailing = { GhostButton("Close", { state.castOpen = false }) }) {
        val r = state.renderers
        when {
            assetId == null -> Text(
                "This item has no asset id to hand to a renderer.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            r == null -> Skeleton(Modifier.fillMaxWidth().height(36.dp))

            r.isEmpty() -> Text(
                "No renderers found — a device that is off is not listed.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            else -> Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (x in r) {
                    FilterChip(x.name, false, {
                        state.castOpen = false
                        scope.launch {
                            video.pause()
                            session.io { session.api.playHere(assetId, x.name, x.udn) }.onSuccess { res ->
                                when (res) {
                                    is McpResult.Ok -> session.toast(
                                        Toast.Kind.SUCCESS,
                                        "Playing on ${x.name}",
                                        video.current?.title,
                                    )

                                    is McpResult.Refused -> session.refused(res)
                                }
                            }
                        }
                    }, icon = Icons.Rounded.Cast)
                }
            }
        }
    }
}

private fun streamNote(target: PlaybackTarget): String? {
    if (target.origin != PlaybackTarget.Origin.STREAM) return null
    val why = target.reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    val seek = if (target.restartSeekable) " Seeking restarts it from the new point." else " No seeking."
    return "Playing a phone-friendly stream from the server$why.$seek"
}
