package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.cast.CastManager
import one.rarebit.heyarr.desktop.cast.CastRecovery
import one.rarebit.heyarr.desktop.cast.CastStatus
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.power.SleepInhibitor
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * heyarr-kmp#248, the night after: the laptop relaying a Cast idle-suspended, the node dropped
 * the stream, the TV played out its buffer and stopped, and the app did nothing on waking. The
 * sleep hold, reconnecting, and putting the film back where it was.
 */
class CastResumeTest {
    private val first = CastDevice("first", "First", InetAddress.getByName("127.0.0.1") as Inet4Address, 8009)

    /** Counts holds the way an OS would: open while acquired, closed once released. */
    private class RecordingInhibitor(private val available: Boolean = true) : SleepInhibitor {
        var asked = 0
        var open = 0
        val reasons = mutableListOf<String>()
        override fun acquire(reason: String): AutoCloseable? {
            asked++
            if (!available) return null
            open++
            reasons += reason
            return AutoCloseable { open-- }
        }
    }

    private val longFilm = HeyarrApi(
        object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(
                    200,
                    """{"mode":"stream","mime":"video/mp4","url":"/api/v1/playback/stream/cap",""" +
                        """"source":{"duration_seconds":7200}}""",
                )
        },
        "https://example.invalid",
        Credential.Guest,
    )

    /**
     * A manager whose watcher, clocks, receiver connections and sleep hold are all in the
     * test's hands. A reconnect finds the receiver as the previous channel last saw it.
     */
    private inner class Rig(
        val hold: RecordingInhibitor = RecordingInhibitor(),
        private val media: (String) -> String = { "http://127.0.0.1:1/loopback" },
    ) : AutoCloseable {
        val clock = ManualClock()
        var wall = 1_000_000L
        val channels = mutableListOf<FakeChannel>()
        var refuse: String? = null
        var connects = 0
        private val sessions = AtomicInteger(1)
        private var beat: () -> Unit = {}
        val manager = CastManager(
            media,
            {
                connects++
                refuse?.let { error(it) }
                FakeChannel(sessions).also { fresh ->
                    channels.lastOrNull()?.let { fresh.status = it.status }
                    channels += fresh
                }
            },
            clock = { clock.now },
            watch = { task ->
                beat = task
                AutoCloseable { beat = {} }
            },
            wallClock = { wall },
            inhibitor = hold,
        )
        val channel: FakeChannel get() = channels.last()

        fun play(plan: HeyarrApi = longFilm) = manager.play(first, plan, "asset", "Example")

        /** One watcher beat, [seconds] after the last, with the machine awake throughout. */
        fun poll(seconds: Double = 2.0) {
            clock.advance(seconds)
            wall += (seconds * 1_000).toLong()
            beat()
        }

        /** The machine sleeps: wall time runs on, the monotonic clock stands still. */
        fun sleep(seconds: Double) {
            wall += (seconds * 1_000).toLong()
        }

        override fun close() = manager.close()
    }

    @Test
    fun aPlayingCastHoldsSleepAndAPauseReleasesItOnlyAfterTheGrace() {
        Rig().use { rig ->
            rig.play()
            assertEquals(1, rig.hold.open)
            assertTrue(rig.hold.reasons.single().contains("First"), "The lock says who holds it and why")

            rig.manager.control("pause")
            rig.poll(CastManager.PAUSE_GRACE_SECONDS - 10.0)
            assertEquals(1, rig.hold.open, "A short pause keeps the machine awake for the TV remote's resume")
            rig.poll(20.0)
            assertEquals(0, rig.hold.open, "A long pause lets the machine sleep")

            rig.manager.control("resume")
            assertEquals(1, rig.hold.open)
            assertEquals(2, rig.hold.asked)

            rig.manager.control("stop")
            assertEquals(0, rig.hold.open)
        }
    }

    @Test
    fun theHoldGoesWhenTheCastIsClosedOrTakenOverAndAMissingInhibitorIsAskedOnce() {
        val closed = RecordingInhibitor()
        Rig(closed).use { it.play() }
        assertEquals(0, closed.open, "Closing the app's session releases the lock")

        val foreign = RecordingInhibitor()
        Rig(foreign).use { rig ->
            rig.play()
            rig.channel.status = CastStatus("PLAYING", 5.0, 900.0, "Another film", 99)
            rig.poll()
            assertNull(rig.manager.castingAsset)
            assertEquals(0, foreign.open)
        }

        val none = RecordingInhibitor(available = false)
        Rig(none).use { rig ->
            rig.play()
            repeat(5) { rig.poll() }
            assertEquals(1, none.asked, "No inhibitor here: not retried every poll")
            assertEquals("asset", rig.manager.castingAsset, "Casting works without one")
        }
    }

    @Test
    fun aDeadChannelIsReplacedAndAHealthySessionPlaysOnUntouched() {
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 10.0)
            rig.poll()
            val old = rig.channel
            old.error = "Cast receiver stopped responding"
            old.report("PLAYING", 12.0)
            rig.poll()
            assertEquals(2, rig.channels.size)
            assertTrue(old.closed)
            assertEquals(0, rig.channel.loadCount, "The receiver still plays our stream: nothing to reload")
            assertEquals("asset", rig.manager.castingAsset)
            assertNull(rig.manager.recovery)
            assertEquals(1, rig.hold.open)
            assertEquals(12.0, rig.manager.status?.position)
        }
    }

    @Test
    fun reconnectingBacksOffKeepsTheCastAndLetsGoAfterItsBudget() {
        Rig().use { rig ->
            rig.play()
            rig.channel.error = "Cast connection lost"
            rig.refuse = "No route to host"
            repeat(10) { rig.poll() } // 20 s
            // Tries at 2, 4, 6, 10 and 18 s: 1, 2, 4, 8 s apart, not every poll.
            assertEquals(1 + 5, rig.connects)
            assertEquals("asset", rig.manager.castingAsset, "Still ours while the receiver may come back")
            assertTrue(rig.manager.error.orEmpty().startsWith("Reconnecting to First"))
            assertEquals(1, rig.hold.open)

            rig.poll(CastManager.RECONNECT_GIVE_UP_SECONDS.toDouble())
            assertNull(rig.manager.castingAsset)
            assertTrue(rig.manager.error.orEmpty().startsWith("Lost First"))
            assertEquals(0, rig.hold.open)

            rig.refuse = null // the TV is back: casting again opens a fresh connection
            rig.play()
            assertEquals("asset", rig.manager.castingAsset)
            assertEquals(1, rig.hold.open)
        }
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L), (1..7).map(CastManager::backoffSeconds))
    }

    @Test
    fun stoppingWhileTheReceiverIsUnreachableLetsTheCastGo() {
        Rig().use { rig ->
            rig.play()
            rig.channel.error = "Cast connection lost"
            rig.refuse = "No route to host"
            rig.poll()
            assertFails { rig.manager.control("pause") }
            rig.manager.control("stop") // "Play here" while the TV is away
            assertNull(rig.manager.castingAsset)
            assertEquals(0, rig.hold.open)

            rig.refuse = null
            rig.play() // mid-backoff, with no connection at all
            assertEquals("asset", rig.manager.castingAsset)
        }
    }

    @Test
    fun aSuspendReconnectsAndRestartsTheStreamBeforeTheReceiversBufferRunsDry() {
        Rig().use { rig ->
            rig.play()
            rig.manager.control("seek", 1860.0) // the 31:00 restart of the incident
            rig.channel.report("PLAYING", 10.0)
            rig.poll()
            val old = rig.channel
            val loads = old.loadCount

            // Eight minutes asleep: the node drops the stream, the TV plays on from its buffer.
            rig.sleep(480.0)
            old.report("PLAYING", 490.0)
            rig.poll()
            assertTrue(old.closed, "A socket from before the suspend is never trusted")
            assertEquals(loads, old.loadCount)
            assertEquals(listOf("load"), rig.channel.events, "Reloaded into the receiver's session, not relaunched")
            assertEquals("start=2350.0", rig.channel.lastLoad!!.first.substringAfter("?"))
            assertEquals(true, rig.channel.lastLoad!!.second)
            assertEquals(CastRecovery(CastRecovery.Kind.RECONNECTED, "First", 2350.0), rig.manager.recovery)
            assertEquals("asset", rig.manager.castingAsset)

            // Playing on from the restart: no second reload, and the notice clears.
            rig.channel.report("PLAYING", 0.0)
            rig.poll()
            rig.channel.report("PLAYING", 2.0)
            rig.poll()
            assertEquals(1, rig.channel.loadCount)
            assertNull(rig.manager.recovery)
            assertEquals(2352.0, rig.manager.status?.position)
        }
    }

    @Test
    fun aFilmTheReceiverDropsMidwayIsRelaunchedWhereItWas() {
        for (reason in listOf("ERROR", "INTERRUPTED", "FINISHED")) {
            Rig().use { rig ->
                rig.play()
                rig.manager.control("seek", 600.0)
                rig.channel.report("PLAYING", 20.0)
                rig.poll()
                rig.channel.events.clear()
                rig.channel.status = requireNotNull(rig.channel.status).copy(state = "IDLE", idleReason = reason)
                rig.poll()
                assertEquals(listOf("stop-receiver", "load"), rig.channel.events, reason)
                assertEquals("start=620.0", rig.channel.lastLoad!!.first.substringAfter("?"), reason)
                assertEquals(CastRecovery(CastRecovery.Kind.RESUMING, "First", 620.0), rig.manager.recovery, reason)
                assertEquals("asset", rig.manager.castingAsset, reason)
                assertEquals(1, rig.hold.open, reason)
            }
        }
    }

    @Test
    fun aStopFromTheTvRemoteTheFilmsRealEndAndAnotherSenderAreNeverUndone() {
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 300.0)
            rig.poll()
            val loads = rig.channel.loadCount
            rig.channel.status = requireNotNull(rig.channel.status).copy(state = "IDLE", idleReason = "CANCELLED")
            rig.poll()
            assertNull(rig.manager.castingAsset, "CANCELLED is a stop")
            assertEquals(loads, rig.channel.loadCount)
            assertEquals(0, rig.hold.open)
        }
        Rig().use { rig ->
            rig.play()
            rig.manager.control("seek", 7100.0)
            rig.channel.report("PLAYING", 50.0) // 7150 of 7200: the credits
            rig.poll()
            val loads = rig.channel.loadCount
            rig.channel.status = requireNotNull(rig.channel.status).copy(state = "IDLE", idleReason = "FINISHED")
            rig.poll()
            assertNull(rig.manager.castingAsset, "The film ended")
            assertEquals(loads, rig.channel.loadCount)
        }
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 300.0)
            rig.poll()
            val loads = rig.channel.loadCount
            rig.sleep(600.0) // even with the stream presumed cut
            rig.channel.status = CastStatus("PLAYING", 4.0, 900.0, "Another film", 99)
            rig.poll()
            assertNull(rig.manager.castingAsset, "Another sender's media replaced ours")
            assertEquals(0, rig.channel.loadCount)
            assertEquals(loads, rig.channels.first().loadCount)
            assertEquals("Another film", rig.manager.status?.title)
        }
    }

    @Test
    fun aSessionThatVanishedWhileTheMachineSleptIsResumedButNotOtherwise() {
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 100.0)
            rig.poll()
            rig.sleep(1_800.0)
            rig.channel.status = null // played out its buffer and closed the media while we were away
            rig.poll()
            assertEquals(listOf("load"), rig.channel.events, "Its media app is gone: a fresh launch")
            assertEquals("start=100.0", rig.channel.lastLoad!!.first.substringAfter("?"))
            assertEquals(CastRecovery.Kind.RECONNECTED, rig.manager.recovery?.kind)
        }
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 100.0)
            rig.poll()
            val loads = rig.channel.loadCount
            rig.channel.status = null // no suspend, no cut stream: someone closed it
            rig.poll()
            assertNull(rig.manager.castingAsset)
            assertEquals(loads, rig.channel.loadCount)
        }
    }

    @Test
    fun aFilmThatKeepsDroppingStopsAfterTheResumeBudget() {
        Rig().use { rig ->
            rig.play()
            rig.manager.control("seek", 900.0)
            val loads = rig.channel.loadCount
            repeat(CastManager.MAX_RESUMES + 1) {
                rig.channel.status = requireNotNull(rig.channel.status).copy(state = "IDLE", idleReason = "ERROR")
                rig.poll()
            }
            assertEquals(loads + CastManager.MAX_RESUMES, rig.channel.loadCount)
            assertNull(rig.manager.castingAsset)
            assertEquals(CastRecovery(CastRecovery.Kind.GAVE_UP, "First", 900.0), rig.manager.recovery)
            assertTrue(rig.manager.error.orEmpty().contains("keeps dropping"))
            assertEquals(0, rig.hold.open)
        }
    }

    @Test
    fun aStreamTheNodeCutsUnderTheRelayIsRestartedAtTheReceiversPosition() {
        // The node's side of the relay: a chunked live stream that dies mid-film, its last
        // chunk never sent, as the proxy's exchange ends when the node drops this machine.
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { node ->
            Thread {
                runCatching {
                    node.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine().orEmpty().isNotEmpty()) Unit
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\n" +
                                        "Transfer-Encoding: chunked\r\n\r\n4\r\nmoov\r\n"
                                    ).toByteArray(),
                            )
                            flush()
                        }
                        Thread.sleep(300) // let the relay read the first chunk, then hang up
                    }
                }
            }.apply { isDaemon = true }.start()
            val upstream = "http://127.0.0.1:${node.localPort}/stream"
            Rig(media = { upstream }).use { rig ->
                rig.play()
                rig.manager.control("seek", 1200.0)
                rig.channel.report("PLAYING", 30.0)
                rig.poll()
                assertEquals(2, rig.channel.loadCount, "The Cast and one seek so far")

                // The receiver reads its download through the relay until the node's side breaks.
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { receiver ->
                    val read = HttpRequest.newBuilder(URI.create(rig.channel.lastLoad!!.first))
                        .timeout(Duration.ofSeconds(5)).build()
                    runCatching {
                        receiver.send(read, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
                    }
                }
                rig.channel.report("PLAYING", 32.0)
                rig.poll()
                assertEquals("start=1232.0", rig.channel.lastLoad!!.first.substringAfter("?"))
                assertEquals(CastRecovery(CastRecovery.Kind.RESUMING, "First", 1232.0), rig.manager.recovery)
                assertNotNull(rig.manager.castingAsset)
            }
        }
    }

    @Test
    fun fateOfReadsWhyOurSessionEnded() {
        fun fate(
            state: String?,
            reason: String? = null,
            session: Int? = 5,
            film: Double = 600.0,
            cut: Boolean = false,
        ) = CastManager.fateOf(
            state?.let { CastStatus(it, 0.0, null, null, session, idleReason = reason) },
            5,
            film,
            7200.0,
            cut,
        )
        assertEquals(CastManager.Companion.Fate.OURS, fate("PLAYING"))
        assertEquals(CastManager.Companion.Fate.OURS, fate("IDLE"), "No reason: the moment around a LOAD")
        assertEquals(CastManager.Companion.Fate.FOREIGN, fate("PLAYING", session = 6))
        assertEquals(CastManager.Companion.Fate.FOREIGN, fate("IDLE", "ERROR", session = 6, cut = true))
        assertEquals(CastManager.Companion.Fate.ENDED, fate("IDLE", "CANCELLED", cut = true))
        assertEquals(CastManager.Companion.Fate.DROPPED, fate("IDLE", "ERROR"))
        assertEquals(CastManager.Companion.Fate.DROPPED, fate("IDLE", "FINISHED"))
        assertEquals(CastManager.Companion.Fate.ENDED, fate("IDLE", "FINISHED", film = 7150.0))
        assertEquals(CastManager.Companion.Fate.ENDED, fate("IDLE", "ERROR", film = 7150.0))
        assertEquals(CastManager.Companion.Fate.ENDED, fate(null))
        assertEquals(CastManager.Companion.Fate.DROPPED, fate(null, cut = true))
        assertEquals(CastManager.Companion.Fate.ENDED, fate(null, film = 7150.0, cut = true))
    }
}
