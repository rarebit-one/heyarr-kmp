package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.cast.CastChannel
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.cast.CastManager
import one.rarebit.heyarr.desktop.cast.CastStatus
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastManagerTest {
    private val address = InetAddress.getByName("127.0.0.1") as java.net.Inet4Address
    private val first = CastDevice("first", "First", address, 8009)
    private val second = CastDevice("second", "Second", address, 8009)
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

    private class FakeChannel : CastChannel {
        var closed = false
        var failLoad = false
        var lastLoad: Pair<String, Boolean>? = null
        var loadCount = 0
        var lastControl: Pair<String, Double?>? = null
        override var status: CastStatus? = CastStatus("PLAYING", 0.0, null, "Example", 1)
        override val error: String? = null
        override fun load(url: String, title: String, autoplay: Boolean, start: Double) {
            if (failLoad) error("LOAD_FAILED")
            loadCount++
            lastLoad = url to autoplay
            status = CastStatus(if (autoplay) "PLAYING" else "PAUSED", 0.0, null, title, loadCount + 1)
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
        }).use { manager ->
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
        }).use { manager ->
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
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }).use { manager ->
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
        }).use { manager ->
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
        }).use { manager ->
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
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }).use { manager ->
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
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }).use { manager ->
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
        CastManager({ "http://127.0.0.1:1/loopback" }, { channel }).use { manager ->
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
}
