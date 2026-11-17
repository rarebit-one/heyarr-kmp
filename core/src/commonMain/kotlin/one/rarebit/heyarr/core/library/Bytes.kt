package one.rarebit.heyarr.core.library

import kotlin.math.roundToLong

/** Byte counts as people read them. Shared so a size reads the same on every screen of both apps. */
object Bytes {
    private val UNITS = arrayOf("KB", "MB", "GB", "TB")

    /**
     * `1.4 GB`, `812 MB`, `3.2 KB` — base-1024, one decimal below 100 of a unit. Each app
     * used to carry its own copy (the phone's `WorkAsset`, the desktop's `PrimaryAsset`);
     * both now delegate here. `String.format` is JVM-only, so the one decimal is rounded by
     * hand, half up, which is what `%.1f` did for every size a file actually has.
     */
    fun format(bytes: Long): String {
        var value = bytes.toDouble()
        var unit = -1
        while (value >= STEP && unit < UNITS.size - 1) {
            value /= STEP
            unit++
        }
        val tenths = (value * TENTHS).roundToLong()
        return when {
            unit < 0 -> "$bytes B"
            value >= WHOLE_FROM -> "${value.toLong()} ${UNITS[unit]}"
            else -> "${tenths / TENTHS}.${tenths % TENTHS} ${UNITS[unit]}"
        }
    }

    private const val STEP = 1024
    private const val TENTHS = 10
    private const val WHOLE_FROM = 100
}
