package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.cast.CastManager
import one.rarebit.heyarr.desktop.cast.CastRecovery
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.playback.AuthenticatedMediaProxy
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Casting the node's HLS presentation (ADR-0126, heyarr-core#908): the Default Media Receiver
 * plays it with the whole film's duration and seeks it natively (seen on hardware), so the
 * restart machinery is only the fallback for a plan without one, or a receiver that refuses it.
 */
class CastHlsTest {
    private val first = CastDevice("first", "First", InetAddress.getByName("127.0.0.1") as Inet4Address, 8009)

    private fun plan(body: String, base: String = "https://example.invalid") = HeyarrApi(
        object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
            override fun post(url: String, body2: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(200, body)
        },
        base,
        Credential.Guest,
    )

    private val presented = plan(
        """{"mode":"stream","mime":"video/mp4","url":"/api/v1/playback/stream/cap",""" +
            """"hls_url":"/render/hls/cap/index.m3u8","hls_mime":"application/vnd.apple.mpegurl",""" +
            """"source":{"duration_seconds":7200}}""",
    )
    private val streamOnly = plan(
        """{"mode":"stream","mime":"video/mp4","url":"/api/v1/playback/stream/cap",""" +
            """"source":{"duration_seconds":7200}}""",
    )

    /** A manager with the watcher, clocks and receiver in the test's hands, as CastResumeTest's. */
    private inner class Rig(
        private val failHls: Boolean = false,
        private val media: (String) -> String = { url ->
            if (url.endsWith(
                    "/index.m3u8",
                )
            ) {
                "http://127.0.0.1:1/p/index.m3u8"
            } else {
                "http://127.0.0.1:1/loopback"
            }
        },
    ) : AutoCloseable {
        val clock = ManualClock()
        var wall = 1_000_000L
        val channels = mutableListOf<FakeChannel>()
        private val sessions = AtomicInteger(1)
        private var beat: () -> Unit = {}
        val manager = CastManager(
            media,
            {
                FakeChannel(sessions).also { fresh ->
                    fresh.failHls = failHls
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
        )
        val channel: FakeChannel get() = channels.last()
        val loads: Int get() = channels.sumOf { it.loadCount }

        fun play(api: HeyarrApi = presented) = manager.play(first, api, "asset", "Example")

        fun poll(seconds: Double = 2.0) {
            clock.advance(seconds)
            wall += (seconds * 1_000).toLong()
            beat()
        }

        fun sleep(seconds: Double) {
            wall += (seconds * 1_000).toLong()
        }

        override fun close() = manager.close()
    }

    @Test
    fun aPresentationIsLoadedWholeAndSeekedNativelyInFilmTime() {
        Rig().use { rig ->
            rig.play()
            assertTrue(rig.channel.lastHls, "The presentation, not the live stream")
            assertTrue(rig.channel.lastLoad!!.first.endsWith("/index.m3u8"), rig.channel.lastLoad!!.first)
            assertNull(rig.channel.lastDuration, "The playlist says how long the film is")
            rig.manager.control("seek", 3000.0)
            assertEquals("seek" to 3000.0, rig.channel.lastControl, "The receiver's own seek")
            assertEquals(1, rig.loads, "No restart")
            rig.channel.report("PLAYING", 3002.0)
            rig.poll()
            assertEquals(3002.0, rig.manager.status?.position, "Receiver time is film time: no offset added")
            assertEquals(7200.0, rig.manager.status?.duration)
            // Out of range is clamped as the restart seek clamps it.
            rig.manager.control("seek", 9000.0)
            assertEquals("seek" to 7200.0, rig.channel.lastControl)
        }
    }

    @Test
    fun aPlanWithoutAPresentationStillRestartsTheLiveStream() {
        Rig().use { rig ->
            rig.play(streamOnly)
            assertFalse(rig.channel.lastHls)
            assertEquals(7200.0, rig.channel.lastDuration)
            rig.manager.control("seek", 3000.0)
            assertEquals(2, rig.loads)
            assertEquals("start=3000", rig.channel.lastLoad!!.first.substringAfter("?"))
            assertNull(rig.channel.lastControl)
        }
    }

    @Test
    fun aReceiverThatRefusesThePresentationGetsTheLiveStream() {
        Rig(failHls = true).use { rig ->
            rig.play()
            assertFalse(rig.channel.lastHls)
            assertTrue(!rig.channel.lastLoad!!.first.contains("index.m3u8"))
            assertEquals("asset", rig.manager.castingAsset)
            assertNull(rig.manager.error)
            // ...and for the rest of the cast: its seeks are restarts.
            rig.manager.control("seek", 3000.0)
            assertFalse(rig.channel.lastHls)
            assertEquals("start=3000", rig.channel.lastLoad!!.first.substringAfter("?"))
        }
    }

    @Test
    fun aFarSeekTheReceiverWaitsOnIsNotRestartedButAStallStillIs() {
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 100.0)
            rig.poll()
            // The TV's remote: +30 min. The node cuts that segment while the receiver buffers.
            rig.channel.report("BUFFERING", 1900.0)
            repeat(3) { rig.poll() }
            assertEquals(1, rig.loads, "Waiting on a far segment is not an unreachable seek here")
            assertNull(rig.manager.recovery)
            // Still nothing after the stall window: the receiver is wedged, and is relaunched there.
            repeat(10) { rig.poll() }
            assertEquals(CastRecovery.Kind.RESTARTING, rig.manager.recovery?.kind)
            assertTrue(rig.channel.lastHls, "Relaunched with the presentation")
            assertEquals(1900.0, rig.channel.lastStart, "At the film point, in film time")
            rig.channel.report("PLAYING", 1902.0)
            rig.poll()
            assertEquals(1902.0, rig.manager.status?.position)
        }
    }

