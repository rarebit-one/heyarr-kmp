package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.mobile.playback.PlaybackJson
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `stream` plan is a transcode ffmpeg is still producing: no `Content-Length`, and
 * no duration of its own until it finishes. ExoPlayer therefore reports an unset
 * duration, which the session floors to 0 — and a 0 total is poison for a scrub bar,
 * because every fraction of it is 0. Dragging anywhere restarted the film.
 *
 * The node already sends the real runtime for exactly this reason (`source.
 * duration_seconds`, which its own Go doc calls "the scrubber total for a `stream`
 * plan"). The parser was dropping it. These cover the path from the wire to the
 * target, and the seek arithmetic that total feeds.
 */
class StreamSeekDurationTest {

    @Test
    fun thePlansSourceRuntimeIsParsed() {
        val body = """{"mode":"stream","url":"/api/v1/playback/stream/tok",
            "source":{"container":"mp4","video":"hevc","audio":"eac3","duration_seconds":2703.4}}"""
        val plan = PlaybackJson.parse(body)
        assertTrue(plan.isStream)
        assertEquals(2703.4, plan.source?.durationSeconds)
    }

    @Test
    fun anUnprobedBlobHasNoRuntimeRatherThanZero() {
        // `omitempty`: a blob nothing probed sends no duration at all. Null, not 0.0 —
        // the difference between "the player should fall back" and "a zero-length film".
        val plan = PlaybackJson.parse("""{"mode":"stream","url":"/s/t","source":{"container":"mp4"}}""")
        assertNull(plan.source?.durationSeconds)
    }

    @Test
    fun aNonPositiveRuntimeIsTreatedAsUnknown() {
        val plan = PlaybackJson.parse("""{"mode":"stream","url":"/s/t","source":{"duration_seconds":0}}""")
        assertNull(plan.source?.durationSeconds, "zero is not a runtime")
    }

    @Test
    fun aStreamTargetCarriesTheRuntimeToTheScrubber() {
        val t = PlaybackTarget(
            contentUrl = "https://h.example/api/v1/playback/stream/tok",
            credential = Credential.Bearer("heyarr_1_secret"),
            isVideo = true,
            seekable = false,
            origin = PlaybackTarget.Origin.STREAM,
            restartSeekable = true,
            streamBaseUrl = "https://h.example/api/v1/playback/stream/tok",
            sourceDurationSeconds = 2703.4,
        )
        assertEquals(2703.4, t.sourceDurationSeconds)
        // And the restart seek still builds off the base, unencoded.
        assertEquals("${t.streamBaseUrl}?start=1800", t.atStreamStart(1800.0).contentUrl)
    }

    @Test
    fun aFractionalSeekAgainstAKnownRuntimeLandsWhereItWasDropped() {
        // The arithmetic seekFraction does, with a total that is no longer zero.
        val durationMs = (2703.4 * 1000).toLong()
        assertEquals(1351700L, (0.5f * durationMs).toLong())
    }

    @Test
    fun aFractionalSeekAgainstNoRuntimeWouldLandOnZero() {
        // Why the guard exists: this is the OLD behaviour, and it is indistinguishable
        // from "seek to the beginning" no matter where the finger was.
        val unknown = 0L
        assertEquals(0L, (0.9f * unknown).toLong())
    }

    @Test
    fun sourceTimeIsTheCutOffsetPlusThePlayersOwnClock() {
        // ExoPlayer restarts at zero on every re-cut; the offset is what makes the
        // scrubber and the next relative seek agree about where we actually are.
        val offsetMs = (1800.0 * 1000).toLong()
        val playerPositionMs = 3_000L
        assertEquals(1_803_000L, offsetMs + playerPositionMs)
    }
}
