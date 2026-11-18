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
    /**
     * A moving bar, [seconds] long, encoded to H.264 and muxed to MP4 by the device's own encoder.
     * [noisy] fills the picture with a pattern that changes every frame, so the encoder spends
     * its whole bitrate: a plain bar compresses to almost nothing, and the network tests need a
     * file the size a real stream's would be for its length.
     */
    @Suppress("LongParameterList") // the clip's shape, each with the default most tests want
    fun write(
        file: File,
        seconds: Int = 40,
        width: Int = 320,
        height: Int = 240,
        fps: Int = 15,
        noisy: Boolean = false,
    ) {
        file.parentFile?.mkdirs()
        Encoder(width, height, fps, frames = fps * seconds, noisy).write(file)
    }

    private class Encoder(val width: Int, val height: Int, val fps: Int, val frames: Int, val noisy: Boolean) {
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
                    val luma = when {
                        noisy -> ((col * NOISE_A + row * NOISE_B + frame * NOISE_C) xor (col * row)).toByte()
                        lit -> LUMA_HI
                        else -> LUMA_LO
                    }
                    y.buffer.put(row * y.rowStride + col * y.pixelStride, luma)
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
    private const val NOISE_A = 31
    private const val NOISE_B = 17
    private const val NOISE_C = 13
    private const val LUMA_HI: Byte = 235.toByte()
    private const val LUMA_LO: Byte = 16
    private const val CHROMA: Byte = 128.toByte()
    private const val START_FROM_BACKGROUND = "android.permission.START_ACTIVITIES_FROM_BACKGROUND"
    private const val BACK_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 100L
}

/**
 * Serves one file over loopback HTTP, with `Range` — what the blob endpoint does for the player.
 * For the network checks (heyarr-kmp#232) it can also go [down] (every connection, open or new,
 * is reset, as a lost network resets them) and refuse a lapsed stream token with 410 ([expire]):
 * `url(token)` names a URL by its token, as a planned stream's does.
 */
class ClipServer(
    private val bytes: ByteArray,
    private val contentType: String = "video/mp4",
    name: String = "clip.mp4",
) : Closeable {
    // IPv4 by name: Android's getLoopbackAddress() is ::1, which a 127.0.0.1 URL never reaches.
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    val url = "http://$LOOPBACK:${socket.localPort}/$name"
    private val live = java.util.Collections.synchronizedSet(HashSet<Socket>())
    private val expired = java.util.Collections.synchronizedSet(HashSet<String>())

    /** How many requests asked for each token, for the refresh check. */
    val requests = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Bytes per second to send at, 0 for as fast as it can. A small test clip arrives whole in
     * one read otherwise, and a player that holds the whole film cannot be starved by an outage.
     */
    @Volatile var bytesPerSecond: Long = 0

    /** Down: every connection is reset, the ones open now included. */
    @Volatile var down: Boolean = false
        set(value) {
            field = value
            if (value) synchronized(live) { live.toList() }.forEach(::reset)
        }

    fun url(token: String) = "$url?t=$token"

    /** Refusing: every request is answered 401, as the node answers one with no credential (#240). */
    @Volatile var refusing: Boolean = false

    /** Refuse [token] with 410 from now on, and drop the reads open on it so the next one asks. */
    fun expire(token: String) {
        expired.add(token)
        synchronized(live) { live.toList() }.forEach(::reset)
    }

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (down) {
                    reset(client)
                } else {
                    live.add(client)
                    thread(isDaemon = true) {
                        runCatching { serve(client) }
                        live.remove(client)
                    }
                }
            }
        }
    }

    private fun reset(client: Socket) {
        runCatching {
            client.setSoLinger(true, 0)
            client.close()
        }
    }

    private fun serve(client: Socket): Unit = client.use {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return@use
        val token = requestLine.substringAfter("?t=", "").substringBefore(' ')
        requests.merge(token, 1, Int::plus)
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return@use
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        if (refusing) {
            val refused = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            client.getOutputStream().write(refused.toByteArray(Charsets.ISO_8859_1))
            return@use
        }
        if (token in expired) {
            val gone = "HTTP/1.1 410 Gone\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            client.getOutputStream().write(gone.toByteArray(Charsets.ISO_8859_1))
            return@use
        }
        val spec = range?.removePrefix("bytes=")?.split('-')
        val from = spec?.getOrNull(0)?.toIntOrNull() ?: 0
        val to = spec?.getOrNull(1)?.toIntOrNull() ?: (bytes.size - 1)
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
            append("Content-Length: ${to - from + 1}\r\n")
            if (range != null) append("Content-Range: bytes $from-$to/${bytes.size}\r\n")
            append("\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        send(out, from, to)
        out.flush()
    }

    /** Bytes [from]..[to], at [bytesPerSecond] when one is set. */
    private fun send(out: java.io.OutputStream, from: Int, to: Int) {
        var at = from
        val started = System.nanoTime()
        while (at <= to) {
            val n = minOf(CHUNK, to - at + 1)
            out.write(bytes, at, n)
            at += n
            // Hold to the rate: sleep until the bytes sent so far are due.
            val rate = bytesPerSecond.takeIf { it > 0 } ?: continue
            val aheadMs = ((at - from) * NANOS_PER_SECOND / rate - (System.nanoTime() - started)) / NANOS_PER_MILLI
            if (aheadMs > 0) Thread.sleep(aheadMs)
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val BACKLOG = 16
        const val CHUNK = 16 * 1024
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val LOOPBACK = "127.0.0.1"
    }
}
