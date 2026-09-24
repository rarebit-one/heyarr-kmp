package one.rarebit.heyarr.desktop.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The slice of libmpv's C API the embedded player needs: a core (client.h) and the
 * software render context (render.h). Loaded once through JNA; absent on a machine
 * without the library, in which case the player says so and offers the pop-out.
 */
internal interface MpvLib : Library {
    fun mpv_create(): Pointer?
    fun mpv_set_option_string(h: Pointer, name: String, data: String): Int
    fun mpv_initialize(h: Pointer): Int
    fun mpv_get_property(h: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_terminate_destroy(h: Pointer)
    fun mpv_error_string(code: Int): String
    fun mpv_render_context_create(res: PointerByReference, h: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, cb: UpdateCallback?, cbCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    /** `mpv_render_update_fn`: mpv has a new frame (or wants attention). Called on mpv's thread; must return at once. */
    fun interface UpdateCallback : Callback {
        fun invoke(ctx: Pointer?)
    }

    companion object {
        const val FORMAT_INT64 = 4
        const val PARAM_API_TYPE = 1
        const val PARAM_SW_SIZE = 17
        const val PARAM_SW_FORMAT = 18
        const val PARAM_SW_STRIDE = 19
        const val PARAM_SW_POINTER = 20
        const val UPDATE_FRAME = 1L

        /** The library, loaded once; the failure names what to install. */
        val loaded: Result<MpvLib> by lazy {
            // libmpv refuses to start under a non-C numeric locale, and the JVM sets the process
            // locale from the environment at startup. Java's own formatting is unaffected.
            runCatching { Native.load(if (Platform.isWindows()) "msvcrt" else "c", CLib::class.java).setlocale(if (Platform.isLinux()) 1 else 4, "C") }
            for (dir in listOf("/opt/homebrew/lib", "/usr/local/lib")) NativeLibrary.addSearchPath("mpv", dir)
            val lib = listOf("mpv", "libmpv-2", "mpv-2").firstNotNullOfOrNull { name -> runCatching { Native.load(name, MpvLib::class.java) }.getOrNull() }
            if (lib != null) Result.success(lib) else Result.failure(UnsatisfiedLinkError("libmpv is not installed (the mpv library, not only the mpv command)"))
        }
    }
}

/** `setlocale(3)`: LC_NUMERIC is 1 on glibc and 4 on macOS, the BSDs and MSVC. */
internal interface CLib : Library {
    fun setlocale(category: Int, locale: String?): String?
}

/** One rendered picture: an immutable Skia image plus its pixel size. Owned by [MpvRenderer]; never closed while a draw holds it. */
class VideoFrame internal constructor(internal val image: Image, val width: Int, val height: Int)

/**
 * mpv's software renderer feeding Compose. mpv decodes and converts each frame into a
 * pooled Skia bitmap on this renderer's own thread, at the video's own size (Skia scales
 * on the GPU); the frame then becomes an immutable image the picture composable draws.
 * Frames are handed over under [withFrame]'s lock, and an image is freed only there, so
 * a draw never touches freed pixels. Three frames stay alive: the current one and two
 * retired, enough for any draw already in flight.
 *
 * Why software and not OpenGL: Compose owns its GL/Metal context and shares none of it;
 * a memory surface is the one contract libmpv offers that needs nothing of the host.
 * Measured here: ~18 ms a frame at 1080p on one core — well inside 24/30 fps.
 */
internal class MpvRenderer(private val lib: MpvLib, private val handle: Pointer) : AutoCloseable {
    private val ctx: Pointer
    private val wake = Semaphore(0)

    @Volatile private var running = true

    // Strong reference: JNA only keeps the native trampoline alive while this object is.
    private val callback = MpvLib.UpdateCallback { wake.release() }
    private val apiName = Memory(8).apply { setString(0, "sw") }
    private val format = Memory(8).apply { setString(0, "rgb0") }
    private val sizeArg = Memory(8)
    private val strideArg = Memory(8)
    private val propArg = Memory(8)
    private val params = Memory(6 * PARAM_BYTES)
    private val pool = arrayOfNulls<Bitmap>(2)
    private var poolIndex = 0
    private val lock = Any()
    private val retired = ArrayDeque<Image>()
    private val thread: Thread

    /** The latest picture, null before the first frame or for audio-only media. Read it inside a draw. */
    var frame: VideoFrame? by mutableStateOf(null)
        private set

    init {
        param(0, MpvLib.PARAM_API_TYPE, apiName)
        param(1, 0, null)
        val out = PointerByReference()
        val rc = lib.mpv_render_context_create(out, handle, params)
        if (rc < 0) throw IllegalStateException("libmpv render context: ${lib.mpv_error_string(rc)}")
        ctx = out.value
        lib.mpv_render_context_set_update_callback(ctx, callback, null)
        thread = Thread(::loop, "mpv-render").apply {
            isDaemon = true
            start()
        }
    }

    /** Run [block] with the current frame while no frame can be freed underneath it. */
    fun <T> withFrame(block: (VideoFrame?) -> T): T = synchronized(lock) { block(frame) }

    private fun loop() {
        while (running) {
            if (!wake.tryAcquire(250, TimeUnit.MILLISECONDS)) continue
            wake.drainPermits()
            if (!running) break
            if (lib.mpv_render_context_update(ctx) and MpvLib.UPDATE_FRAME == 0L) continue
            runCatching { renderOne() }
        }
    }

    private fun renderOne() {
        val (w, h) = renderSize(property("dwidth"), property("dheight")) ?: return
        val bmp = pool[poolIndex]?.takeIf { it.width == w && it.height == h } ?: Bitmap().also {
            it.allocPixels(ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.OPAQUE, null), w, h))
            // allocPixels leaves the buffer uninitialised; a render that does not fill it
            // (a partial or pre-roll frame during warm-up) would otherwise show as garbage —
            // the cycling blue/green at stream start. Start it opaque black.
            it.erase(0xFF000000.toInt())
            pool[poolIndex]?.close()
            pool[poolIndex] = it
        }
        val pix = bmp.peekPixels() ?: return
        try {
            sizeArg.setInt(0, w)
            sizeArg.setInt(4, h)
            strideArg.setLong(0, pix.rowBytes.toLong())
            param(0, MpvLib.PARAM_SW_SIZE, sizeArg)
            param(1, MpvLib.PARAM_SW_FORMAT, format)
            param(2, MpvLib.PARAM_SW_STRIDE, strideArg)
            param(3, MpvLib.PARAM_SW_POINTER, Pointer(pix.addr))
            param(4, 0, null)
            // Blocks until the frame is due: mpv paces this thread, the UI just draws what is current.
            if (lib.mpv_render_context_render(ctx, params) < 0) return
        } finally {
            pix.close()
        }
        val image = Image.makeFromBitmap(bmp) // a copy, so the pool bitmap can take the next frame at once
        synchronized(lock) {
            frame?.let { retired.addLast(it.image) }
            frame = VideoFrame(image, w, h)
            while (retired.size > 2) retired.removeFirst().close()
        }
        poolIndex = (poolIndex + 1) % pool.size
    }

