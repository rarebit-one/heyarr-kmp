package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.mobile.consumption.ConsumptionClient
import one.rarebit.heyarr.mobile.consumption.ConsumptionReporter
import one.rarebit.heyarr.mobile.consumption.InMemoryDeviceIdStore
import one.rarebit.heyarr.mobile.consumption.Position
import one.rarebit.heyarr.mobile.consumption.ProgressReporter
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.AudioSessionBridge
import one.rarebit.heyarr.mobile.playback.AudioState
import org.junit.Assert.assertEquals
import org.junit.Test

private class RecordingReporter : ProgressReporter {
    val events = ArrayList<String>()
    override fun begin(assetId: String, verb: String) {
        events.add("begin:$assetId:$verb")
    }
    override fun progressAt(pos: Position) {
        events.add("progress:${pos.locator}")
    }
    override fun pauseAt(pos: Position) {
        events.add("pause:${pos.locator}")
    }
    override fun resumeAt(pos: Position) {
        events.add("resume:${pos.locator}")
    }
    override fun endAt(pos: Position, completed: Boolean) {
        events.add("end:${pos.locator}:$completed")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSessionBridgeTest {
    private val a = AudioItem(assetId = "a1", workId = "w", title = "One", contentUrl = "u")
    private val b = AudioItem(assetId = "a2", workId = "w", title = "Two", contentUrl = "u")

    @Test fun aNewItemIsAListenBeginAndTheOldOneStops() {
        val dispatcher = UnconfinedTestDispatcher()
        val audio = FakeAudioPlayer()
        val rep = RecordingReporter()
        AudioSessionBridge(audio, rep, CoroutineScope(dispatcher))
        audio.flow.value = AudioState(queue = listOf(a, b), index = 0, playing = true, positionMs = 0)
        audio.flow.value = AudioState(queue = listOf(a, b), index = 0, playing = true, positionMs = 30_000)
        audio.flow.value = AudioState(queue = listOf(a, b), index = 0, playing = false, positionMs = 31_000)
        audio.flow.value = AudioState(queue = listOf(a, b), index = 0, playing = true, positionMs = 31_000)
        audio.flow.value = AudioState(queue = listOf(a, b), index = 1, playing = true, positionMs = 0)
        audio.flow.value = AudioState() // queue cleared
        assertEquals(
            listOf("begin:a1:listen", "progress:30", "pause:31", "resume:31", "end:0:false", "begin:a2:listen", "end:0:false"),
            rep.events,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConsumptionReporterOrderingTest {
    private val base = "https://h.example"

    /** Commands enqueued faster than the worker runs still reach the node one at a time, in order. */
    @Test fun transitionsArriveInOrderUnderAStandardDispatcher() = runTest {
        val std = StandardTestDispatcher(testScheduler)
        val t = RoutedTransport(
            mapOf(
                "POST /devices" to HttpResponse(201, """{"id":"d1"}"""),
                "POST /consumption/sessions" to HttpResponse(201, """{"id":"s1"}"""),
                "POST /consumption/sessions/s1/transitions" to HttpResponse(200, """{"id":"s1"}"""),
            ),
        )
        var now = 0.0
        val r = ConsumptionReporter(
            client = ConsumptionClient(t, { base }, { Credential.Session("tok") }),
            store = InMemoryDeviceIdStore(), scope = CoroutineScope(std), baseUrl = { base },
            canWrite = { true }, enrolledDeviceKey = { null }, deviceName = { "phone" }, capabilities = { null },
            io = std, clock = { now },
        )
        r.begin("a1", "watch")
        // Nothing has run yet; queue a burst that must land after the session exists, in order.
        advanceUntilIdle()
        now = 20.0
        r.progress(10.0)
        r.pause(11.0)
        now = 40.0
        r.resume(11.0)
        r.end(12.0, completed = false)
        advanceUntilIdle()
        val transitions = t.calls.filter { it.second.endsWith("/transitions") }.map { c ->
            Regex("\"transition\":\"([a-z]+)\"").find(c.third!!)!!.groupValues[1]
        }
        assertEquals(listOf("start", "progress", "pause", "resume", "stop"), transitions)
    }
}
