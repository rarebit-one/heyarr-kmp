package one.rarebit.heyarr.desktop.playback

import one.rarebit.heyarr.core.playback.DisplayFit
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import java.awt.GraphicsEnvironment

/**
 * The desktop half of choosing a copy (heyarr-kmp#225): the screen this machine plays on,
 * as `:core`'s [DisplayFit].
 *
 * The tallest connected screen's short edge in DEVICE pixels — a window can be dragged to
 * any of them, and a Retina panel's logical 1117 lines are 2234 real ones. HDR is reported
 * as absent: the JVM has no portable way to ask, and an SDR copy is the safe choice when
 * the node may re-encode without tone-mapping. Probed once; a headless JVM (the screenshot
 * task, tests) gets the 1080-line profile the player has always declared.
 */
object DesktopDisplay {
    val fit: DisplayFit by lazy { DisplayFit(height = probeHeight() ?: HeyarrApi.DEFAULT_MAX_HEIGHT) }

    /**
     * What `max_height` the plan may declare at most: [HeyarrApi.DEFAULT_MAX_HEIGHT]. The
     * desktop declares H.264 only and has no hardware decoder, so a taller declaration
     * would ask the node for a 4K H.264 re-encode this machine stutters on.
     */
    const val CEILING = HeyarrApi.DEFAULT_MAX_HEIGHT

    private fun probeHeight(): Int? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.maxOfOrNull { device ->
            val config = device.defaultConfiguration
            val b = config.bounds
            val t = config.defaultTransform
            minOf(b.width * t.scaleX, b.height * t.scaleY).toInt()
        }?.takeIf { it > 0 }
    }.getOrNull()
}
