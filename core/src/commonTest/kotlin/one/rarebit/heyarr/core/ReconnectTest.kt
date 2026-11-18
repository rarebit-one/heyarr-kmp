package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.playback.Reconnect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectTest {
    @Test fun theWaitDoublesFromASecondToTenAndStaysThere() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (1..6).map(Reconnect::delayMs))
        assertEquals(10_000L, Reconnect.delayMs(500), "no overflow on a long outage")
        assertEquals(1_000L, Reconnect.delayMs(0))
    }

    @Test fun anOutageIsRiddenOutForFiveMinutes() {
        assertTrue(Reconnect.keepTrying(70_000), "longer than any buffer")
        assertTrue(Reconnect.keepTrying(Reconnect.GIVE_UP_AFTER_MS - 1))
        assertFalse(Reconnect.keepTrying(Reconnect.GIVE_UP_AFTER_MS))
    }

    @Test fun aLapsedUrlOrLeaseIsPlannedAgainNotRetried() {
        assertTrue(Reconnect.expired(401))
        assertTrue(Reconnect.expired(403))
        assertTrue(Reconnect.expired(410))
        assertFalse(Reconnect.expired(404), "gone from the library: nothing to refresh")
        assertFalse(Reconnect.expired(503))
        assertFalse(Reconnect.expired(null))
    }
}
