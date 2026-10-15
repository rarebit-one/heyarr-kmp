package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.PathEffect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.mcp.RendererListResult
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.playback.EmbeddedPlayer
import one.rarebit.heyarr.desktop.playback.MpvTrack
import one.rarebit.heyarr.desktop.playback.PlayerState
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.rememberArtwork
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Artwork
import one.rarebit.heyarr.desktop.ui.components.VideoSurface
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.desktop.ui.isListening
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens
import java.awt.GraphicsEnvironment

/** Per-visit state of the player screen; playback itself lives in [AppSession.playback]. */
class PlayerScreenState {
    var rendererList by mutableStateOf<RendererListResult?>(null)
    var castOpen by mutableStateOf(false)
}

/** The keys the player answers to, by Compose key; the window-level dispatcher maps AWT codes onto them. */
object PlayerKeys {
    fun handle(
        key: Key,
        p: EmbeddedPlayer,
        onFullscreen: () -> Unit,
        onBack: () -> Unit,
        fullscreen: Boolean,
    ): Boolean = when (key) {
        Key.Spacebar, Key.K -> {
            p.togglePause()
            true
        }

        Key.DirectionLeft, Key.J -> {
            p.seekBy(-10.0)
            true
        }

        Key.DirectionRight, Key.L -> {
            p.seekBy(10.0)
            true
        }

        Key.DirectionUp -> {
            p.setVolume(p.state.volume + 5)
            true
        }

        Key.DirectionDown -> {
            p.setVolume(p.state.volume - 5)
            true
        }

        Key.F -> {
            onFullscreen()
            true
        }

        Key.M -> {
            p.toggleMute()
            true
        }

        Key.S, Key.C -> {
            p.cycleSubtitle()
            true
        }

        Key.Escape -> {
            if (fullscreen) onFullscreen() else onBack()
            true
        }

        else -> false
    }

    fun fromAwt(code: Int): Key? = when (code) {
        java.awt.event.KeyEvent.VK_SPACE -> Key.Spacebar
        java.awt.event.KeyEvent.VK_LEFT -> Key.DirectionLeft
        java.awt.event.KeyEvent.VK_RIGHT -> Key.DirectionRight
        java.awt.event.KeyEvent.VK_UP -> Key.DirectionUp
        java.awt.event.KeyEvent.VK_DOWN -> Key.DirectionDown
        java.awt.event.KeyEvent.VK_F -> Key.F
        java.awt.event.KeyEvent.VK_M -> Key.M
        java.awt.event.KeyEvent.VK_S -> Key.S
        java.awt.event.KeyEvent.VK_C -> Key.C
        java.awt.event.KeyEvent.VK_K -> Key.K
        java.awt.event.KeyEvent.VK_J -> Key.J
        java.awt.event.KeyEvent.VK_L -> Key.L
        java.awt.event.KeyEvent.VK_ESCAPE -> Key.Escape
        else -> null
    }
}

