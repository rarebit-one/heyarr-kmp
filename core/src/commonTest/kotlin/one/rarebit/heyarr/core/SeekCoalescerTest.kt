package one.rarebit.heyarr.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.rarebit.heyarr.core.playback.SeekCoalescer
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SeekCoalescerTest {
    @Test fun rapidRelativeSeeksAccumulateWithoutRestartingEveryPress() = runTest {
        val committed = mutableListOf<Double>()
        val seek = SeekCoalescer(this) { committed.add(it) }
        seek.seekBy(600.0, 10.0)
        runCurrent()
        advanceTimeBy(100)
        seek.seekBy(600.0, 10.0)
        runCurrent()
        advanceTimeBy(100)
        seek.seekBy(600.0, -10.0)
        runCurrent()
        advanceTimeBy(249)
        assertEquals(emptyList(), committed)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(610.0), committed)
    }

    @Test fun finalScrubTargetWinsAndTheNextBurstUsesActualPlaybackPosition() = runTest {
        val committed = mutableListOf<Double>()
        val seek = SeekCoalescer(this) { committed.add(it) }
        seek.seekTo(100.0)
        seek.seekTo(900.0)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        seek.seekBy(903.0, 10.0)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf(900.0, 913.0), committed)
    }

    @Test fun changingItemOrStoppingCancelsPendingSeek() = runTest {
        val committed = mutableListOf<Double>()
        val seek = SeekCoalescer(this) { committed.add(it) }
        seek.seekTo(600.0)
        runCurrent()
        seek.cancel()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(emptyList(), committed)
        seek.seekBy(5.0, -10.0)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf(0.0), committed)
    }
}
