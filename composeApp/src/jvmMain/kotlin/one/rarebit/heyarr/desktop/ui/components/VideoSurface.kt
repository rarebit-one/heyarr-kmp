package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import one.rarebit.heyarr.desktop.state.AppSession
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.awt.GraphicsEnvironment

/**
 * The picture: mpv's latest frame, letterboxed into this box on black. A plain Compose
 * element — anything may be drawn over it — that repaints when a frame lands, without
 * recomposing anything (the frame is read inside the draw). Several may show the same
 * player at once (the player screen, the now-playing bar); each draws the same frame.
 */
@Composable
fun VideoSurface(player: one.rarebit.heyarr.desktop.playback.EmbeddedPlayer, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.Black)) {
        val renderer = player.renderer ?: return@Canvas
        drawIntoCanvas { canvas ->
            renderer.withFrame { f ->
                f ?: return@withFrame
                val scale = minOf(size.width / f.width, size.height / f.height)
                val dw = f.width * scale
                val dh = f.height * scale
                val left = (size.width - dw) / 2f
                val top = (size.height - dh) / 2f
                canvas.nativeCanvas.drawImageRect(f.image, Rect.makeWH(f.width.toFloat(), f.height.toFloat()), Rect.makeXYWH(left, top, dw, dh), SamplingMode.LINEAR, null, true)
            }
        }
    }
}

/**
 * Starts and re-hosts mpv for the session: when an item needs a fresh start, or when
 * playback moves between the app and a pop-out window. Draws nothing; it lives at the
 * root so playback outlives any screen.
 */
@Composable
fun PlaybackHost(session: AppSession) {
    val playback = session.playback
    if (GraphicsEnvironment.isHeadless()) return
    val item = playback.current ?: return
    LaunchedEffect(item.assetId, playback.player.state.eof) {
        if (playback.loadedAssetId == item.assetId && playback.player.state.eof && playback.audioQueue.isNotEmpty()) playback.next()?.let(playback::play)
    }
    LaunchedEffect(item.assetId, playback.pendingStart) {
        if (!playback.pendingStart && playback.player.isRunning) return@LaunchedEffect
        val embedded = !playback.popout
        val err = session.io {
            // resolvePlaybackTarget asks the server's playback plan (a network call), so
            // it runs here inside io, off the UI thread, not before the block.
            if (playback.player.isRunning && playback.loadedAssetId == item.assetId) playback.player.switchTo(embedded)
            else if (playback.player.isRunning) playback.resolvePlaybackTarget(item).let { t ->
                playback.player.load(t.url, playback.title(item), t.durationSeconds, t.streamBaseUrl); null
            }
            else playback.resolvePlaybackTarget(item).let { t -> playback.player.start(embedded, t.url, playback.token, playback.title(item), t.durationSeconds, t.streamBaseUrl) }
        }.fold(onSuccess = { it }, onFailure = { it.message ?: "Could not start playback" })
        playback.pendingStart = false
        playback.startError = err
        if (err == null) { playback.loadedAssetId = item.assetId; playback.player.play(); playback.refreshSubtitles() }
    }
}