/**
 * The player screen: the picture (mpv's frames, a Compose element), our transport, and
 * what's next. Fullscreen the transport floats over the picture and fades a few seconds
 * after the pointer or keyboard last moved; the picture itself never moves. Playback
 * belongs to the session, so leaving this screen keeps it going in the now-playing bar;
 * Back is just Back.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    session: AppSession,
    route: Route.Player,
    state: PlayerScreenState,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpen: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val playback = session.playback
    // The route carries the item for the first frame; the session takes over once it has it.
    val item = playback.current ?: route
    val type = item.typeHint
    val p = playback.player
    val ps = p.state
    val headless = remember { GraphicsEnvironment.isHeadless() }
    val reduceMotion = LocalAppearance.current.reduceMotion
    val controlsEnter = if (reduceMotion) EnterTransition.None else fadeIn()
    val controlsExit = if (reduceMotion) ExitTransition.None else fadeOut()

    DisposableEffect(Unit) {
        playback.onPlayerScreen = true
        onDispose {
            playback.onPlayerScreen = false
            if (fullscreen) onFullscreen(false)
        }
    }
    LaunchedEffect(item.workId) {
        val a = session.api ?: return@LaunchedEffect
        session.io { a.assets(item.workId) }.onSuccess { list ->
            playback.queue = Series.seasons(list).flatMap { it.episodes }
            playback.refreshSubtitles() // now the episode→sidecar map is known, attach captions to the running file
        }
    }
    // Fullscreen: the transport shows on any activity and fades 3.5 s later while playing.
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(fullscreen, ps.paused, playback.controlsTick) {
        controlsVisible = true
        if (fullscreen && !ps.paused) {
            delay(3500)
            controlsVisible = false
        }
    }
    val overlayShowing = !fullscreen || controlsVisible || ps.paused
    fun toggleFullscreen() {
        onFullscreen(!fullscreen)
        playback.wakeControls()
    }
    val blankCursor = remember {
        runCatching {
            PointerIcon(
                java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
                    java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                    java.awt.Point(0, 0),
                    "blank",
                ),
            )
        }.getOrDefault(PointerIcon.Default)
    }

    MediaScope(type) {
        val theme = LocalMediaTheme.current

        // ── the picture: a tap pauses, a double tap toggles fullscreen ──
        @Composable
        fun Picture(m: Modifier) {
            Box(
                m.background(Color.Black).pointerInput(playback.popout) {
                    detectTapGestures(onTap = {
                        if (!playback.popout) p.togglePause()
                    }, onDoubleTap = { toggleFullscreen() })
                },
            ) {
                when {
                    type.isListening() -> AudioPlayerArtwork(session, item)

                    headless -> {
                        val art by session.artwork.rememberArtwork(null)
                        Artwork(art, type, Modifier.fillMaxSize(), glyphSize = 64.dp)
                        Text(
                            "mpv renders here",
                            style = MaterialTheme.typography.labelMedium,
                            color = Tokens.textMuted,
                            modifier = Modifier.align(Alignment.Center).padding(top = 90.dp),
                        )
                    }

                    playback.popout -> PopoutNotice(Modifier.align(Alignment.Center))

                    else -> {
                        VideoSurface(p, Modifier.fillMaxSize())
                        // Loading cover: opaque over the picture until the first frame plays
                        // (warm-up) AND during a mid-stream stall (stuck once it has begun). It is
                        // honest about "playing but nothing on screen" and hides any pre-roll or
                        // stale frame rather than freezing on it.
                        if ((ps.warmingUp || ps.stalled) &&
                            playback.startError == null
                        ) {
                            StartingOverlay(if (ps.warmingUp) "Starting…" else "Buffering…")
                        }
                    }
                }
                if (playback.startError !=
                    null
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Notice(
                            "mpv could not start: ${playback.startError}",
                            tone = Tokens.danger,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
            }
        }

        // ── the transport: beneath the picture windowed, over it fullscreen ──
        @Composable
        fun Transport(overlay: Boolean) {
            Column(
                Modifier.fillMaxWidth().background(
                    if (overlay) Color.Transparent else Tokens.bgBase,
                ).padding(horizontal = 24.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SeekBar(ps, onSeek = { p.seekFraction(it.toDouble()) })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButtonRound(HeyarrIcons.Back10, "Back 10 seconds (←)", { p.seekBy(-10.0) }, size = 36.dp)
                    IconButtonRound(
                        if (ps.paused) HeyarrIcons.Play else HeyarrIcons.Pause,
                        if (ps.paused) "Play (space)" else "Pause (space)",
                        { p.togglePause() },
                        size = 48.dp,
                        filled = true,
                    )
                    IconButtonRound(HeyarrIcons.Forward10, "Forward 10 seconds (→)", { p.seekBy(10.0) }, size = 36.dp)
                    val next = playback.next()
                    if (next !=
                        null
                    ) {
                        IconButtonRound(HeyarrIcons.SkipNext, "Next: ${next.subtitle}", {
                            playback.play(next)
                        }, size = 36.dp)
                    }
                    IconButtonRound(HeyarrIcons.Stop, "Stop and close the player", {
                        playback.stop()
                        onBack()
                    }, size = 36.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${clock(ps.position)} / ${clock(ps.duration)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.textPrimary,
                    )
                    PlaybackStatusText(ps)
                    Spacer(Modifier.weight(1f))
                    TrackMenu(ps, p)
                    Spacer(Modifier.width(8.dp))
                    VolumeControl(ps, p)
                    IconButtonRound(
                        if (fullscreen) HeyarrIcons.FullscreenExit else HeyarrIcons.Fullscreen,
                        if (fullscreen) "Exit fullscreen (Esc)" else "Fullscreen (F)",
                        ::toggleFullscreen,
                        size = 40.dp,
                        filled = !fullscreen,
                    )
                }
            }
        }

        if (fullscreen) {
            Box(
                modifier.fillMaxSize().background(Color.Black)
                    .onPointerEvent(PointerEventType.Move) { playback.wakeControls() }
                    .pointerHoverIcon(
                        if (overlayShowing) PointerIcon.Default else blankCursor,
                        overrideDescendants = true,
                    ),
            ) {
                Picture(Modifier.fillMaxSize())
                AnimatedVisibility(
                    overlayShowing,
                    enter = controlsEnter,
                    exit = controlsExit,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)),
                        ).padding(horizontal = 24.dp, vertical = 16.dp).padding(bottom = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GhostButton("Exit fullscreen", { toggleFullscreen() }, icon = HeyarrIcons.FullscreenExit)
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Tokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.subtitle?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Tokens.textMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    overlayShowing,
                    enter = controlsEnter,
                    exit = controlsExit,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Box(
                        Modifier.fillMaxWidth().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))),
                        ).padding(top = 56.dp),
                    ) {
                        Transport(overlay = true)
                    }
                }
            }
            return@MediaScope
        }

        Column(modifier.fillMaxSize().background(Tokens.bgBase)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GhostButton(item.from, onBack, icon = HeyarrIcons.Back)
                Column(Modifier.weight(1f)) {
                    Text("PLAY HERE · MPV", style = MaterialTheme.typography.labelSmall, color = Tokens.accentGradEnd)
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 1)
                    }
                }
                SecondaryButton(if (playback.popout) "Pop back in" else "Pop out", {
                    scope.launch {
                        if (playback.popout) {
                            playback.popout = false
                            playback.pendingStart = true
                        } else {
                            playback.popout = true
                            val err = session.io {
                                if (p.isRunning) {
                                    p.switchTo(
                                        embedded = false,
                                    )
                                } else {
                                    playback.resolvePlaybackTarget(item).let { t ->
                                        p.start(
                                            false,
                                            t.url,
                                            playback.token,
                                            playback.title(item),
                                            t.durationSeconds,
                                            t.streamBaseUrl,
                                        )
                                    }
                                }
                            }.getOrNull()
                            if (err != null) {
                                session.toast(Toast.Kind.ERROR, "Couldn't pop out", err)
                                playback.popout = false
                                playback.pendingStart = true
                            }
                        }
                    }
                }, icon = if (playback.popout) HeyarrIcons.Fullscreen else HeyarrIcons.OpenExternal, compact = true)
                IconButtonRound(HeyarrIcons.Cast, "Play on a renderer", {
                    state.castOpen = !state.castOpen
                    if (state.rendererList == null) {
                        scope.launch {
                            session.api?.let { a ->
                                session.io { a.renderers() }.onSuccess { state.rendererList = it }
                            }
                        }
                    }
                })
            }
            if (state.castOpen) Box(Modifier.padding(horizontal = 24.dp)) { CastRow(session, state, item) }

            // Keep the picture inside the height left after the header, the transport and
            // the up-next card. Sized from the width alone, a maximized window's 16:9 picture
            // runs past the bottom edge and takes the transport with it.
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val pictureWidth = minOf(maxWidth, maxHeight * (16f / 9f))
                Picture(Modifier.width(pictureWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard)))
            }
            Transport(overlay = false)

            // ── up next: the one episode after this one ──
            val next = playback.next()
            if (!fullscreen && next != null) {
                Spacer(Modifier.height(8.dp))
                val ep = playback.queue.firstOrNull { it.asset.id == next.assetId }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionHeader("Up next")
                    val thumb by rememberCover(session, MediaType.SERIES, "", ep?.thumbnailPath).let { c ->
                        androidx.compose.runtime.derivedStateOf { c.value.bitmap }
                    }
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().clip(
                            RoundedCornerShape(Tokens.radiusInput),
                        ).background(
                            Tokens.surface1,
                        ).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput))
                            .clickable(interactionSource = interaction, indication = null) {
                                playback.play(next)
                            }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard))) {
                            Artwork(thumb, MediaType.SERIES, Modifier.fillMaxSize(), glyphSize = 18.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            ep?.code?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = theme.accentGradientEnd)
                            }
                            Text(
                                ep?.title ?: next.subtitle ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                color = Tokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ep?.asset?.sizeBytes?.let {
                                Text(
                                    one.rarebit.heyarr.desktop.library.PrimaryAsset.formatBytes(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Tokens.textMuted,
                                )
                            }
                        }
                        SecondaryButton("Play next", {
                            playback.play(next)
                        }, icon = HeyarrIcons.SkipNext, compact = true)
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components use PascalCase.
private fun AudioPlayerArtwork(session: AppSession, item: Route.Player) {
    val work by produceState<WorkDetail?>(null, item.workId, session.generation) {
        value = session.io { session.api?.work(item.workId) }.getOrNull()
    }
    val cover by rememberCover(
        session,
        item.typeHint,
        item.title,
        work?.artworkPath,
        work?.work?.year,
        work?.work?.artist ?: work?.work?.author,
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Artwork(
            cover.bitmap,
            item.typeHint,
            Modifier.size(minOf(maxWidth, maxHeight)),
            contentDescription = "Cover for ${item.title}",
            seed = item.title,
        )
    }
}

/** `eng` → English, else the raw code, else the title, else the id. */
internal fun trackLabel(t: MpvTrack): String {
    val lang = t.lang?.let { code -> languageName(code) }
    return lang ?: t.title ?: "Track ${t.id}"
}

