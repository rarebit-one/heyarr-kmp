package one.rarebit.heyarr.mobile

import androidx.media3.common.util.UnstableApi
import one.rarebit.heyarr.core.playback.Reconnect
import one.rarebit.heyarr.mobile.playback.StreamErrorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The film's reads through a lost connection (heyarr-kmp#232): what counts as one, and the budget. */
@UnstableApi
class StreamErrorPolicyTest {
    @Test fun aLostConnectionIsRecognisedHoweverItIsWrapped() {
        assertTrue(StreamErrorPolicy.isConnectionFailure(ConnectException("Failed to connect to /10.0.0.2:7777")))
        assertTrue(StreamErrorPolicy.isConnectionFailure(IOException("read", SocketTimeoutException("timeout"))))
        assertTrue(StreamErrorPolicy.isConnectionFailure(IOException("wrap", UnknownHostException("node.example"))))
        assertTrue(
            "the far end closed the socket before answering",
            StreamErrorPolicy.isConnectionFailure(IOException("unexpected end of stream on http://10.0.0.2/...")),
        )
        assertFalse(StreamErrorPolicy.isConnectionFailure(IOException("malformed")))
        assertFalse(StreamErrorPolicy.isConnectionFailure(null))
        assertNull("no status without a refusal", StreamErrorPolicy.httpStatus(ConnectException()))
    }

    @Test fun theRetryBudgetIsLiftedWhileAnOutageIsUnderWay() {
        val default = 3
        assertEquals(
            "a minute into an outage, failing 5 s ago: keep trying",
            Int.MAX_VALUE,
            StreamErrorPolicy.minimumRetryCount(60_000, 5_000, default),
        )
        assertEquals(
            "five minutes of it: give up and say so",
            default,
            StreamErrorPolicy.minimumRetryCount(Reconnect.GIVE_UP_AFTER_MS, 5_000, default),
        )
        assertEquals(
            "back for a while: Media3's own budget again",
            default,
            StreamErrorPolicy.minimumRetryCount(60_000, StreamErrorPolicy.OUTAGE_GRACE_MS, default),
        )
        assertEquals("never failed", default, StreamErrorPolicy.minimumRetryCount(null, null, default))
    }
}
