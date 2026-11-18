package one.rarebit.heyarr.core.playback

import one.rarebit.heyarr.core.net.JsonScan

/**
 * The person's preferred playback quality (heyarr-core#887): a [QualityPreference] and an
 * optional cap of their own, [maxHeight] lines and [maxBitrateBps].
 *
 * It has two homes. The person's default is personal state: it follows them from device to
 * device, encrypted, and the node never reads it (Invariant 6). A device's override is a
 * local setting on that device. The client lays the override over the default ([resolve])
 * and sends the result with every plan request ([planJson]); heyarr-core's ADR-0123
 * addendum is the contract.
 *
 * The register value stored in personal state and the plan request's `preference` object
 * are the same JSON: `{"quality":"best","max_height":1080}`, with absent caps left out.
 */
data class PlaybackPreference(
    val quality: QualityPreference = QualityPreference.DEFAULT,
    /** The person's height ceiling, in lines, under any quality; null is no cap. */
    val maxHeight: Int? = null,
    /** The person's bitrate ceiling. The node steers its choice by it; null is no cap. */
    val maxBitrateBps: Long? = null,
) {
    /** `{"quality":…,"max_height":…,"max_bitrate_bps":…}`: the register value, and the plan's `preference`. */
    fun planJson(): String = buildString {
        append("{\"quality\":\"").append(quality.key).append('"')
        maxHeight?.let { append(",\"max_height\":").append(it) }
        maxBitrateBps?.let { append(",\"max_bitrate_bps\":").append(it) }
        append('}')
    }

    /** `Data saver · ≤1080p`: what Settings shows for a preference. */
    val label: String
        get() = listOfNotNull(quality.label, maxHeight?.let { "≤${it}p" }).joinToString(" · ")

    companion object {
        /** The heights the Settings cap offers, tallest first. */
        val CAP_HEIGHTS = listOf(ReleaseName.UHD, ReleaseName.FULL_HD, ReleaseName.HD)

        /**
         * A stored or echoed value back to a preference; null when [json] is not one (nothing
         * stored, or a value an older or newer client wrote that names no quality this one
         * knows). A cap that is not a positive number is dropped, not trusted.
         */
        fun parse(json: String?): PlaybackPreference? {
            val obj = json?.let { JsonScan.rootObject(it) } ?: return null
            return QualityPreference.ofOrNull(JsonScan.stringField(obj, "quality"))?.let { quality ->
                PlaybackPreference(
                    quality = quality,
                    maxHeight = JsonScan.intField(obj, "max_height")?.takeIf { it > 0 },
                    maxBitrateBps = JsonScan.longField(obj, "max_bitrate_bps")?.takeIf { it > 0 },
                )
            }
        }

        /**
         * What this device plays by: its [override] quality when it has one, else the
         * person's [account] default, else [QualityPreference.DEFAULT]. The cap is the
         * person's and holds under either: it says what they will spend, not what fits here.
         */
        fun resolve(account: PlaybackPreference?, override: QualityPreference?): PlaybackPreference {
            val base = account ?: PlaybackPreference()
            return if (override == null) base else base.copy(quality = override)
        }
    }
}
