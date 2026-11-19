package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.playback.clockLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockLabelTest {
    @Test
    fun minutesUnderAnHourAndHoursFromOne() {
        val cases = listOf(
            0L to "0:00",
            5L to "0:05",
            59L to "0:59",
            60L to "1:00",
            605L to "10:05",
            3_599L to "59:59",
            3_600L to "1:00:00",
            3_661L to "1:01:01",
            36_000L to "10:00:00",
            359_999L to "99:59:59",
        )
        for ((seconds, label) in cases) assertEquals(label, clockLabel(seconds), "$seconds s")
    }

    @Test
    fun aNegativeLongIsRenderedAsTheOldCopiesDid() {
        assertEquals("0:-5", clockLabel(-5L))
        assertEquals("-1:-5", clockLabel(-65L))
        assertEquals("0:00", clockLabel(-3_600L))
    }

    @Test
    fun aFractionalPositionTruncatesAndClampsAtZero() {
        assertEquals("0:59", clockLabel(59.999))
        assertEquals("1:00:00", clockLabel(3_600.4))
        assertEquals("0:00", clockLabel(-0.4))
        assertEquals("0:00", clockLabel(-90.0))
        assertEquals("0:00", clockLabel(Double.NaN))
    }
}
