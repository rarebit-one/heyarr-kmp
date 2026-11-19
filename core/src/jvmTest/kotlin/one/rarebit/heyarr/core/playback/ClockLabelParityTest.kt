package one.rarebit.heyarr.core.playback

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [clockLabel] replaced four `String.format` copies (desktop PlayerScreen and NowPlayingScreen,
 * the phone's CastScreen and ContinueClient) and one `padStart` copy (Continue). This pins it
 * byte-for-byte against the format-based original over every boundary and a sweep, negatives
 * included, so the move changed no label.
 *
 * In a locale whose zero digit is not '0' (Arabic, Persian, …) `%d` printed native digits; the
 * shared label prints ASCII digits everywhere, as Continue's copy always did. The parity is
 * therefore asserted under a Latin-digit locale.
 */
class ClockLabelParityTest {
    private fun formatCopy(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, sec) else "%d:%02d".format(Locale.US, m, sec)
    }

    private fun playerCopy(s: Double): String = formatCopy(s.toLong().coerceAtLeast(0))

    @Test
    fun longMatchesTheFormatCopy() {
        val edges = listOf(-86_400L, -3_601L, -3_600L, -61L, -60L, -59L, -1L, 0L, 1L, 9L, 10L, 59L, 60L, 599L, 600L) +
            listOf(3_599L, 3_600L, 3_601L, 35_999L, 36_000L, 86_399L, 360_000L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (s in edges + (-4_000L..40_000L step 7)) assertEquals(formatCopy(s), clockLabel(s), "$s s")
    }

    @Test
    fun doubleMatchesThePlayerCopy() {
        val edges = listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.0, -0.5, 0.0, 0.999)
        val sweep = (-400..40_000 step 3).map { it + 0.73 }
        for (s in edges + sweep + listOf(3_599.999, 3_600.0)) {
            assertEquals(playerCopy(s), clockLabel(s), "$s s")
        }
    }
}
