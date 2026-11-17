package one.rarebit.heyarr.mobile.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.view.Display
import android.view.WindowManager

/**
 * The Android half of [ClientCapabilities]: ask `MediaCodecList` which audio/video
 * MIMEs this phone has a DECODER for, size `max_height` to the display's SHORT edge, and
 * ask the display whether it renders HDR. Probed once per process; codecs and panels
 * don't come and go.
 *
 * The short edge, not the long one (heyarr-kmp#225): video plays landscape, so a
 * 1080×2412 phone shows 1080 lines at full resolution. The long edge (2412) was sent
 * before, which is taller than a 2160p frame — so the node never downscaled anything for a
 * phone, and a work holding a 2160p and a 1080p copy had no reason to prefer the 1080p one.
 */
object MediaCodecCapabilities {

    @Volatile private var cached: ClientCapabilities? = null

    fun probe(context: Context): ClientCapabilities = cached ?: build(context).also { cached = it }

    private fun build(context: Context): ClientCapabilities {
        val mimes = ArrayList<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) mimes.add(type)
        }
        return ClientCapabilities.fromDecoderMimes(
            mimes,
            maxHeight = displayShortEdge(context),
            supportsHdr = displaySupportsHdr(context),
        )
    }

    private fun displayShortEdge(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return DEFAULT_MAX_HEIGHT
        val bounds = wm.maximumWindowMetrics.bounds
        return minOf(bounds.width(), bounds.height()).takeIf { it > 0 } ?: DEFAULT_MAX_HEIGHT
    }

    // Through DisplayManager: the probe is handed the application context, and
    // `Context.getDisplay()` throws for a context that is not tied to a window.
    private fun displaySupportsHdr(context: Context): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        return runCatching { dm.getDisplay(Display.DEFAULT_DISPLAY)?.isHdr == true }.getOrDefault(false)
    }

    private const val DEFAULT_MAX_HEIGHT = 1080
}
