package one.rarebit.heyarr.mobile

import android.Manifest
import android.app.ActivityManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.playback.VideoSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The film in the background, on a device (#231). A Nothing Phone back from Recents found its
 * hardware decoder reclaimed and the player driving it anyway; Play could not get out of the
 * error. Only a device has the codec, the surface and the activity lifecycle, so this plays a
 * real H.264 clip (encoded here, served over local HTTP through the app's own data source)
 * through [VideoSession] in a `PlayerView`, presses HOME and comes back.
 *
 * What it cannot show: a HARDWARE decoder being reclaimed by another app. The emulator's
 * decoder is software and nothing competes for it, which is why the failure is forced
 * through [VideoSession.failForTest] for the recovery cases.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoBackgroundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val clip = File(ctx.cacheDir, "smoke/clip.mp4")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var server: ClipServer
    private lateinit var video: VideoSession
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun playAClip() {
        clip.parentFile?.mkdirs()
        writeClip(clip)
        server = ClipServer(clip.readBytes())
        instrumentation.runOnMainSync { video = VideoSession(ctx, OkHttpClient(), scope) }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            // Wired as MainActivity wires it, and shown as the player screen shows it.
            activity.lifecycle.addObserver(video.lifecycleObserver { activity.isChangingConfigurations })
            activity.setContent {
                AndroidView(
                    factory = { PlayerView(it).apply { useController = false } },
                    update = { if (it.player !== video.player) it.player = video.player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        instrumentation.runOnMainSync {
            video.load(
                NowPlaying(
                    target = PlaybackTarget(server.url, Credential.Guest, isVideo = true, mimeType = "video/mp4"),
                    title = "clip",
                ),
            )
        }
    }

    @After fun stop() {
        instrumentation.runOnMainSync { video.stop() }
        scenario.close()
        server.close()
        scope.cancel()
    }

    @Test fun aFilmPausesInTheBackgroundAndCarriesOnFromTheSamePlaceOnReturn() {
        awaitPlayingPast(PLAYED_BEFORE_MS)
        var task = 0
        scenario.onActivity { task = it.taskId }

        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await("the player let go of its decoder in the background") {
            video.player == null && video.state.paused
        }
        val left = main { video.state.positionMs }
        // A while behind other apps: nothing moves, nothing is held.
        SystemClock.sleep(BACKGROUND_MS)
        assertEquals("paused where it was left", left, main { video.state.positionMs })
        assertNull("no player, so no codec, while in the background", main { video.player })

        backFromRecents(task)
        await("playing again on return") { video.player?.isPlaying == true }
        // Its own clock, not the held scrubber: it sought back to where it was, not to 0.
        val resumedAt = main { video.player!!.currentPosition }
        assertTrue("resumed at $resumedAt, left at $left", resumedAt >= left - SEEK_SLACK_MS)
        awaitPlayingPast(left + PLAYED_AFTER_MS)
        assertNull("no error on return", main { video.state.error })
    }

    @Test fun aDecoderFailureIsRetriedOnceByItself() {
        awaitPlayingPast(PLAYED_BEFORE_MS)
        val failedAt = main { video.state.positionMs }
        main { video.failForTest(decoderFailure()) }
        await("re-prepared and playing again") { video.player?.isPlaying == true && video.state.error == null }
        assertTrue("from where it failed", main { video.player!!.currentPosition } >= failedAt - SEEK_SLACK_MS)
    }

    @Test fun aSecondFailureIsShownInFullAndPlayRecoversIt() {
        awaitPlayingPast(PLAYED_BEFORE_MS)
        main { video.failForTest(decoderFailure()) }
        await("the automatic retry is playing") { video.player?.isPlaying == true }
        main { video.failForTest(decoderFailure()) }
        await("the second failure in a row is left up") { video.state.error != null }
        val error = main { video.state.error!! }
        assertTrue("the whole text, code and detail: $error", error.endsWith("ERROR_CODE_DECODING_FAILED: forced"))
        assertEquals("a dead player until Play", Player.STATE_IDLE, main { video.player!!.playbackState })
        val stoppedAt = main { video.state.positionMs }

        // The bug: Play did nothing here.
        main { video.togglePause() }
        await("Play re-prepared it") { video.player?.isPlaying == true && video.state.error == null }
        assertTrue("where it stopped", main { video.player!!.currentPosition } >= stoppedAt - SEEK_SLACK_MS)
    }

    /**
     * Bring the task back to the front, as Recents does. ActivityScenario's own way back is
     * an activity start, which Android refuses from an app sent to the background, so this
     * borrows the shell's right to reorder tasks for the one call.
     */
    private fun backFromRecents(task: Int) {
        val ui = instrumentation.uiAutomation
        ui.adoptShellPermissionIdentity(Manifest.permission.REORDER_TASKS, START_FROM_BACKGROUND)
        try {
            instrumentation.runOnMainSync {
                ctx.getSystemService(ActivityManager::class.java).moveTaskToFront(task, 0)
            }
        } finally {
            ui.dropShellPermissionIdentity()
        }
        // ActivityScenario's state is read off the main thread.
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (scenario.state != Lifecycle.State.RESUMED) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("never came back to the front")
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun decoderFailure() = PlaybackException("forced", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    private fun awaitPlayingPast(positionMs: Long) = await("playing past ${positionMs}ms") {
        video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= positionMs
    }

    private fun <T> main(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!main(condition)) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("timed out waiting: $what (state ${main { video.state }})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    /** A moving bar, encoded to H.264 and muxed to MP4 by the device's own encoder. */
    private fun writeClip(file: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var frame = 0
        var done = false
        while (!done) {
            if (frame <= FRAMES) frame = feed(codec, frame)
            val o = codec.dequeueOutputBuffer(info, DEQUEUE_US)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
            } else if (o >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(o, false)
                done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            }
        }
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
    }

    /** Queue [frame] (or, past the last, the end of stream) if the encoder has room; the next frame to feed. */
    private fun feed(codec: MediaCodec, frame: Int): Int {
        val i = codec.dequeueInputBuffer(DEQUEUE_US)
        if (i < 0) return frame
        val pts = frame * 1_000_000L / FPS
        if (frame == FRAMES) {
            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
            paint(codec.getInputImage(i)!!, frame)
            codec.queueInputBuffer(i, 0, WIDTH * HEIGHT * 3 / 2, pts, 0)
        }
        return frame + 1
    }

    private fun paint(image: android.media.Image, frame: Int) {
        val y = image.planes[0]
        for (row in 0 until HEIGHT) {
            for (col in 0 until WIDTH) {
                val lit = (col + frame * BAR_STEP) % WIDTH < WIDTH / 4
                y.buffer.put(row * y.rowStride + col * y.pixelStride, if (lit) LUMA_HI else LUMA_LO)
            }
        }
        for (p in 1..2) {
            val plane = image.planes[p]
            for (row in 0 until HEIGHT / 2) {
                for (col in 0 until WIDTH / 2) plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, CHROMA)
            }
        }
    }

    /** Serves one file over loopback HTTP, with `Range` — what the blob endpoint does for the player. */
    private class ClipServer(private val bytes: ByteArray) : Closeable {
        // IPv4 by name: Android's getLoopbackAddress() is ::1, which a 127.0.0.1 URL never reaches.
        private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        val url = "http://$LOOPBACK:${socket.localPort}/clip.mp4"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(client) } }
                }
            }
        }

        private fun serve(client: Socket): Unit = client.use {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            reader.readLine() ?: return@use
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: return@use
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            val spec = range?.removePrefix("bytes=")?.split('-')
            val from = spec?.getOrNull(0)?.toIntOrNull() ?: 0
            val to = spec?.getOrNull(1)?.toIntOrNull() ?: (bytes.size - 1)
            val status = if (range != null) "206 Partial Content" else "200 OK"
            val head = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                append("Content-Length: ${to - from + 1}\r\n")
                if (range != null) append("Content-Range: bytes $from-$to/${bytes.size}\r\n")
                append("\r\n")
            }
            val out = client.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes, from, to - from + 1)
            out.flush()
        }

        override fun close() = socket.close()
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        const val FPS = 15
        const val FRAMES = FPS * 40 // forty seconds: room to play, leave, come back and play on
        const val BIT_RATE = 400_000
        const val DEQUEUE_US = 10_000L
        const val BAR_STEP = 4
        const val LUMA_HI: Byte = 235.toByte()
        const val LUMA_LO: Byte = 16
        const val CHROMA: Byte = 128.toByte()
        const val BACKLOG = 16
        const val LOOPBACK = "127.0.0.1"
        const val START_FROM_BACKGROUND = "android.permission.START_ACTIVITIES_FROM_BACKGROUND"
        const val PLAYED_BEFORE_MS = 3_000L
        const val PLAYED_AFTER_MS = 2_000L
        const val BACKGROUND_MS = 5_000L
        const val SEEK_SLACK_MS = 1_000L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
    }
}
