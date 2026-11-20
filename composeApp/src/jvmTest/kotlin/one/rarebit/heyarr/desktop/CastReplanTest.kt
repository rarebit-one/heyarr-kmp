package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.cast.CastTimeline
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.cast.CastManager
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * heyarr-kmp#256: the node signs a live stream's token for an hour from planning and then
 * refuses to start it, so a restart LOAD (a seek, a stall's relaunch, a resume) more than
 * [CastTimeline.PLAN_REFRESH_SECONDS] after the plan plans the film again first, as Android
 * does since #252. A presentation's capability outlives the film and is never planned again.
 */
class CastReplanTest {
    private val first = CastDevice("first", "First", InetAddress.getByName("127.0.0.1") as Inet4Address, 8009)

    /** A node that numbers each plan's stream token, and can be made to refuse planning. */
    private class Planner(private val hls: Boolean = false) : HttpTransport {
        var plans = 0
        var refuse = false
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse {
            if (refuse) return HttpResponse(503, "")
            plans++
            val presentation = if (hls) """"hls_url":"/render/hls/cap$plans/index.m3u8",""" else ""
            return HttpResponse(
                200,
                """{"mode":"stream","mime":"video/mp4","url":"/api/v1/playback/stream/tok$plans",""" +
                    presentation + """"source":{"duration_seconds":7200}}""",
            )
        }
    }

    /** A manager with the watcher, both clocks and the receiver in the test's hands, as CastResumeTest's. */
    private inner class Rig(hls: Boolean = false) : AutoCloseable {
        val node = Planner(hls)
        val clock = ManualClock()
        var wall = 1_000_000L
        val channels = mutableListOf<FakeChannel>()

        /** Every node URL a LOAD's doorway was opened onto, in order. */
        val opened = mutableListOf<String>()
        private val sessions = AtomicInteger(1)
        private var beat: () -> Unit = {}
        val manager = CastManager(
            { url ->
                opened += url
                "http://127.0.0.1:1/loopback"
            },
            {
                FakeChannel(sessions).also { fresh ->
                    channels.lastOrNull()?.let {
                        fresh.status = it.status
                        fresh.appSession = it.appSession
                    }
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

        private val api = HeyarrApi(node, "https://example.invalid", Credential.Guest)

        fun play() = manager.play(first, api, "asset", "Example")

        /** One watcher beat, [seconds] after the last, with the machine awake throughout. */
        fun poll(seconds: Double = 2.0) {
            age(seconds)
            beat()
        }

        /** Time passes awake, with no beat in between. */
        fun age(seconds: Double) {
            clock.advance(seconds)
            wall += (seconds * 1_000).toLong()
        }

        override fun close() = manager.close()
    }

    @Test
    fun aRestartPastTheTokensHourPlansTheFilmAgainAndKeepsItsPosition() {
        Rig().use { rig ->
            rig.play()
            rig.age(CastTimeline.PLAN_REFRESH_SECONDS.toDouble())
            rig.manager.control("seek", 3000.0)
            assertEquals(2, rig.node.plans, "The token would no longer start: planned again")
            assertTrue(rig.opened.last().endsWith("/stream/tok2"), rig.opened.last())
            assertEquals("start=3000", rig.channel.lastLoad!!.first.substringAfter("?"))
            assertEquals(4200.0, rig.channel.lastDuration, "What remains of the film, as before")
            rig.channel.report("PLAYING", 2.0)
            rig.poll()
            assertEquals(3002.0, rig.manager.status?.position, "Film-absolute, the offset unchanged")
            assertNull(rig.manager.error)
            // The fresh plan is the one held now: the next restart within its hour reuses it.
            rig.manager.control("seek", 4000.0)
            assertEquals(2, rig.node.plans)
            assertTrue(rig.opened.last().endsWith("/stream/tok2"))
        }
    }

    @Test
    fun aYoungPlanIsReusedByEveryRestart() {
        Rig().use { rig ->
            rig.play()
            rig.age(CastTimeline.PLAN_REFRESH_SECONDS - 60.0)
            rig.manager.control("seek", 3000.0)
            assertEquals(1, rig.node.plans)
            assertTrue(rig.opened.all { it.endsWith("/stream/tok1") }, rig.opened.toString())
        }
    }

    @Test
    fun aDroppedFilmsResumePastTheHourPlansAgain() {
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 600.0)
            rig.poll()
            rig.age(CastTimeline.PLAN_REFRESH_SECONDS.toDouble())
            rig.channel.status = rig.channel.status!!.copy(state = "IDLE", idleReason = "ERROR")
            rig.poll()
            assertEquals(2, rig.node.plans)
            assertTrue(rig.opened.last().endsWith("/stream/tok2"), rig.opened.last())
            assertEquals("start=600", rig.channel.lastLoad!!.first.substringAfter("?"))
        }
    }

    @Test
    fun anHourAsleepCountsThoughTheMonotonicClockStoodStill() {
        // The node's token lapses in wall time; the laptop's monotonic clock does not run in a
        // suspend. Waking, the cut stream is restarted, and that restart needs a fresh token.
        Rig().use { rig ->
            rig.play()
            rig.channel.report("PLAYING", 600.0)
            rig.poll()
            rig.wall += 3_600_000L
            rig.poll()
            assertEquals(2, rig.node.plans, "Planned again after the wake")
            assertTrue(rig.opened.last().endsWith("/stream/tok2"), rig.opened.last())
        }
    }

    @Test
    fun aPresentationIsNeverPlannedAgain() {
        Rig(hls = true).use { rig ->
            rig.play()
            assertTrue(rig.channel.lastHls)
            rig.channel.report("PLAYING", 600.0)
            rig.poll()
            rig.age(2 * 3600.0)
            // Dropped after it played: resumed as the presentation, on its own capability.
            rig.channel.status = rig.channel.status!!.copy(state = "IDLE", idleReason = "ERROR")
            rig.poll()
            assertEquals(2, rig.channels.sumOf { it.loadCount }, "Resumed")
            assertTrue(rig.channel.lastHls)
            assertEquals(1, rig.node.plans, "Its capability lasts six hours plus the film")
            assertTrue(rig.opened.last().endsWith("/cap1/index.m3u8"), rig.opened.last())
        }
    }

    @Test
    fun aFailedReplanRestartsWithTheOldPlanAndSaysSoUntilOneSucceeds() {
        Rig().use { rig ->
            rig.play()
            rig.age(CastTimeline.PLAN_REFRESH_SECONDS.toDouble())
            rig.node.refuse = true
            rig.manager.control("seek", 3000.0)
            assertEquals(1, rig.node.plans)
            assertTrue(rig.opened.last().endsWith("/stream/tok1"), "The old plan, still inside its hour")
            assertEquals("start=3000", rig.channel.lastLoad!!.first.substringAfter("?"))
            assertEquals("asset", rig.manager.castingAsset, "The cast goes on")
            val said = rig.manager.error
            assertTrue(said != null && said.startsWith("Could not refresh the stream for First"), said)
            rig.channel.report("PLAYING", 2.0)
            rig.poll()
            assertEquals(said, rig.manager.error, "A poll does not clear it")
            // The node is back: the next restart plans again and the warning goes.
            rig.node.refuse = false
            rig.manager.control("seek", 4000.0)
            assertEquals(2, rig.node.plans)
            assertTrue(rig.opened.last().endsWith("/stream/tok2"))
            assertNull(rig.manager.error)
            assertFalse(rig.channel.lastHls)
        }
    }
}
