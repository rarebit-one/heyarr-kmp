package one.rarebit.heyarr.mobile

import android.Manifest
import android.app.ActivityManager
import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * What the playback tests on a device share (#231, #232): a real H.264 clip encoded by the
 * device's own encoder, served over loopback HTTP with `Range` as the blob endpoint serves it,
 * and the way back to the front from the background.
 */
object TestClip {
    /** A moving bar, [seconds] long, encoded to H.264 and muxed to MP4 by the device's own encoder. */
    fun write(file: File, seconds: Int = 40, width: Int = 320, height: Int = 240, fps: Int = 15) {
        file.parentFile?.mkdirs()
        Encoder(width, height, fps, frames = fps * seconds).write(file)
    }

    private class Encoder(val width: Int, val height: Int, val fps: Int, val frames: Int) {
        fun write(file: File) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
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
                if (frame <= frames) frame = feed(codec, frame)
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
            val pts = frame * 1_000_000L / fps
            if (frame == frames) {
                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                paint(codec.getInputImage(i)!!, frame)
                codec.queueInputBuffer(i, 0, width * height * 3 / 2, pts, 0)
            }
            return frame + 1
        }

        private fun paint(image: android.media.Image, frame: Int) {
            val y = image.planes[0]
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val lit = (col + frame * BAR_STEP) % width < width / 4
                    y.buffer.put(row * y.rowStride + col * y.pixelStride, if (lit) LUMA_HI else LUMA_LO)
                }
            }
            for (p in 1..2) {
                val plane = image.planes[p]
                for (row in 0 until height / 2) {
                    for (col in 0 until width / 2) {
                        plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, CHROMA)
                    }
                }
            }
        }
    }

    /**
     * Bring [scenario]'s task back to the front, as Recents does. ActivityScenario's own way
     * back is an activity start, which Android refuses from an app sent to the background, so
     * this borrows the shell's right to reorder tasks for the one call.
     */
    fun backFromRecents(instrumentation: Instrumentation, scenario: ActivityScenario<*>, task: Int) {
        val ui = instrumentation.uiAutomation
        ui.adoptShellPermissionIdentity(Manifest.permission.REORDER_TASKS, START_FROM_BACKGROUND)
        try {
            instrumentation.runOnMainSync {
                instrumentation.targetContext.getSystemService(ActivityManager::class.java).moveTaskToFront(task, 0)
            }
        } finally {
            ui.dropShellPermissionIdentity()
        }
        // ActivityScenario's state is read off the main thread.
        val deadline = SystemClock.uptimeMillis() + BACK_TIMEOUT_MS
        while (scenario.state != Lifecycle.State.RESUMED) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("never came back to the front")
            SystemClock.sleep(POLL_MS)
        }
    }

    private const val BIT_RATE = 400_000
    private const val DEQUEUE_US = 10_000L
    private const val BAR_STEP = 4
    private const val LUMA_HI: Byte = 235.toByte()
    private const val LUMA_LO: Byte = 16
    private const val CHROMA: Byte = 128.toByte()
    private const val START_FROM_BACKGROUND = "android.permission.START_ACTIVITIES_FROM_BACKGROUND"
    private const val BACK_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 100L
}

/** Serves one file over loopback HTTP, with `Range` — what the blob endpoint does for the player. */
class ClipServer(private val bytes: ByteArray) : Closeable {
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

    private companion object {
        const val BACKLOG = 16
        const val LOOPBACK = "127.0.0.1"
    }
}
