package one.rarebit.heyarr.mobile.cast

import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK's numbers in the words `:core`'s receiver policy reads (heyarr-kmp#250). The rest of
 * the adapter needs a phone and a TV; these are what it hands the tested manager.
 */
class SdkCastChannelTest {
    @Test fun playerStatesAreTheCastMediaStatusNames() {
        assertEquals("PLAYING", SdkCastChannel.stateName(MediaStatus.PLAYER_STATE_PLAYING))
        assertEquals("PAUSED", SdkCastChannel.stateName(MediaStatus.PLAYER_STATE_PAUSED))
        assertEquals("BUFFERING", SdkCastChannel.stateName(MediaStatus.PLAYER_STATE_BUFFERING))
        assertEquals(
            "a LOAD still starting is buffering, so a stuck one is a stall",
            "BUFFERING",
            SdkCastChannel.stateName(MediaStatus.PLAYER_STATE_LOADING),
        )
        assertEquals("IDLE", SdkCastChannel.stateName(MediaStatus.PLAYER_STATE_IDLE))
        assertEquals("UNKNOWN", SdkCastChannel.stateName(MediaStatus.PLAYER_STATE_UNKNOWN))
    }

    @Test fun idleReasonsSayWhetherAFilmIsResumed() {
        assertEquals("FINISHED", SdkCastChannel.idleName(MediaStatus.IDLE_REASON_FINISHED))
        assertEquals("CANCELLED", SdkCastChannel.idleName(MediaStatus.IDLE_REASON_CANCELED))
        assertEquals("INTERRUPTED", SdkCastChannel.idleName(MediaStatus.IDLE_REASON_INTERRUPTED))
        assertEquals("ERROR", SdkCastChannel.idleName(MediaStatus.IDLE_REASON_ERROR))
        assertNull(SdkCastChannel.idleName(MediaStatus.IDLE_REASON_NONE))
    }

    @Test fun onlyANetworkFailureEndsASessionAsLost() {
        assertTrue(SdkCastChannel.lostEnd(CastStatusCodes.NETWORK_ERROR))
        assertTrue(SdkCastChannel.lostEnd(CastStatusCodes.TIMEOUT))
        assertFalse("Stop casting", SdkCastChannel.lostEnd(CastStatusCodes.SUCCESS))
        assertFalse("the app quit from the TV", SdkCastChannel.lostEnd(CastStatusCodes.APPLICATION_NOT_RUNNING))
    }
}
