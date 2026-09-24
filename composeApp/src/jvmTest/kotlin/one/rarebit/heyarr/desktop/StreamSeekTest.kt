package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.playback.PlayerEvents
import one.rarebit.heyarr.desktop.playback.PlayerState
import one.rarebit.heyarr.desktop.playback.StreamSeek
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A node-repackaged transcode stream has no length and no ranges, so mpv can only
 * seek inside what it has already demuxed — dragging the scrubber past the buffer
 * did nothing at all. ADR-0069's answer is to re-cut the stream from the new offset
 * (`?start=<s>`), which costs a fresh ffmpeg but reaches anywhere in the runtime.
 *
 * Two things have to be right for that to feel like a seek rather than a restart:
 * the URL, and the clock. mpv's clock goes back to zero on every re-cut, so the
 * player shifts it by where the cut began — otherwise a scrub to 40 minutes in
 * would show 0:00 and the next ±10 s would jump to the top of the film.
 */
class StreamSeekTest {
    private val base = "https://h.example/api/v1/playback/stream/tok123"

    @Test
    fun theFirstPlayIsThePlainUrl() {
        // Zero (and a nonsense negative) must not append `?start=0`: the plan's URL
        // is what the token was minted for and it plays from the top unaided.
        assertEquals(base, StreamSeek.urlAt(base, 0.0))
        assertEquals(base, StreamSeek.urlAt(base, -5.0))
    }

    @Test
    fun aSeekAppendsTheOffsetInSeconds() {
        assertEquals("$base?start=90", StreamSeek.urlAt(base, 90.0))
        assertEquals("$base?start=2703", StreamSeek.urlAt(base, 2703.0))
    }

    @Test
    fun aFractionalOffsetKeepsUpToThreeDecimalsAndNoTrailingZeros() {
        assertEquals("$base?start=12.5", StreamSeek.urlAt(base, 12.5))
        // Rounded, not truncated (%.3f is half-up) — the same arithmetic the phone's
        // formatSeconds does, so a seek means the same instant on either client.
        assertEquals("$base?start=12.346", StreamSeek.urlAt(base, 12.3456))
        assertEquals("$base?start=12.3", StreamSeek.urlAt(base, 12.2999))
    }

    @Test
    fun anExistingQueryTakesAnAmpersand() {
        assertEquals("$base?x=1&start=30", StreamSeek.urlAt("$base?x=1", 30.0))
    }

    @Test
    fun theTokenIsNeverPercentEncoded() {
        // The #16 trap: the path goes on the wire verbatim, colons and all.
        val withColon = "https://h.example/api/v1/playback/stream/blake3:abc"
        assertEquals("$withColon?start=10", StreamSeek.urlAt(withColon, 10.0))
    }

    // ── the clock ────────────────────────────────────────────────────────────────

    private fun timePos(seconds: Double) = """{"event":"property-change","name":"time-pos","data":$seconds}"""

    @Test
    fun aDirectFilesPositionIsMpvsOwn() {
        val s = PlayerEvents.apply(PlayerState(), timePos(42.0))
        assertEquals(42.0, s.position)
    }

    @Test
    fun aReCutStreamsPositionIsShiftedToSourceTime() {
        // mpv says 3 s into a stream that was cut at 1800 s: the film is at 30:03.
        val s = PlayerEvents.apply(PlayerState(), timePos(3.0), sourceOffset = 1800.0)
        assertEquals(1803.0, s.position)
    }

    @Test
    fun theBufferedBandIsShiftedTheSameWay() {
        val line = """{"event":"property-change","name":"demuxer-cache-time","data":12.0}"""
        assertEquals(1812.0, PlayerEvents.apply(PlayerState(), line, sourceOffset = 1800.0).bufferedTo)
    }

    @Test
    fun warmUpIsJudgedOnMpvsClockNotTheShiftedOne() {
        // A seek deep into a film must still WARM UP: the fallback that ends warm-up
        // reads how far into this cut we are, so a big offset cannot fake a started
        // picture before a frame has played.
        val cut = PlayerEvents.apply(PlayerState(), timePos(0.1), sourceOffset = 1800.0)
        assertEquals(false, cut.hasStarted, "0.1 s into the cut is still warming up")
        val playing = PlayerEvents.apply(cut, timePos(0.9), sourceOffset = 1800.0)
        assertEquals(true, playing.hasStarted, "past the first second the picture is up")
    }

    @Test
    fun theBufferedBandBoundsWhatCanBeSeekedWithoutReCutting() {
        // The decision the player makes on every stream seek: inside the cut AND
        // inside what the demuxer has read ahead is mpv's own seek (instant); past
        // the frontier there are no bytes, and only a fresh ffmpeg can produce them.
        // This is the rule in PlayerState terms; EmbeddedPlayer applies it.
        val cutAt = 1800.0
        val s = PlayerState(duration = 3600.0, position = 1805.0, bufferedTo = 1830.0)
        val margin = 1.0
        fun buffered(at: Double) = at >= cutAt && at <= s.bufferedTo - margin

        assertEquals(true, buffered(1810.0), "ahead but inside the cache: a native seek")
        assertEquals(true, buffered(1800.0), "the start of the cut is still in hand")
        assertEquals(false, buffered(1829.5), "right on the frontier would only stall")
        assertEquals(false, buffered(2400.0), "past the cache: those bytes do not exist yet")
        assertEquals(false, buffered(600.0), "before the cut: ffmpeg was never asked for it")
    }

    @Test
    fun theScrubberFractionUsesSourceTimeAgainstThePinnedRuntime() {
        // The pinned total is the source runtime, so a shifted position lands the
        // playhead where it was dropped instead of back at the left edge.
        val s = PlayerState(duration = 3600.0, position = 1800.0)
        assertEquals(0.5f, s.fraction)
    }
}
