package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.PlayerRecovery
import one.rarebit.heyarr.mobile.playback.PlayerRecovery.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder-failure state machine behind the player's recovery (#231): what a Nothing
 * Phone back from Recents hit, a hardware decoder reclaimed in the background and a Play
 * button that could not get out of Media3's error state.
 */
class PlayerRecoveryTest {

    private val decoding = "ERROR_CODE_DECODING_FAILED"

    @Test fun aDecoderFailureIsRetriedOnceAutomaticallyAndPlayingOnClearsIt() {
        val r = PlayerRecovery()
        // Failed at 23:59, the issue's position.
        assertEquals("the first failure re-prepares by itself", Decision.Retry, r.onError(decoding, 1_439_000))
        assertFalse("no error is put up for it", r.showing)
        // The fresh player plays on, well past the point that failed: recovered.
        r.onPosition(1_440_000, playing = true)
        assertEquals("a first frame is not yet recovery", 1, r.consecutiveFailures)
        r.onPosition(1_439_000 + PlayerRecovery.RECOVERED_AFTER_MS, playing = true)
        assertEquals("playing past the failure clears the count", 0, r.consecutiveFailures)
        // So a failure much later gets its own automatic retry.
        assertEquals("a later failure is retried again", Decision.Retry, r.onError(decoding, 3_000_000))
    }

    @Test fun aSecondFailureInARowIsShownWithTheOtherCopyAndPlayRecoversIt() {
        val r = PlayerRecovery()
        assertEquals(Decision.Retry, r.onError(decoding, 60_000))
        assertEquals(
            "the second failure in a row is shown, offering the other copy",
            Decision.Show(offerOtherCopy = true),
            r.onError(decoding, 60_500),
        )
        assertTrue("the error is up, waiting for Play", r.showing)
        assertEquals("Play on the error re-prepares", Decision.Retry, r.onPlay())
        assertFalse("and takes the error down", r.showing)
        // Play keeps working however often it fails: each failure is shown again, never retried
        // behind the person's back.
        assertEquals(Decision.Show(offerOtherCopy = true), r.onError(decoding, 60_500))
        assertEquals("Play re-prepares again", Decision.Retry, r.onPlay())
        r.onPosition(60_500 + PlayerRecovery.RECOVERED_AFTER_MS, playing = true)
        assertEquals("and once it plays on, all is forgiven", 0, r.consecutiveFailures)
    }

    @Test fun aDecoderThatFailsAtTheSameSampleIsNotRetriedForEver() {
        // Re-prepared, it renders a frame or two and fails at the same place again: without a
        // margin past the failure this would retry, flicker and fail in a loop, showing nothing.
        val r = PlayerRecovery()
        assertEquals(Decision.Retry, r.onError(decoding, 10_000))
        r.onPosition(10_400, playing = true)
        assertEquals(Decision.Show(offerOtherCopy = true), r.onError(decoding, 10_400))
    }

    @Test fun anErrorThatIsNotTheDecodersIsShownAtOnceAndPlayStillRetriesIt() {
        val r = PlayerRecovery()
        assertEquals(
            "a broken stream is not re-prepared behind the person's back",
            Decision.Show(offerOtherCopy = false),
            r.onError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 5_000),
        )
        assertEquals("but Play is Media3's own recovery for it too", Decision.Retry, r.onPlay())
    }

    @Test fun aFormatThePhoneCannotDecodeIsNotRetried() {
        // The same every time: the re-plan for a stream (PlaybackCoordinator.onIssue) answers it.
        val r = PlayerRecovery()
        assertTrue(r.onError("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", 0) is Decision.Show)
        assertTrue(PlayerRecovery.recoverable("ERROR_CODE_DECODING_RESOURCES_RECLAIMED"))
        assertTrue(PlayerRecovery.recoverable("ERROR_CODE_DECODER_INIT_FAILED"))
        assertFalse(PlayerRecovery.recoverable("ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES"))
    }

    @Test fun playWithNoErrorUpIsAnOrdinaryPlay() {
        val r = PlayerRecovery()
        assertNull(r.onPlay())
        r.onError(decoding, 0)
        assertNull("an automatic retry is under way, so Play just plays", r.onPlay())
    }

    @Test fun aNewItemStartsWithACleanSlate() {
        val r = PlayerRecovery()
        r.onError(decoding, 0)
        r.onError(decoding, 0)
        r.reset()
        assertFalse(r.showing)
        assertEquals("a new copy gets its own automatic retry", Decision.Retry, r.onError(decoding, 0))
    }

    @Test fun pausedPlaybackIsNotRecovery() {
        val r = PlayerRecovery()
        r.onError(decoding, 0)
        r.onPosition(PlayerRecovery.RECOVERED_AFTER_MS * 2, playing = false)
        assertEquals("a seek while paused proves nothing about the decoder", 1, r.consecutiveFailures)
    }

    // ── A lapsed stream URL or lease (heyarr-kmp#232) ───────────────────────────────

    private val badStatus = "ERROR_CODE_IO_BAD_HTTP_STATUS"

    @Test fun anExpiredStreamIsPlannedAgainOnceAndPlayingOnForgivesIt() {
        val r = PlayerRecovery()
        assertEquals("410 in a long film: plan it again", Decision.Refresh, r.onError(badStatus, 5_400_000, 410))
        assertFalse("nothing shown while it does", r.showing)
        assertEquals(
            "refused again straight away: shown, and no other copy for a refusal",
            Decision.Show(offerOtherCopy = false),
            r.onError(badStatus, 5_400_000, 410),
        )
        assertEquals("Play plans it afresh rather than asking for the lapsed URL", Decision.Refresh, r.onPlay())
        assertEquals(
            "refused once more: shown again",
            Decision.Show(offerOtherCopy = false),
            r.onError(badStatus, 5_400_000, 410),
        )
        assertEquals(Decision.Refresh, r.onPlay())
        // That fresh plan plays on.
        r.onPosition(5_400_000 + PlayerRecovery.RECOVERED_AFTER_MS, playing = true)
        assertEquals(
            "a later expiry, an hour on, is refreshed too",
            Decision.Refresh,
            r.onError(badStatus, 9_000_000, 403),
        )
    }

    @Test fun onlyALapsedUrlIsRefreshed() {
        assertEquals(Decision.Refresh, PlayerRecovery().onError(badStatus, 0, 401))
        assertEquals(Decision.Show(offerOtherCopy = false), PlayerRecovery().onError(badStatus, 0, 404))
        assertEquals(Decision.Show(offerOtherCopy = false), PlayerRecovery().onError(badStatus, 0, 500))
    }
}
