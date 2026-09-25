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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.playback.NowPlaying
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

// The pieces of [PlayerScreen]: the immersive (fullscreen / landscape) frame, and the
// portrait layout's top bar, picture, inline controls and "Up next"; plus the transport's
// seek rail and the captions menu.

/** Fullscreen or landscape video: the picture edge to edge, controls that fade while playing; a tap toggles them. */
@UnstableApi
@Composable
internal fun ImmersivePlayer(
    video: VideoSession,
    state: PlayerScreenState,
    onBack: () -> Unit,
    modifier: Modifier,
    transport: @Composable () -> Unit,
) {
    val item = video.current ?: return
    val ps = video.state
    val fullscreen = video.fullscreen
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
                transport()
            }
        }
    }
}

/** Open or close the "Play on a renderer" row, fetching the renderers the first time. */
internal fun toggleCastRow(session: AppSession, state: PlayerScreenState, scope: CoroutineScope) {
    state.castOpen = !state.castOpen
    if (state.renderers ==
        null
    ) {
        scope.launch { session.io { session.api.renderers() }.onSuccess { state.renderers = it } }
    }
}

/** The portrait layout's top bar: Back, the title, cast, and fullscreen for video. */
@Composable
internal fun PlayerTopBar(
    title: String,
    isVideo: Boolean,
    onBack: () -> Unit,
    onCast: () -> Unit,
    onFullscreen: () -> Unit,
) {
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
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButtonRound(Icons.Rounded.Cast, "Play on a renderer", onCast, size = 40.dp)
        if (isVideo) {
            IconButtonRound(Icons.Rounded.Fullscreen, "Fullscreen", onFullscreen, size = 40.dp, filled = true)
        }
    }
}

/** The picture (or the art for audio), with the player's error over it when there is one. */
@UnstableApi
@Composable
internal fun PlayerPicture(video: VideoSession, item: NowPlaying, type: MediaType) {
    val ps = video.state
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
}

/** The portrait layout's controls: transport, the clock line with captions, and why playback looks the way it does. */
@UnstableApi
@Composable
internal fun InlineControls(video: VideoSession, item: NowPlaying, onNext: (QueueEntry) -> Unit, onStop: () -> Unit) {
    val ps = video.state
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
        PlayerClockRow(video)
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
}

/** Position / duration, buffering and finished flags, and the captions menu. */
@UnstableApi
@Composable
private fun PlayerClockRow(video: VideoSession) {
    val ps = video.state
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
}

/** "Up next": the queue's next entry, one tap to play it. */
@Composable
internal fun UpNext(next: QueueEntry, onNext: (QueueEntry) -> Unit) {
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