    private fun property(name: String): Int {
        propArg.setLong(0, 0)
        return if (lib.mpv_get_property(handle, name, MpvLib.FORMAT_INT64, propArg) < 0) 0 else propArg.getLong(0).toInt()
    }

    private fun param(i: Int, type: Int, data: Pointer?) {
        params.setInt(i * PARAM_BYTES, type)
        params.setPointer(i * PARAM_BYTES + 8, data)
    }

    override fun close() {
        running = false
        wake.release()
        runCatching { thread.join(2000) }
        lib.mpv_render_context_set_update_callback(ctx, null, null)
        lib.mpv_render_context_free(ctx)
        synchronized(lock) {
            frame?.image?.close()
            frame = null
            retired.forEach { it.close() }
            retired.clear()
            pool.forEach { it?.close() }
            pool.fill(null)
        }
    }

    companion object {
        /** `struct mpv_render_param { int type; void *data; }` on a 64-bit ABI. */
        private const val PARAM_BYTES = 16L

        /** Longest edge mpv converts on the CPU; anything larger is scaled down and Skia does the rest. */
        const val MAX_EDGE = 3840

        /**
         * The pixel size to render: the video's display size (aspect already corrected),
         * capped at [MAX_EDGE] and kept even; null while there is no video (audio-only, or
         * before the first frame). Pure, for the tests.
         */
        fun renderSize(displayWidth: Int, displayHeight: Int): Pair<Int, Int>? {
            if (displayWidth < 2 || displayHeight < 2) return null
            val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(displayWidth, displayHeight))
            val w = (displayWidth * scale).toInt().coerceAtLeast(2) and 1.inv()
            val h = (displayHeight * scale).toInt().coerceAtLeast(2) and 1.inv()
            return w to h
        }
    }
}
