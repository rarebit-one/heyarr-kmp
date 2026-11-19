package one.rarebit.heyarr.mobile.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.rarebit.heyarr.core.cast.CastTimeline
import one.rarebit.heyarr.core.cast.ReceiverReport
import one.rarebit.heyarr.core.playback.ResumeItem
import one.rarebit.heyarr.mobile.cast.CastManager.Control
import one.rarebit.heyarr.mobile.playback.PlaybackClient.PlanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The Android Cast manager over a scripted receiver (heyarr-kmp#250): the semantics desktop
 * #249 proved on real hardware — film-absolute restart seeks, a fresh media app, following
 * receiver-side seeks and stalls, resuming a dropped film but never a stop or the film's end —
 * plus what a phone adds: the relay per LOAD, the locked phone, the direct route's fallback,
 * the SDK's lost session and the wake hold.
 */
class CastManagerTest {
    private val second = 1_000_000_000L
    private var now = 0L
    private var signable = true
    private val channel = FakeChannel()
    private val doorways = mutableListOf<Pair<String, FakeDoorway>>()
    private val holds = mutableListOf<CastHold.Hold?>()
    private val recorded = mutableListOf<Pair<CastProgress, Double>>()
    private var connected = 0

    private val film = ResumeItem("a1", workId = "w1", title = "Film", kind = "movie")
    private val source = CastSource("https://node.example/api/v1/playback/stream/tok", "video/mp4", 7200.0)

    private fun manager(plan: CastSource = source, node: InetAddress? = null) = CastManager(
        channel = channel,
        doorway = { _, upstream ->
            FakeDoorway("http://192.168.1.10:4000/media/${doorways.size}").also { doorways += upstream to it }
        },
        resolve = { node },
        canSign = { signable },
        hold = { holds += it },
        clock = { now },
        io = Dispatchers.Unconfined,
        scope = null,
    ).also { m ->
        m.planner = { plan }
        m.onProgress = { _, event, at, _ -> recorded += event to at }
        m.onConnected = { connected++ }
    }

    private fun item(start: Double = 0.0) = CastItem("a1", "Film", start, film)

    /** The receiver reports [position] (its clock) in [state] for our latest LOAD, [step] seconds on; then a poll. */
    private fun CastManager.see(position: Double, state: String = "PLAYING", step: Long = 2, idle: String? = null) =
        runBlocking {
            now += step * second
            channel.report = ReceiverReport(position, state, channel.lastId, idle)
            poll()
        }

    private fun play(m: CastManager, start: Double = 0.0) = runBlocking { m.play(item(start)).getOrThrow() }

    @Test fun aCastIsRelayedFromItsResumePointAndDeclaresWhatRemains() {
        val m = manager()
        play(m, start = 600.0)
        val load = channel.loads.single()
        assertEquals(
            "the receiver fetches this phone's doorway, from the film's point",
            "http://192.168.1.10:4000/media/0?start=600",
            load.url,
        )
        assertEquals("the doorway relays the node's stream", source.url, doorways.single().first)
        assertEquals("LOAD declares what remains of the film", 6600.0, load.durationSeconds!!, 0.0)
        assertEquals("video/mp4", load.mime)
        assertTrue(m.state.value.route is CastRoute.Relay)
        assertEquals("a1", m.castingAsset)
        assertEquals("Living room", m.castingTo)
        assertEquals("relaying holds the phone awake", CastHold.Hold("Film", "Living room", locks = true), holds.last())
    }

    @Test fun shownPositionAndDurationAreFilmAbsoluteNeverTheBufferedEnd() {
        val m = manager()
        play(m, start = 600.0)
        m.see(30.0)
        assertEquals(630.0, m.state.value.position, 0.0)
        assertEquals(7200.0, m.state.value.duration!!, 0.0)
        assertEquals("ticks are recorded film-absolute", CastProgress.TICK to 630.0, recorded.last())
    }

    @Test fun aSeekRestartsThroughANewDoorwayAndClosesTheOldOne() {
        val m = manager()
        play(m)
        runBlocking { m.control(Control.SEEK, 1800.0).getOrThrow() }
        assertEquals("http://192.168.1.10:4000/media/1?start=1800", channel.loads[1].url)
        assertEquals(5400.0, channel.loads[1].durationSeconds!!, 0.0)
        assertTrue("the old doorway serves nothing more", doorways[0].second.closed)
        assertFalse(doorways[1].second.closed)
        m.see(10.0)
        assertEquals(1810.0, m.state.value.position, 0.0)
        runBlocking {
            m.control(Control.PAUSE).getOrThrow()
            m.control(Control.SEEK, 60.0).getOrThrow()
        }
        assertFalse("a paused film stays paused where it lands", channel.loads.last().autoplay)
    }

    @Test fun aMediaAppWeDoNotOwnIsQuitBeforeCastingAndAnEmptyOneIsNot() {
        channel.report = ReceiverReport(100.0, "PLAYING", 99)
        play(manager())
        assertEquals("someone else's (or a wedged) media app is relaunched", 1, channel.relaunches)
        val fresh = FakeChannel()
        val m = CastManager(fresh, { _, _ -> FakeDoorway("http://x/media/a") }, io = Dispatchers.Unconfined).also {
            it.planner =
                { source }
        }
        runBlocking { m.play(item()).getOrThrow() }
        assertEquals("a freshly launched app is used as it is", 0, fresh.relaunches)
    }

    @Test fun anotherSendersMediaReleasesOursAndLeavesItsResumePoint() {
        val m = manager()
        play(m)
        m.see(100.0)
        runBlocking {
            channel.report = ReceiverReport(5.0, "PLAYING", 0)
            m.poll()
        }
        assertNull(m.state.value.item)
        assertNull(m.castingAsset)
        assertEquals(CastProgress.CHECKPOINT to 100.0, recorded.last())
        assertNull("nothing left to relay", holds.last())
        assertEquals("never reloaded over someone else's film", 1, channel.loads.size)
    }

    @Test fun aReceiverJumpPastWhatItBufferedRestartsTheFilmThere() {
        val m = manager()
        play(m)
        m.see(10.0)
        m.see(12.0)
        m.see(900.0, "BUFFERING")
        m.see(900.0, "BUFFERING")
        assertEquals("inside the grace it may still be fetching", 1, channel.loads.size)
        m.see(900.0, "BUFFERING")
        assertEquals(2, channel.loads.size)
        assertTrue(channel.loads[1].url.endsWith("?start=900"))
        assertEquals(CastRecovery.Kind.SEEKING, m.state.value.recovery?.kind)
        m.see(3.0)
        assertEquals(903.0, m.state.value.position, 0.0)
    }

    @Test fun seeksTheReceiverCanServeFromItsBufferAreLeftToIt() {
        val m = manager()
        play(m)
        m.see(10.0)
        m.see(300.0)
        m.see(302.0)
        m.see(100.0, "BUFFERING")
        m.see(102.0)
        assertEquals(1, channel.loads.size)
    }

    @Test fun aStalledReceiverIsRelaunchedWhereItWasAtMostTwice() {
        val m = manager()
        play(m)
        m.see(50.0)
        repeat(11) { m.see(50.0, "BUFFERING") }
        assertEquals(1, channel.relaunches)
        assertTrue(channel.loads[1].url.endsWith("?start=50"))
        assertEquals(CastRecovery.Kind.RESTARTING, m.state.value.recovery?.kind)
        repeat(11) { m.see(0.0, "BUFFERING") }
        assertEquals(2, channel.relaunches)
        repeat(11) { m.see(0.0, "BUFFERING") }
        assertEquals("the budget is spent: the user is asked to step in", 2, channel.relaunches)
        assertEquals(CastRecovery.Kind.GAVE_UP, m.state.value.recovery?.kind)
        assertTrue(m.state.value.error!!.contains("keeps stalling"))
    }

    @Test fun pausedTimeNeverCountsAsAStall() {
        val m = manager()
        play(m)
        m.see(50.0)
        runBlocking { m.control(Control.PAUSE).getOrThrow() }
        repeat(30) { m.see(50.0, "PAUSED") }
        assertEquals(1, channel.loads.size)
        assertEquals(CastProgress.CHECKPOINT to 50.0, recorded.first { it.first == CastProgress.CHECKPOINT })
    }

    @Test fun aUserStopIsFinal() {
        val m = manager()
        play(m)
        m.see(100.0)
        runBlocking { m.control(Control.STOP).getOrThrow() }
        assertTrue(channel.commands.contains("stop"))
        assertNull(m.state.value.item)
        assertEquals(CastProgress.CHECKPOINT to 100.0, recorded.last())
        runBlocking { m.poll() }
        assertEquals("no resume after a stop", 1, channel.loads.size)
    }

    @Test fun aStopFromTheTvsRemoteIsFinalToo() {
        val m = manager()
        play(m)
        m.see(100.0)
        m.see(0.0, "IDLE", idle = "CANCELLED")
        assertNull(m.state.value.item)
        assertEquals(1, channel.loads.size)
        assertEquals(0, channel.relaunches)
    }

    @Test fun theFilmsRealEndIsNotResumed() {
        val m = manager()
        play(m, start = 7100.0)
        m.see(80.0)
        m.see(0.0, "IDLE", idle = "FINISHED")
        assertNull(m.state.value.item)
        assertEquals(CastProgress.ENDED to 7180.0, recorded.last())
        assertEquals(1, channel.loads.size)
    }

    @Test fun aFilmDroppedMidWayIsPutBackInAFreshMediaAppAndGivenUpOnAfterThree() {
        val m = manager()
        play(m)
        m.see(100.0)
        m.see(0.0, "IDLE", idle = "ERROR")
        assertEquals(1, channel.relaunches)
        assertTrue(channel.loads[1].url.endsWith("?start=100"))
        assertEquals(CastRecovery.Kind.RESUMING, m.state.value.recovery?.kind)
        repeat(2) { m.see(0.0, "IDLE", idle = "ERROR") }
        assertEquals(4, channel.loads.size)
        m.see(0.0, "IDLE", idle = "ERROR")
        assertEquals("three resumes that never played on: let it go", 4, channel.loads.size)
        assertNull(m.state.value.item)
        assertEquals(CastRecovery.Kind.GAVE_UP, m.state.value.recovery?.kind)
    }

    @Test fun aSeveredRelayRestartsTheFilmWhileTheReceiverStillHasBuffer() {
        val m = manager()
        play(m)
        m.see(100.0)
        doorways.last().second.severed = true
        m.see(102.0)
        assertEquals(2, channel.loads.size)
        assertTrue(channel.loads[1].url.endsWith("?start=102"))
        assertEquals("its own session still plays ours: no relaunch", 0, channel.relaunches)
    }

    @Test fun aLockedPhoneWaitsForTheUnlockInsteadOfReloadingIntoARefusal() {
        val m = manager()
        play(m)
        m.see(100.0)
        doorways.last().second.severed = true
        signable = false
        m.see(102.0)
        m.see(104.0)
        assertEquals("nothing reloaded while locked", 1, channel.loads.size)
        assertEquals(CastRecovery.Kind.WAITING_FOR_UNLOCK, m.state.value.recovery?.kind)
        signable = true
        m.see(106.0)
        assertEquals(2, channel.loads.size)
        assertTrue(channel.loads[1].url.endsWith("?start=106"))
        runBlocking {
            signable = false
            val seek = m.control(Control.SEEK, 3000.0)
            assertTrue("a seek says why it cannot", seek.exceptionOrNull() is CastLocked)
        }
    }

    @Test fun aDirectCastTheReceiverCannotFetchFallsBackToTheRelay() {
        val free = source.copy(credentialFree = true)
        channel.failLoad = { load -> load.url.startsWith(free.url) }
        val m = manager(plan = free, node = v4("192.168.1.5"))
        play(m, start = 30.0)
        assertEquals("only the relayed LOAD landed", 1, channel.loads.size)
        assertEquals("http://192.168.1.10:4000/media/0?start=30", channel.loads.single().url)
        assertTrue(m.state.value.route is CastRoute.Relay)
        // …and stays relayed: the next seek does not try the node again.
        channel.failLoad = null
        runBlocking { m.control(Control.SEEK, 60.0).getOrThrow() }
        assertTrue(channel.loads.last().url.startsWith("http://192.168.1.10:4000/media/1"))
    }

    @Test fun aDirectCastNeedsNoRelayAndNoHold() {
        val free = source.copy(credentialFree = true)
        val m = manager(plan = free, node = v4("10.0.0.5"))
        play(m)
        assertEquals(free.url, channel.loads.single().url)
        assertTrue(doorways.isEmpty())
        assertTrue(m.state.value.route is CastRoute.Direct)
        assertNull("the phone is not in the path", holds.last())
    }

    @Test fun aLostSessionIsRejoinedWithABackoffAndLetGoAfterTenMinutes() {
        val m = manager()
        play(m)
        m.see(100.0)
        channel.drop()
        channel.rejoinResult = false
        runBlocking { m.sessionEnded(lost = true) }
        assertEquals("still ours meanwhile: the gate keeps deferring to the cast", "a1", m.castingAsset)
        runBlocking { m.poll() }
        assertEquals(1, channel.rejoins)
        runBlocking { m.poll() }
        assertEquals("backing off", 1, channel.rejoins)
        now += second
        runBlocking { m.poll() }
        assertEquals(2, channel.rejoins)
        now += CastTimeline.RECONNECT_GIVE_UP_SECONDS * second
        runBlocking { m.poll() }
        assertNull(m.castingAsset)
        assertTrue(m.state.value.error!!.startsWith("Lost Living room"))
    }

    @Test fun aRejoinedReceiverGetsTheFilmBackWhereItWas() {
        val m = manager()
        play(m)
        m.see(100.0)
        channel.drop()
        runBlocking {
            m.sessionEnded(lost = true)
            m.poll()
        }
        assertEquals(1, channel.rejoins)
        assertEquals(2, channel.loads.size)
        assertTrue(channel.loads[1].url.endsWith("?start=100"))
        assertEquals(CastRecovery.Kind.RECONNECTED, m.state.value.recovery?.kind)
    }

    @Test fun endingTheSessionIsAStop() {
        val m = manager()
        play(m)
        m.see(100.0)
        channel.drop()
        runBlocking {
            m.sessionEnded(lost = false)
            m.poll()
        }
        assertNull(m.castingAsset)
        assertEquals(0, channel.rejoins)
        assertEquals(CastProgress.CHECKPOINT to 100.0, recorded.last())
    }

    @Test fun whileReconnectingOnlyStopWorks() {
        val m = manager()
        play(m)
        channel.live = false
        runBlocking {
            assertTrue(m.control(Control.PAUSE).exceptionOrNull()!!.message!!.startsWith("Reconnecting"))
            m.control(Control.STOP).getOrThrow()
        }
        assertNull(m.castingAsset)
    }

    @Test fun theHoldFollowsPlayAndLetsGoOfTheLocksAfterALongPause() {
        val m = manager()
        play(m)
        assertTrue(holds.last()!!.locks)
        runBlocking { m.control(Control.PAUSE).getOrThrow() }
        assertTrue("a short pause keeps them: the TV's remote may resume it", holds.last()!!.locks)
        m.see(0.0, "PAUSED", step = CastTimeline.PAUSE_GRACE_SECONDS + 1)
        assertFalse(holds.last()!!.locks)
        runBlocking { m.control(Control.RESUME).getOrThrow() }
        assertTrue(holds.last()!!.locks)
        runBlocking { m.control(Control.STOP).getOrThrow() }
        assertNull(holds.last())
    }

    @Test fun aSessionThatComesUpWithNothingOfOursHandsOverOnlyThen() {
        val m = manager()
        runBlocking { m.sessionLive() }
        assertEquals(1, connected)
        play(m)
        runBlocking { m.sessionLive() }
        assertEquals("a reconnect to our own film is not a hand-over", 1, connected)
    }

    @Test fun aPlanThatIsNotCastSafeIsRefusedBeforeAnythingIsLoaded() {
        val m = manager()
        m.planner = { CastRouting.sourceOf(PlanResult.Direct(null, null)) }
        val result = runBlocking { m.play(item()) }
        assertTrue(result.isFailure)
        assertTrue(channel.loads.isEmpty())
        assertEquals(0, channel.relaunches)
        assertTrue(m.state.value.error!!.contains("Cast"))
    }

    @Test fun nothingCastsWithoutASession() {
        channel.live = false
        val result = runBlocking { manager().play(item()) }
        assertEquals("No Cast receiver is connected", result.exceptionOrNull()?.message)
    }

    private fun v4(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address

    private inner class FakeChannel : CastChannel {
        override var receiver: CastReceiver? = CastReceiver("tv1", "Living room", v4("192.168.1.50"))
        override var live = true
        var report: ReceiverReport? = null
        val loads = mutableListOf<CastLoad>()
        val commands = mutableListOf<String>()
        var relaunches = 0
        var rejoins = 0
        var rejoinResult = true
        var failLoad: ((CastLoad) -> Boolean)? = null
        var lastId = 0
        private var next = 1

        override fun report() = report

        override suspend fun load(load: CastLoad): Int {
            check(failLoad?.invoke(load) != true) { "LOAD_FAILED" }
            loads += load
            lastId = next++
            report = ReceiverReport(0.0, if (load.autoplay) "PLAYING" else "PAUSED", lastId)
            return lastId
        }

        override suspend fun play() {
            commands += "play"
            report = report?.copy(state = "PLAYING")
        }

        override suspend fun pause() {
            commands += "pause"
            report = report?.copy(state = "PAUSED")
        }

        override suspend fun stop() {
            commands += "stop"
            report = report?.copy(state = "IDLE", idleReason = "CANCELLED")
        }

        override suspend fun relaunch(): Boolean {
            relaunches++
            report = null
            return true
        }

        override suspend fun rejoin(receiver: CastReceiver): Boolean {
            rejoins++
            if (rejoinResult) {
                live = true
                this.receiver = receiver
                report = null
            }
            return rejoinResult
        }

        /** The session went away: no receiver, nothing live. */
        fun drop() {
            live = false
            receiver = null
            report = null
        }
    }

    private class FakeDoorway(override val url: String) : MediaDoorway {
        override var severed = false
        override var credentialBlocked = false
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
