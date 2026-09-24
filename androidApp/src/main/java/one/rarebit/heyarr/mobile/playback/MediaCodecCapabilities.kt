package one.rarebit.heyarr.mobile.playback

import android.content.Context
import android.media.MediaCodecList
import android.view.WindowManager

/**
 * The Android half of [ClientCapabilities]: ask `MediaCodecList` which audio/video
 * MIMEs this phone has a DECODER for, and size `max_height` to the display's longer
 * edge (a 1080×2412 phone is asked for ≤ 2412 — landscape-tall — so a 4K source is
 * downscaled and a 1080p one isn't). Probed once per process; codecs don't come and go.
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
        return ClientCapabilities.fromDecoderMimes(mimes, maxHeight = displayLongEdge(context))
    }

    private fun displayLongEdge(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return DEFAULT_MAX_HEIGHT
        val bounds = wm.maximumWindowMetrics.bounds
        return maxOf(bounds.width(), bounds.height()).takeIf { it > 0 } ?: DEFAULT_MAX_HEIGHT
    }

    private const val DEFAULT_MAX_HEIGHT = 1080
}