private val LANGUAGES: Map<String, String> by lazy {
    java.util.Locale.getAvailableLocales().filter { it.language.isNotBlank() }.flatMap { l ->
        listOf(l.language, runCatching { l.isO3Language }.getOrDefault("")).filter { it.isNotBlank() }.map {
            it.lowercase() to
                l.getDisplayLanguage(java.util.Locale.ENGLISH)
        }
    }.toMap()
}

internal fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    return LANGUAGES[code.lowercase().substringBefore('-')]?.takeIf { it.isNotBlank() }
        ?: code.uppercase().takeIf { code.length in 2..3 }
}

/**
 * The scrubber before the stream is seekable: an inert dashed rail, so it reads as "not ready"
 * rather than a playhead frozen at zero. It does not sweep: a moving bar reads as measured
 * progress, and the wait has none (the overlay's "Starting…" says what is happening).
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun WarmUpRail() {
    Canvas(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(4.dp)
            .semantics { contentDescription = "Starting…" },
    ) {
        val y = size.height / 2f
        drawLine(
            Tokens.border,
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = size.height / 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(WARM_UP_DASH_PX, WARM_UP_DASH_PX)),
        )
    }
}

private const val WARM_UP_DASH_PX = 6f

@Composable
private fun SeekBar(ps: PlayerState, onSeek: (Float) -> Unit) {
    val theme = LocalMediaTheme.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val buffered = ps.bufferedFraction
    // Fixed height so the row does not jump when the warm-up bar swaps for the Slider.
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
        if (ps.warmingUp) {
            WarmUpRail()
        } else {
            // Behind the Slider: the inactive rail, and over it a lighter band as far
            // as the stream has cached ahead. Inset by the thumb radius so it lines up
            // with the track the Slider paints the played portion onto. The Slider's
            // own inactive track is transparent (so this shows through) but it still
            // owns the active track and the thumb, so dragging stays pixel-accurate.
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
                    dragging?.let(onSeek)
                    dragging = null
                },
                modifier = Modifier.fillMaxWidth().height(24.dp).semantics {
                    contentDescription =
                        "Position ${clock(
                            ps.position,
                        )} of ${clock(ps.duration)}, buffered ${(buffered * 100).toInt()} percent"
                },
                colors = SliderDefaults.colors(
                    thumbColor = theme.accentGradientEnd,
                    activeTrackColor = theme.accent,
                    inactiveTrackColor = Color.Transparent,
                ),
                enabled =
                ps.duration > 0 && ps.hasStarted,
            )
        }
    }
}

/** The picture's warm-up cover: an opaque starting state until the first frame plays. */
@Composable
private fun StartingOverlay(label: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(
                color = LocalMediaTheme.current.accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(40.dp),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted)
        }
    }
}

@Composable
private fun CastRow(session: AppSession, state: PlayerScreenState, item: Route.Player) {
    val scope = rememberCoroutineScope()
    Panel("Play on a renderer", trailing = { GhostButton("Close", { state.castOpen = false }) }) {
        val rl = state.rendererList
        when {
            rl == null -> Skeleton(Modifier.fillMaxWidth().height(36.dp))

            rl.renderers.isEmpty() -> Text(
                "No renderers found — a device that is off is not listed.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            else -> RendererGroups(rl) { x ->
                val a = session.api ?: return@RendererGroups
                state.castOpen = false
                Caster(session, a, x, before = { session.playback.player.pause() }).cast(item.assetId)
            }
        }
        GhostButton("Search the network again", {
            scope.launch {
                session.api?.let { a ->
                    session.io { a.renderers(refresh = true) }.onSuccess { state.rendererList = it }
                }
            }
        })
    }
}

/** `#RRGGBB` for mpv's OSC options. */
internal fun accentHex(c: Color): String = "#%02X%02X%02X".format(
    (c.red * 255).toInt(),
    (c.green * 255).toInt(),
    (
        c.blue *
            255
        ).toInt(),
)

internal fun clock(s: Double): String {
    val t = s.toLong().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
