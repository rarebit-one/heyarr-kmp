package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.cast.CastTimeline
import one.rarebit.heyarr.core.cast.CastTimeline.Fate
import one.rarebit.heyarr.core.cast.CastTimeline.Verdict
import one.rarebit.heyarr.core.cast.ReceiverReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The receiver policy both apps cast by (heyarr-kmp#248, #249, #250). Times are monotonic
 * nanoseconds, as a caller's clock gives them.
 */
class CastTimelineTest {
    private val second = 1_000_000_000L

    private fun report(position: Double, state: String = "PLAYING", session: Int? = 7, idle: String? = null) =
        ReceiverReport(position, state, session, idle)

    /** Feeds [reports] one every [step] seconds and returns the last judgement. */
    private fun run(
        vararg reports: ReceiverReport,
        step: Long = 2,
        wantsPlay: Boolean = true,
        nativeSeeks: Boolean = false,
    ): CastTimeline.Judgement {
        var progress: CastTimeline.Progress? = null
        var last: CastTimeline.Judgement? = null
        reports.forEachIndexed { i, r ->
            last = CastTimeline.judge(progress, r, i * step * second, wantsPlay, nativeSeeks)
            progress = last.progress
        }
        return last!!
    }

    @Test fun aFirstReportIsABaselineNeverAVerdict() {
        val j = CastTimeline.judge(null, report(100.0, "BUFFERING"), 0, wantsPlay = true)
        assertEquals(Verdict.STEADY, j.verdict)
        assertFalse(j.played)
    }

    @Test fun playingOnIsProofOfHealth() {
        val j = run(report(10.0), report(12.0))
        assertEquals(Verdict.STEADY, j.verdict)
        assertTrue(j.played)
    }

    @Test fun aJumpTheReceiverPlaysOnFromIsLeftToIt() {
        // A seek inside what it buffered: the clock leaps, then moves on.
        val j = run(report(10.0), report(300.0), report(302.0))
        assertEquals(Verdict.STEADY, j.verdict)
        assertTrue(j.played)
    }

    @Test fun aJumpTheReceiverSitsOnIsAnUnreachedSeekAfterTheGrace() {
        val early = run(report(10.0), report(900.0, "BUFFERING"), report(900.0, "BUFFERING"), step = 1)
        assertEquals(Verdict.STEADY, early.verdict, "inside the grace it may still be fetching")
        val late = run(
            report(10.0),
            report(900.0, "BUFFERING"),
            report(900.0, "BUFFERING"),
            report(900.0, "BUFFERING"),
            step = 2,
        )
        assertEquals(Verdict.UNREACHED_SEEK, late.verdict)
    }

    @Test fun anHlsPresentationsFarSeekIsAFreshBaselineAndOnlyAStallFromThereCounts() {
        // heyarr-core#908: the receiver reaches any point of a presentation itself; it waits a
        // few seconds while the node cuts that segment, which is no unreached seek.
        val waiting = Array(4) { report(900.0, "BUFFERING") }
        val early = run(report(10.0), *waiting, nativeSeeks = true)
        assertEquals(Verdict.STEADY, early.verdict)
        assertFalse(early.progress.jumped)
        val wedged = run(report(10.0), *Array(12) { report(900.0, "BUFFERING") }, nativeSeeks = true)
        assertEquals(Verdict.STALLED, wedged.verdict, "still stuck there after the stall window")
    }

    @Test fun aRewindIsAFreshBaseline() {
        val j = run(report(600.0), report(100.0, "BUFFERING"), report(100.0, "BUFFERING"), step = 2)
        assertEquals(Verdict.STEADY, j.verdict)
    }

    @Test fun noProgressWhileMeantToPlayIsAStallAfterTwentySeconds() {
        val frozen = Array(12) { report(50.0, "BUFFERING") }
        assertEquals(Verdict.STALLED, run(report(48.0), *frozen).verdict)
        val short = Array(8) { report(50.0, "BUFFERING") }
        assertEquals(Verdict.STEADY, run(report(48.0), *short).verdict)
    }

    @Test fun pausedTimeNeverCounts() {
        val paused = Array(30) { report(50.0, "PAUSED") }
        assertEquals(Verdict.STEADY, run(report(48.0), *paused, wantsPlay = false).verdict)
        // Pausing resets the stall clock: buffering again after a long pause is a fresh start.
        assertEquals(
            Verdict.STEADY,
            run(report(48.0), *paused, report(50.0, "BUFFERING"), report(50.0, "BUFFERING")).verdict,
        )
    }

    @Test fun nothingIsJudgedWhenTheSenderDoesNotWantPlay() {
        val frozen = Array(20) { report(50.0, "BUFFERING") }
        assertEquals(Verdict.STEADY, run(report(48.0), *frozen, wantsPlay = false).verdict)
    }

    @Test fun aRestartSeekIsClampedToTheFilmAndKeptToTheMillisecond() {
        assertEquals(CastTimeline.RestartSeek(0.0, true), CastTimeline.restartSeek(-5.0, 7200.0, true))
        assertEquals(7200.0, CastTimeline.restartSeek(9000.0, 7200.0, false).position)
        assertEquals(123.457, CastTimeline.restartSeek(123.4567, null, true).position)
        assertEquals(1e9, CastTimeline.restartSeek(1e9, null, true).position, "no duration: no ceiling")
        assertFailsWith<IllegalArgumentException> { CastTimeline.restartSeek(Double.NaN, 10.0, true) }
    }

    @Test fun anotherSessionIsForeign() {
        assertEquals(Fate.FOREIGN, CastTimeline.fateOf(report(1.0, session = 8), 7, 10.0, 100.0, cut = false))
    }

    @Test fun ourSessionPlayingOrLoadingIsOurs() {
        assertEquals(Fate.OURS, CastTimeline.fateOf(report(1.0), 7, 10.0, 100.0, cut = true))
        assertEquals(Fate.OURS, CastTimeline.fateOf(report(0.0, "IDLE"), 7, 10.0, 100.0, cut = false))
    }

    @Test fun aStopIsNeverUndone() {
        val stopped = report(0.0, "IDLE", idle = "CANCELLED")
        assertEquals(Fate.ENDED, CastTimeline.fateOf(stopped, 7, 600.0, 7200.0, cut = true))
    }

    @Test fun anotherSendersLoadOverOurFilmIsLetGoLikeAStop() {
        // INTERRUPTED on the session we own is someone else's LOAD; resuming would reload our
        // film over theirs (heyarr-kmp#242 review). Mid-film and with the stream cut alike.
        val replaced = report(0.0, "IDLE", idle = "INTERRUPTED")
        assertEquals(Fate.ENDED, CastTimeline.fateOf(replaced, 7, 600.0, 7200.0, cut = false))
        assertEquals(Fate.ENDED, CastTimeline.fateOf(replaced, 7, 600.0, 7200.0, cut = true))
        assertEquals(Fate.ENDED, CastTimeline.fateOf(replaced, 7, 600.0, null, cut = true))
    }

    @Test fun anErrorMidFilmIsResumedButNotNearTheEnd() {
        val error = report(0.0, "IDLE", idle = "ERROR")
        assertEquals(Fate.DROPPED, CastTimeline.fateOf(error, 7, 600.0, 7200.0, cut = false))
        assertEquals(Fate.ENDED, CastTimeline.fateOf(error, 7, 7170.0, 7200.0, cut = false))
    }

    @Test fun finishingEarlyIsADroppedStreamFinishingAtTheEndIsTheFilmsEnd() {
        val finished = report(0.0, "IDLE", idle = "FINISHED")
        assertEquals(Fate.DROPPED, CastTimeline.fateOf(finished, 7, 600.0, 7200.0, cut = false))
        assertEquals(Fate.ENDED, CastTimeline.fateOf(finished, 7, 7190.0, 7200.0, cut = false))
        // With no length to compare against, only a known cut says it ended early.
        assertEquals(Fate.ENDED, CastTimeline.fateOf(finished, 7, 600.0, null, cut = false))
        assertEquals(Fate.DROPPED, CastTimeline.fateOf(finished, 7, 600.0, null, cut = true))
    }

    @Test fun aVanishedSessionIsResumedOnlyWhenTheStreamIsKnownCut() {
        assertEquals(Fate.ENDED, CastTimeline.fateOf(null, 7, 600.0, 7200.0, cut = false))
        assertEquals(Fate.DROPPED, CastTimeline.fateOf(null, 7, 600.0, 7200.0, cut = true))
    }

    @Test fun reconnectsBackOffToThirtySeconds() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L), (1..7).map(CastTimeline::backoffSeconds))
        assertEquals(1L, CastTimeline.backoffSeconds(0))
        assertEquals(30L, CastTimeline.backoffSeconds(Int.MAX_VALUE))
    }

    @Test fun theSenderStaysAwakeWhilePlayingAndForAWhileAfterAPause() {
        assertTrue(CastTimeline.holdWanted(owned = true, pausedAt = null, now = 0))
        assertTrue(CastTimeline.holdWanted(owned = true, pausedAt = 0, now = 599 * second))
        assertFalse(CastTimeline.holdWanted(owned = true, pausedAt = 0, now = 600 * second))
        assertFalse(CastTimeline.holdWanted(owned = false, pausedAt = null, now = 0))
    }
}
