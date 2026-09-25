package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.mobile.consumption.ConsumptionClient
import one.rarebit.heyarr.mobile.consumption.ConsumptionReporter
import one.rarebit.heyarr.mobile.consumption.InMemoryDeviceIdStore
import one.rarebit.heyarr.mobile.consumption.Position
import one.rarebit.heyarr.mobile.consumption.ProgressThrottle
import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionClientTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")
    private val caps =
        ClientCapabilities(containers = listOf("mp4"), video = listOf("h264"), audio = listOf("aac"), maxHeight = 1080)

    @Test fun bodiesAreTheServersShapes() {
        assertEquals(
            """{"device_key":"ed25519:ab","name":"heyarr-mobile on Pong","platform":"android","profile":{"containers":["mp4"],"video_codecs":["h264"],"audio_codecs":["aac"],"max_width":0,"max_height":1080,"max_bitrate_bps":0,"supports_hdr":false}}""",
            ConsumptionClient.registerBody("ed25519:ab", "heyarr-mobile on Pong", caps),
        )
        assertEquals(
            """{"device_key":"k","name":"n","platform":"android"}""",
            ConsumptionClient.registerBody("k", "n", null),
        )
        assertEquals(
            """{"asset_id":"a1","device_id":"d1","verb":"watch"}""",
            ConsumptionClient.sessionBody("a1", "d1", "watch"),
        )
        assertEquals(
            """{"transition":"progress","progress":{"locator":"1284.5","unit":"seconds"}}""",
            ConsumptionClient.transitionBody("progress", Position.seconds(1284.5)),
        )
        assertEquals("""{"transition":"start"}""", ConsumptionClient.transitionBody("start", null))
        assertEquals(
            """{"transition":"progress","progress":{"locator":"12","unit":"page"}}""",
            ConsumptionClient.transitionBody("progress", Position.page(12)),
        )
        assertEquals("120", ConsumptionClient.locator(120.0))
        assertEquals("0", ConsumptionClient.locator(-3.0))
        assertEquals("1.25", ConsumptionClient.locator(1.25))
        assertEquals(
            "$base/api/v1/consumption/sessions/s%3A1/transitions",
            ConsumptionClient.transitionUrl(base, "s:1"),
        )
    }

    @Test fun registerSessionAndTransitionReadTheIds() {
        val t = RoutedTransport(
            mapOf(
                "POST /devices" to HttpResponse(201, """{"id":"d1","device_key":"k"}"""),
                "POST /consumption/sessions" to HttpResponse(201, """{"id":"s1","state":"created"}"""),
                "POST /consumption/sessions/s1/transitions" to HttpResponse(200, """{"id":"s1","state":"playing"}"""),
            ),
        )
        val c = ConsumptionClient(t, { base }, { cred })
        assertEquals(ConsumptionClient.Outcome.Ok("d1"), c.registerDevice("k", "n", null))
        assertEquals(ConsumptionClient.Outcome.Ok("s1"), c.createSession("a1", "d1", "watch"))
        assertEquals(ConsumptionClient.Outcome.Ok("s1"), c.transition("s1", "start", null))
        assertEquals("Bearer tok", t.lastAuth)
    }

    @Test fun refusalsCarryTheStatus() {
        val t = RoutedTransport(
            mapOf(
                "POST /consumption/sessions/s1/transitions" to
                    HttpResponse(409, """{"detail":"illegal session transition"}"""),
            ),
        )
        val out = ConsumptionClient(t, { base }, { cred }).transition("s1", "resume", Position.seconds(1.0))
        assertTrue(out is ConsumptionClient.Outcome.Refused && out.status == 409)
        assertTrue(
            ConsumptionClient(t, {
                base
            }, { null }).transition("s1", "resume", Position.seconds(1.0)) is ConsumptionClient.Outcome.Refused,
        )
    }

    @Test fun throttleSendsOnIntervalAndMovement() {
        val th = ProgressThrottle(intervalSeconds = 15.0, minDeltaSeconds = 5.0)
        assertTrue(th.accept(0.0, 0.0))
        assertFalse("too soon", th.accept(10.0, 20.0))
        assertFalse("not moved", th.accept(20.0, 3.0))
        assertTrue(th.accept(20.0, 30.0))
        th.mark(21.0, 31.0)
        assertFalse("a state change re-anchored", th.accept(30.0, 60.0))
        assertTrue(th.accept(36.0, 60.0))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConsumptionReporterTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val base = "https://h.example"
    private var now = 0.0

    private fun reporter(
        t: RoutedTransport,
        canWrite: Boolean = true,
        store: InMemoryDeviceIdStore = InMemoryDeviceIdStore("phone-x"),
    ) = ConsumptionReporter(
        client = ConsumptionClient(t, { base }, { Credential.Session("tok") }),
        store = store, scope = CoroutineScope(dispatcher), baseUrl = { base },
        canWrite = { canWrite }, enrolledDeviceKey = { "ed25519:dev" }, deviceName = { "phone" },
        capabilities = { null }, io = dispatcher, clock = { now },
    )

    private val routes = mapOf(
        "POST /devices" to HttpResponse(201, """{"id":"d1"}"""),
        "POST /consumption/sessions" to HttpResponse(201, """{"id":"s1"}"""),
        "POST /consumption/sessions/s1/transitions" to HttpResponse(200, """{"id":"s1"}"""),
    )

    @Test fun beginRegistersTheDeviceOnceThenOpensAndStartsASession() {
        val t = RoutedTransport(routes)
        val store = InMemoryDeviceIdStore("phone-x")
        val r = reporter(t, store = store)
        r.begin("a1", "watch")
        assertEquals("s1", r.sessionId)
        assertEquals("d1", store.deviceId(base))
        val posted = t.calls.map { it.second.substringAfter("/api/v1") }
        assertEquals(listOf("/devices", "/consumption/sessions", "/consumption/sessions/s1/transitions"), posted)
        assertTrue("the enrolled key is the device key", t.calls[0].third!!.contains("ed25519:dev"))
        assertTrue(t.calls[2].third!!.contains("\"start\""))

        // A second begin stops the first and does NOT re-register.
        r.begin("a2", "watch")
        val again = t.calls.drop(3).map { it.second.substringAfter("/api/v1") }
        assertEquals(
            listOf(
                "/consumption/sessions/s1/transitions",
                "/consumption/sessions",
                "/consumption/sessions/s1/transitions",
            ),
            again,
        )
        assertTrue(t.calls[3].third!!.contains("\"stop\""))
    }

    @Test fun readOnlyCredentialStaysSilent() {
        val t = RoutedTransport(routes)
        val r = reporter(t, canWrite = false)
        r.begin("a1", "watch")
        r.progress(10.0)
        r.end(20.0, false)
        assertTrue(t.calls.isEmpty())
        assertNull(r.sessionId)
    }

    @Test fun progressIsThrottledAndStateChangesAlwaysGo() {
        val t = RoutedTransport(routes)
        val r = reporter(t)
        r.begin("a1", "watch")
        val before = t.calls.size
        now = 1.0
        r.progress(5.0) // first tick goes
        now = 2.0
        r.progress(10.0) // too soon
        now = 30.0
        r.progress(11.0) // not moved enough
        now = 31.0
        r.progress(60.0) // goes
        assertEquals(before + 2, t.calls.size)
        r.pause(61.0)
        r.resume(61.0)
        assertEquals(before + 4, t.calls.size)
        assertTrue(t.calls.last().third!!.contains("\"resume\""))
        r.end(600.0, completed = true)
        assertTrue(t.calls.last().third!!.contains("\"complete\""))
        assertNull("a completed session is closed", r.sessionId)
        r.progress(700.0)
        assertEquals("nothing after the end", before + 5, t.calls.size)
    }

    @Test fun aConflictDropsTheSession() {
        val t =
            RoutedTransport(
                routes + ("POST /consumption/sessions/s1/transitions" to HttpResponse(409, """{"detail":"illegal"}""")),
            )
        val r = reporter(t)
        r.begin("a1", "watch") // start itself 409s → no session
        assertNull(r.sessionId)
    }
}
