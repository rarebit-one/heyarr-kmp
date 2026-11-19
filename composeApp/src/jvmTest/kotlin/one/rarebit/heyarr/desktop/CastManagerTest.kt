package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.cast.CastChannel
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.cast.CastManager
import one.rarebit.heyarr.desktop.cast.CastRecovery
import one.rarebit.heyarr.desktop.cast.CastStatus
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastManagerTest {
    private val address = InetAddress.getByName("127.0.0.1") as java.net.Inet4Address
    private val first = CastDevice("first", "First", address, 8009)
    private val second = CastDevice("second", "Second", address, 8009)

    @Test
    fun suppliedDiscoveryRunsAndPublishesItsDevices() {
        var calls = 0
        CastManager({ "http://127.0.0.1:1/loopback" }, discoverDevices = {
            calls++
            listOf(first)
        }, watch = noWatch).use { manager ->
            assertEquals(emptyList(), manager.devices)
            assertEquals(listOf(first), manager.discover())
            assertEquals(listOf(first), manager.devices)
            assertEquals(1, calls)
        }
    }
    private val api = HeyarrApi(
        object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(
                    200,
                    """{"mode":"stream","mime":"video/mp4","url":"/api/v1/playback/stream/cap",""" +
                        """"source":{"duration_seconds":120}}""",
                )
        },
        "https://example.invalid",
        Credential.Guest,
    )

    private val noWatch: (() -> Unit) -> AutoCloseable = { AutoCloseable {} }

    private class FakeChannel : CastChannel {
        var closed = false
        var failLoad = false
        var lastLoad: Pair<String, Boolean>? = null
        var lastDuration: Double? = null
        var loadCount = 0
        var receiverStops = 0
        var refuseStop = false
        val events = mutableListOf<String>()
        var lastControl: Pair<String, Double?>? = null
        override var status: CastStatus? = CastStatus("PLAYING", 0.0, null, "Example", 1)
        override val error: String? = null
        override fun load(url: String, title: String, autoplay: Boolean, start: Double, duration: Double?) {
            if (failLoad) error("LOAD_FAILED")
            loadCount++
            events += "load"
            lastLoad = url to autoplay
            lastDuration = duration
            status = CastStatus(if (autoplay) "PLAYING" else "PAUSED", 0.0, null, title, loadCount + 1)
        }
        override fun stopReceiver() {
            if (refuseStop) error("INVALID_REQUEST")
            receiverStops++
            events += "stop-receiver"
            status = null
        }
        override fun control(action: String, seconds: Double?) {
            lastControl = action to seconds
            if (action == "pause") status = status?.copy(state = "PAUSED")
            if (action == "resume") status = status?.copy(state = "PLAYING")
        }
        override fun refresh(): CastStatus? = status
        override fun close() {
            closed = true
        }
    }

    @Test
    fun attachingAnotherReceiverPreservesOwnedPlaybackUntilExplicitTransfer() {
        val original = FakeChannel()
        val replacement = FakeChannel()
        CastManager({ "http://127.0.0.1:1/loopback" }, { device ->
            if (device.id == first.id) original else replacement
        }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset", "Example")
            val refusal = assertFails { manager.attach(second) }
            assertTrue(refusal.message.orEmpty().contains("Stop casting"))
            assertEquals(first.id, manager.selected?.id)
            assertEquals(false, original.closed)
            assertEquals(false, replacement.closed)
            manager.control("seek", 10.0)
            assertEquals(2, original.loadCount, "The original relay and seek plan remain owned")

            manager.play(second, api, "asset", "Transfer")
            assertEquals(second.id, manager.selected?.id)
            assertEquals(true, original.closed)
            assertEquals(1, replacement.loadCount)
        }
    }

    @Test
    fun failedSwitchDropsClosedConnectionAndRetryCreatesANewOne() {
        val made = mutableListOf<FakeChannel>()
        var failSecond = true
        CastManager({ "http://127.0.0.1:1/loopback" }, { device ->
            if (device.id == second.id && failSecond) error("Connection refused")
            FakeChannel().also(made::add)
        }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset", "Example")
            assertFails { manager.play(second, api, "asset", "Example") }
            assertTrue(made.single().closed)
            assertNull(manager.selected)
            failSecond = false
            manager.play(second, api, "asset", "Example")
            assertEquals(second.id, manager.selected?.id)
            assertEquals(2, made.size)
        }
        assertTrue(made.last().closed)
    }

    @Test
    fun pausedRestartSeekLoadsNewCapabilityWithoutAutoplayAndKeepsSourceTime() {
        val channel = FakeChannel()
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset", "Example")
            manager.control("pause")
            manager.control("seek", 35.0)
            assertEquals(false, channel.lastLoad?.second)
            assertTrue(channel.lastLoad!!.first.contains("?start=35.0"))
            assertEquals(35.0, manager.status?.position)
            assertEquals(120.0, manager.status?.duration)
        }
    }

    @Test
    fun directMp4IsRefusedBeforeConnectingBecauseItsCodecsAreUnknown() {
        val direct = HeyarrApi(
            object : HttpTransport {
                override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
                override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                    HttpResponse(200, """{"mode":"direct","mime":"video/mp4","url":"/api/v1/blobs/hash/content"}""")
            },
            "https://example.invalid",
            Credential.Guest,
        )
        var connected = false
        CastManager({ "http://127.0.0.1:1/loopback" }, {
            connected = true
            FakeChannel()
        }, watch = noWatch).use { manager ->
            assertFails { manager.play(first, direct, "asset") }
            assertEquals(false, connected)
        }
    }

    @Test
    fun failedLoadOnAnotherReceiverCannotReuseThePreviousSeekOffset() {
        val firstChannel = FakeChannel()
        val secondChannel = FakeChannel().apply { failLoad = true }
        CastManager({ "http://127.0.0.1:1/loopback" }, { device ->
            if (device.id == first.id) firstChannel else secondChannel
        }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset")
            manager.control("seek", 40.0)
            assertEquals(40.0, manager.status?.position)
            assertFails { manager.play(second, api, "asset") }
            assertNull(manager.status)
            assertEquals(second.id, manager.selected?.id)
            secondChannel.status = CastStatus("IDLE", 0.0, null, null, null, "ERROR")
            manager.refresh()
            assertEquals(0.0, manager.status?.position)
            assertEquals("Cast receiver reported ERROR", manager.error)
        }
    }

    @Test
    fun attachReadsAndControlsAnExistingSessionWithoutLoadingMedia() {
        val channel = FakeChannel().apply { status = CastStatus("PAUSED", 23.0, 120.0, "Existing", 44) }
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }, watch = noWatch).use { manager ->
            assertEquals("PAUSED", manager.attach(first)?.state)
            assertNull(channel.lastLoad)
            manager.control("resume")
            assertEquals("PLAYING", manager.status?.state)
            assertNull(channel.lastLoad)
        }
    }

    @Test
    fun anotherSendersMediaCannotInheritOurOffsetDurationOrRestartPlan() {
        val channel = FakeChannel()
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset", "First film")
            manager.control("seek", 40.0)
            assertEquals(40.0, manager.status?.position)
            assertEquals(2, channel.loadCount)

            channel.status = CastStatus("PLAYING", 5.0, 900.0, "Another film", 77)
            manager.refresh()
            assertEquals(5.0, manager.status?.position)
            assertEquals(900.0, manager.status?.duration)
            assertEquals("Another film", manager.status?.title)
            manager.control("seek", 10.0)
            assertEquals("seek" to 10.0, channel.lastControl)
            assertEquals(2, channel.loadCount, "A foreign session must not LOAD the previous film")
            assertEquals(false, channel.closed, "Releasing our media must not disconnect the new session")
        }
    }

    @Test
    fun aForeignSessionObservedBeforeTheNextPollCannotReloadOurPreviousFilm() {
        val channel = FakeChannel()
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset")
            channel.status = CastStatus("PAUSED", 12.0, 900.0, "Another film", 88)
            manager.control("seek", 25.0)
            assertEquals("seek" to 25.0, channel.lastControl)
            assertEquals(1, channel.loadCount)
            assertEquals(900.0, manager.status?.duration)
        }
    }

    @Test
    fun anOlderResetCannotClosePlaybackStartedAfterTheLatestReset() {
        val callbacks = mutableListOf<() -> Unit>()
        val made = mutableListOf<FakeChannel>()
        CastManager(
            { "http://127.0.0.1:1/loopback" },
            { FakeChannel().also(made::add) },
            { callbacks.add(it) },
            watch = noWatch,
        ).use { manager ->
            manager.play(first, api, "asset")
            manager.invalidate()
            manager.invalidate()
            assertFails { manager.play(first, api, "asset") }
            callbacks[1]()
            assertTrue(made.single().closed)

            manager.play(first, api, "asset", "New playback")
            val replacement = made.last()
            callbacks[0]()
            assertEquals(false, replacement.closed)
            assertEquals(first.id, manager.selected?.id)
            manager.control("pause")
            assertEquals("PAUSED", manager.status?.state)
            assertEquals(1, replacement.loadCount)
        }
    }

    // ── heyarr-kmp#248: receiver-side seeks, wedged receivers, declared duration ──────────

    /** A manual clock, so a stall is a few lines, not twenty real seconds. */
    private class Clock {
        var now = 0L
        fun advance(seconds: Double) {
            now += (seconds * TimeUnit.SECONDS.toNanos(1)).toLong()
        }
    }

    /** Reports [position] (receiver time, relative to the last LOAD) in the current media session. */
    private fun FakeChannel.report(state: String, position: Double) {
        status = requireNotNull(status).copy(state = state, position = position)
    }

    private fun watched(channel: FakeChannel, clock: Clock, plan: HeyarrApi = api) = CastManager(
        { "http://127.0.0.1:1/loopback" },
        { channel },
        clock = { clock.now },
        watch = noWatch,
    ).also { it.play(first, plan, "asset", "Example") }

    @Test
    fun loadsDeclareTheFilmDurationAndARestartDeclaresWhatRemains() {
        val channel = FakeChannel()
        watched(channel, Clock()).use { manager ->
            assertEquals(120.0, channel.lastDuration, "The receiver's scrub bar must span the film")
            manager.control("seek", 35.0)
            assertEquals(85.0, channel.lastDuration)
        }
    }

    @Test
    fun shownPositionAndDurationAreFilmAbsoluteNeverTheBufferedEnd() {
        val channel = FakeChannel()
        watched(channel, Clock()).use { manager ->
            manager.control("seek", 60.0)
            // A live repackage's receiver duration tracks only what it has buffered.
            channel.status = requireNotNull(channel.status).copy(position = 4.0, duration = 36.0)
            manager.refresh()
            assertEquals(64.0, manager.status?.position)
            assertEquals(120.0, manager.status?.duration)
        }
        val unknown = HeyarrApi(
            object : HttpTransport {
                override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
                override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                    HttpResponse(200, """{"mode":"stream","mime":"video/mp4","url":"/api/v1/playback/stream/cap"}""")
            },
            "https://example.invalid",
            Credential.Guest,
        )
        val other = FakeChannel()
        watched(other, Clock(), unknown).use { manager ->
            other.status = requireNotNull(other.status).copy(position = 3.5, duration = 3.5)
            manager.refresh()
            assertNull(manager.status?.duration, "An unknown length is not the buffered end")
            assertNull(other.lastDuration)
        }
    }

    @Test
    fun aMediaAppWeDoNotOwnIsQuitBeforeCastingSoAWedgedSessionIsNeverReused() {
        val channel = FakeChannel() // Starts with someone's PLAYING media session 1.
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset", "Example")
            assertEquals(listOf("stop-receiver", "load"), channel.events)

            channel.events.clear()
            manager.play(first, api, "other", "Next film")
            assertEquals(listOf("load"), channel.events, "Our own healthy session is reused")

            channel.events.clear()
            channel.report("BUFFERING", 0.3)
            manager.play(first, api, "other", "Cast again")
            assertEquals(listOf("stop-receiver", "load"), channel.events, "A stuck session is never reused")
        }
    }

    @Test
    fun aReceiverThatRefusesToQuitStillGetsTheLoad() {
        val channel = FakeChannel().apply { refuseStop = true }
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }, watch = noWatch).use { manager ->
            manager.play(first, api, "asset", "Example")
            assertEquals(listOf("load"), channel.events)
            assertEquals("asset", manager.castingAsset)
        }
    }

    @Test
    fun aReceiverJumpPastWhatItBufferedRestartsTheFilmThere() {
        val channel = FakeChannel()
        val clock = Clock()
        watched(channel, clock).use { manager ->
            manager.control("seek", 60.0)
            val loads = channel.loadCount
            channel.report("PLAYING", 10.0)
            manager.refresh()
            // The TV remote seeks to stream time 50 (film 110): far past where 2s of play reach.
            clock.advance(2.0)
            channel.report("BUFFERING", 50.0)
            manager.refresh()
            assertEquals(110.0, manager.status?.position)
            assertEquals(loads, channel.loadCount, "A seek may still land in the buffer; give it a moment")

            clock.advance(CastManager.SEEK_GRACE_SECONDS.toDouble())
            manager.refresh()
            assertEquals(loads + 1, channel.loadCount)
            assertEquals("start=110.0", channel.lastLoad!!.first.substringAfter("?"))
            assertEquals(true, channel.lastLoad!!.second)
            assertEquals(1, channel.receiverStops, "Only the first Cast quit a foreign app; a seek reloads")
            assertEquals(CastRecovery(CastRecovery.Kind.SEEKING, "First", 110.0), manager.recovery)
            assertEquals(110.0, manager.status?.position)

            // Playing on from the restart point clears the notice.
            channel.report("PLAYING", 0.0)
            manager.refresh()
            clock.advance(2.0)
            channel.report("PLAYING", 2.0)
            manager.refresh()
            assertNull(manager.recovery)
            assertEquals(112.0, manager.status?.position)
        }
    }

    @Test
    fun seeksTheReceiverCanServeFromItsBufferAreLeftToIt() {
        val channel = FakeChannel()
        val clock = Clock()
        watched(channel, clock).use { manager ->
            val loads = channel.loadCount
            channel.report("PLAYING", 30.0)
            manager.refresh()
            clock.advance(2.0)
            channel.report("PLAYING", 45.0) // forward, within what it buffered: plays on
            manager.refresh()
            clock.advance(2.0)
            channel.report("PLAYING", 47.0)
            manager.refresh()
            clock.advance(2.0)
            channel.report("PLAYING", 12.0) // a rewind
            manager.refresh()
            clock.advance(CastManager.STALL_SECONDS - 1.0)
            channel.report("BUFFERING", 12.0)
            manager.refresh()
            assertEquals(loads, channel.loadCount)
            assertNull(manager.recovery)
            assertEquals(12.0, manager.status?.position)
        }
    }

    @Test
    fun aStalledReceiverIsQuitAndRelaunchedAtTheLastFilmPosition() {
        val channel = FakeChannel()
        val clock = Clock()
        watched(channel, clock).use { manager ->
            manager.control("seek", 100.0) // the fixture film is 120s
            channel.report("PLAYING", 5.0)
            manager.refresh()
            clock.advance(2.0)
            channel.report("PLAYING", 7.0)
            manager.refresh()
            channel.events.clear()

            // The observed wedge: BUFFERING with a frozen clock, the relay no longer read.
            channel.report("BUFFERING", 7.3)
            clock.advance(CastManager.STALL_SECONDS - 1.0)
            manager.refresh()
            assertEquals(emptyList(), channel.events)
            clock.advance(1.0)
            manager.refresh()
            assertEquals(listOf("stop-receiver", "load"), channel.events)
            assertEquals("start=107.3", channel.lastLoad!!.first.substringAfter("?"))
            assertEquals(CastRecovery.Kind.RESTARTING, manager.recovery?.kind)
            assertEquals(107.3, manager.recovery?.position)
            assertEquals(107.3, manager.status?.position)
            assertEquals(manager.castingAsset, "asset", "The relaunched session is still ours")
        }
    }

    @Test
    fun pausedTimeNeverCountsAndRepeatedStallsStopAfterTheBudget() {
        val channel = FakeChannel()
        val clock = Clock()
        watched(channel, clock).use { manager ->
            manager.control("pause")
            channel.report("PAUSED", 3.0)
            manager.refresh()
            clock.advance(600.0)
            manager.refresh()
            assertNull(manager.recovery, "A paused receiver is not a stalled one")

            manager.control("resume")
            channel.events.clear()
            repeat(CastManager.MAX_RECOVERIES + 2) {
                channel.report("BUFFERING", 0.0)
                manager.refresh() // a fresh baseline after each relaunch
                clock.advance(CastManager.STALL_SECONDS.toDouble())
                channel.report("BUFFERING", 0.0)
                manager.refresh()
            }
            assertEquals(CastManager.MAX_RECOVERIES, channel.events.count { it == "stop-receiver" })
            assertEquals(CastRecovery.Kind.GAVE_UP, manager.recovery?.kind)
            assertTrue(manager.error.orEmpty().contains("keeps stalling"))
        }
    }

    @Test
    fun theCastingAssetIsReleasedWhenTheReceiverStops() {
        val channel = FakeChannel()
        watched(channel, Clock()).use { manager ->
            assertEquals("First", manager.castingTo)
            assertEquals("asset", manager.castingAsset)
            manager.control("stop")
            assertNull(manager.castingTo)
            assertNull(manager.castingAsset)
        }
        assertFalse(channel.events.isEmpty())
    }
}
