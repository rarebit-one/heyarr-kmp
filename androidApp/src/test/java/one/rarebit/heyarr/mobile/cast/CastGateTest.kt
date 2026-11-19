package one.rarebit.heyarr.mobile.cast

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.mobile.cast.CastGate.Route
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local-playback gate (heyarr-kmp#248, #250): a film is never streamed on this phone and
 * a TV at once, which is what preceded the wedged receiver.
 */
class CastGateTest {
    @Test fun withASessionUpAVideoGoesToTheTv() {
        assertEquals(
            Route.CAST,
            CastGate.route(true, "a1", startPaused = false, sessionLive = true, castingAsset = null),
        )
        assertEquals(
            Route.CAST,
            CastGate.route(true, "a2", startPaused = false, sessionLive = true, castingAsset = "a1"),
        )
    }

    @Test fun musicNeverCasts() {
        assertEquals(
            Route.LOCAL,
            CastGate.route(false, "t1", startPaused = false, sessionLive = true, castingAsset = null),
        )
    }

    @Test fun theFilmBeingCastCannotStartHereWhileTheSessionReconnects() {
        assertEquals(
            Route.BLOCKED,
            CastGate.route(true, "a1", startPaused = false, sessionLive = false, castingAsset = "a1"),
        )
        assertEquals(
            "another film may",
            Route.LOCAL,
            CastGate.route(true, "a2", false, sessionLive = false, castingAsset = "a1"),
        )
    }

    @Test fun aFilmPutBackPausedIsNeverSentOnItsOwn() {
        assertEquals(
            Route.LOCAL,
            CastGate.route(true, "a2", startPaused = true, sessionLive = true, castingAsset = null),
        )
        assertEquals("…nor opened beside its own cast", Route.BLOCKED, CastGate.route(true, "a1", true, true, "a1"))
    }

    @Test fun withoutCastEverythingPlaysHere() {
        assertEquals(
            Route.LOCAL,
            CastGate.route(true, "a1", startPaused = false, sessionLive = false, castingAsset = null),
        )
        assertEquals(
            "no asset to plan",
            Route.LOCAL,
            CastGate.route(true, null, false, sessionLive = true, castingAsset = null),
        )
    }

    private fun playing(isVideo: Boolean, assetId: String? = "a1") = NowPlaying(
        target = PlaybackTarget("https://node.example/x", Credential.Guest, isVideo = isVideo),
        title = "Film",
        assetId = assetId,
        startSeconds = 42.0,
    )

    @Test fun aSessionThatComesUpTakesOverOnlyAPlayingVideo() {
        assertTrue(CastHandoff.takesOver(playing(isVideo = true)))
        assertFalse(CastHandoff.takesOver(playing(isVideo = false)))
        assertFalse(CastHandoff.takesOver(playing(isVideo = true, assetId = null)))
        assertFalse(CastHandoff.takesOver(null))
    }

    @Test fun aHandedOverFilmStartsWhereThePlayerWasAndCarriesItsResumePoint() {
        val item = CastHandoff.itemOf(playing(isVideo = true), startSeconds = 1234.5)!!
        assertEquals(1234.5, item.startSeconds, 0.0)
        assertEquals("a1", item.resume?.assetId)
        assertEquals(
            "a pressed Play's own resume point",
            42.0,
            CastHandoff.itemOf(playing(isVideo = true))!!.startSeconds,
            0.0,
        )
        assertNull(CastHandoff.itemOf(playing(isVideo = true, assetId = null)))
    }
}
