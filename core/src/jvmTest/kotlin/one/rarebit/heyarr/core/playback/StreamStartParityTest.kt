package one.rarebit.heyarr.core.playback

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StreamStart.seconds] replaced the desktop's `StreamSeek.seconds` and the phone's
 * `PlaybackTarget.formatSeconds`, both `String.format(Locale.ROOT, "%.3f", …)` with the
 * trailing zeros trimmed. Pin it to that original, byte for byte, across the half-up edges,
 * the exponent ranges of [Double.toString] and a wide sweep.
 */
class StreamStartParityTest {
    private fun formatCopy(value: Double): String {
        val whole = value.toLong()
        if (value == whole.toDouble()) return whole.toString()
        return String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    }

    private fun check(value: Double) = assertEquals(formatCopy(value), StreamStart.seconds(value), "$value")

    @Test
    fun edgesMatchTheFormatCopy() {
        listOf(
            0.0, -0.0, 0.5, 0.0004, 0.0005, 0.0015, 0.00049999, 0.001, 0.0001, 1.0E-7, 4.9E-324,
            12.5, 12.25, 12.3455, 12.3445, 12.2999, 12.3456, 0.9995, 9.9995, 99.9995, 999.9999,
            107.3, 2350.0004, 2350.0005, 9_999_999.9995, 1.0E7 + 0.5, 1.2345678912345E7, 1.0E15 + 0.25,
            1.0E19, 1.0E300, Long.MAX_VALUE.toDouble(), -12.3455, -0.0004, -0.0005, -1.0E7 - 0.5,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE,
        ).forEach(::check)
    }

    @Test
    fun aSweepOfPlaybackPositionsMatchesTheFormatCopy() {
        // Every millisecond and half-millisecond across the first ten minutes, then a film's
        // worth of arbitrary positions: where the half-up rule and binary noise meet.
        for (n in 0..1_200_000) check(n / 2_000.0)
        val random = Random(433)
        repeat(200_000) { check(random.nextDouble(0.0, 50_000.0)) }
        repeat(50_000) { check(random.nextLong(0, 50_000_000) / 1_000.0 + 0.0005) }
    }

    @Test
    fun theUrlIsBuiltOffTheBase() {
        val base = "http://node.test/api/v1/playback/stream/blake3:abc?token=t"
        assertEquals(base, StreamStart.url(base, 0.0))
        assertEquals(base, StreamStart.url(base, -3.0))
        assertEquals("$base&start=12.346", StreamStart.url(base, 12.3455))
        assertEquals("http://node.test/s?start=90", StreamStart.url("http://node.test/s", 90.0))
    }
}