    @Test
    fun aWakeLeavesAPlayingPresentationAloneAndADroppedOneComesBackWhereItWas() {
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 100.0)
            rig.poll()
            rig.sleep(600.0) // the TV played on from its segments while the laptop slept
            rig.channel.report("PLAYING", 700.0)
            rig.poll() // reconnects
            rig.poll()
            assertEquals(1, rig.loads, "No long stream was cut: nothing to restart")
            assertEquals("asset", rig.manager.castingAsset)
            assertEquals(700.0, rig.manager.status?.position)
        }
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 1200.0)
            rig.poll()
            rig.channel.report("IDLE", 1200.0)
            rig.channel.status = rig.channel.status!!.copy(idleReason = "ERROR")
            rig.poll()
            assertEquals(2, rig.loads)
            assertTrue(rig.channel.lastHls)
            assertEquals(1200.0, rig.channel.lastStart)
            assertEquals(CastRecovery.Kind.RESUMING, rig.manager.recovery?.kind)
        }
    }

    /** What a receiver does with what it was told to LOAD: the playlist, then entries relative to it. */
    private fun fetch(loaded: String, entry: String? = null): String =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
            val url = entry?.let { URI.create(loaded).resolve(it) } ?: URI.create(loaded)
            client.send(
                HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            ).body()
        }

    @Test
    fun aFakeReceiverFetchesTheWholePresentationThroughTheRelayAndProxy() {
        // End to end with real sockets: the plan's hls_url → the loopback proxy's prefix → the
        // receiver-scoped relay; the URL the receiver is told to LOAD reaches every entry.
        FakeHlsNode().use { node ->
            AuthenticatedMediaProxy({ node.origin }, { Credential.Device("cert", "proof") }).use { proxy ->
                val api = plan(
                    """{"mode":"stream","mime":"video/mp4","url":"/api/v1/playback/stream/tok",""" +
                        """"hls_url":"/render/hls/${FakeHlsNode.CAP}/index.m3u8","source":{"duration_seconds":10}}""",
                    node.origin,
                )
                Rig(media = proxy::urlFor).use { rig ->
                    rig.play(api)
                    val loaded = rig.channel.lastLoad!!.first
                    assertEquals(FakeHlsNode.PLAYLIST, fetch(loaded))
                    assertEquals("init", fetch(loaded, "init.mp4"))
                    assertEquals("seg:1", fetch(loaded, "1.m4s"))
                    assertTrue(node.requests.all { it.authorization == null })
                    rig.manager.control("stop")
                }
            }
        }
    }
}
