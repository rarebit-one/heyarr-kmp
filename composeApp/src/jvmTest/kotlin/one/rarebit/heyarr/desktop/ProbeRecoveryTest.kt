package one.rarebit.heyarr.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.open.BlobDownloader
import one.rarebit.heyarr.desktop.open.DownloadResult
import one.rarebit.heyarr.desktop.open.ExternalOpener
import one.rarebit.heyarr.desktop.open.OpenExternally
import one.rarebit.heyarr.desktop.open.OpenResult
import one.rarebit.heyarr.desktop.playback.PlayResult
import one.rarebit.heyarr.desktop.playback.Player
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.InMemorySettingsStore
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.Connection
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The connection state after the machine changes network. A transport whose pooled
 * connections belong to a network that is gone fails every request until its pool is
 * dropped; the probe must drop it and dial again rather than report the node
 * unreachable, and a screen's transport failure must drop it too.
 */
class ProbeRecoveryTest {

    @Test
    fun defaultSessionDoesNotBrowseForCastDevices() {
        val s = session(StatusTransport(200))

        assertEquals(emptyList(), s.cast.discover())
        assertEquals(emptyList(), s.cast.devices)
    }

    /** Fails until [reset], then answers — the shape of a pool of dead connections. */
    private class StaleTransport : HttpTransport {
        var resets = 0
        var calls = 0
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls++
            if (resets == 0) throw IOException("connection reset")
            return HttpResponse(200, """{"works":[]}""")
        }
        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse = HttpResponse(405, "")
        override fun reset() {
            resets++
        }
    }

    private fun session(
        transport: HttpTransport,
        bearerToken: String = "heyarr_1_test",
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ): AppSession = AppSession(
        settings = InMemorySettingsStore(DesktopConfig(baseUrl = "https://h.example", bearerToken = bearerToken)),
        transport = transport,
        player = object : Player {
            override fun play(baseUrl: String, blobHash: String, token: String): PlayResult =
                PlayResult.Failed("no player")
        },
        openExternally = OpenExternally(
            object : BlobDownloader {
                override fun download(
                    baseUrl: String,
                    blobHash: String,
                    credential: Credential,
                    ext: String,
                ): DownloadResult = DownloadResult.Failed("no downloads")
            },
            object : ExternalOpener {
                override fun open(file: File): OpenResult = OpenResult.Opened
            },
        ),
        scope = scope,
    )

    @Test
    fun aFailedProbeDropsThePoolAndDialsAgainBeforeCallingTheNodeOffline() = runBlocking {
        val transport = StaleTransport()
        val s = session(transport)

        s.probe()

        assertEquals(Connection.ONLINE, s.connection)
        assertEquals(1, transport.resets, "the pool is dropped once")
        assertEquals(2, transport.calls, "one attempt on the stale pool, one on the fresh one")
        assertEquals(1, s.probes, "the two dials are one probe to the telemetry")
        assertNull(s.lastFailure, "a probe that recovered leaves no failure behind")
    }

    @Test
    fun aNodeThatIsReallyGoneIsOfflineAfterTheRetry() = runBlocking {
        val transport = object : HttpTransport {
            var resets = 0
            override fun get(url: String, headers: Map<String, String>): HttpResponse =
                throw IOException("no route to host")
            override fun post(
                url: String,
                body: String?,
                contentType: String?,
                headers: Map<String, String>,
            ): HttpResponse = HttpResponse(405, "")
            override fun reset() {
                resets++
            }
        }
        val s = session(transport)

        s.probe()

        assertEquals(Connection.OFFLINE, s.connection)
        assertEquals(1, transport.resets)
        assertEquals("no route to host", s.lastFailure)
    }

    @Test
    fun aCancelledProbeLeavesNothingBehind() = runBlocking {
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                Thread.sleep(10_000)
                return HttpResponse(200, "")
            }
            override fun post(
                url: String,
                body: String?,
                contentType: String?,
                headers: Map<String, String>,
            ): HttpResponse = HttpResponse(405, "")
        }
        val s = session(transport)

        val job = launch(Dispatchers.Default) { s.probe() }
        delay(200)
        job.cancelAndJoin()

        assertEquals(Connection.UNKNOWN, s.connection, "a probe that never finished says nothing about the node")
        assertNull(s.lastFailure, "cancellation is not a failure")
        assertEquals(0, s.probes)
    }

    @Test
    fun aRefusedTokenIsRecordedAsTheReason() = runBlocking {
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(401, "")
            override fun post(
                url: String,
                body: String?,
                contentType: String?,
                headers: Map<String, String>,
            ): HttpResponse = HttpResponse(405, "")
        }
        val s = session(transport)

        s.probe()

        assertEquals(Connection.UNAUTHORIZED, s.connection)
        assertEquals("HTTP 401 from the node", s.lastFailure)
    }

    @Test
    fun aScreensTransportFailureDropsThePoolButARefusedTokenDoesNot() {
        val transport = StaleTransport()
        val s = session(transport)

        s.noteTransportFailure(McpTransportException("heyarr is unreachable: connection reset", null))
        assertEquals(Connection.OFFLINE, s.connection)
        assertEquals(1, transport.resets, "a failure with no HTTP status is the transport's")

        s.noteTransportFailure(McpTransportException("heyarr refused the credential (HTTP 401)", null, 401))
        assertEquals(Connection.UNAUTHORIZED, s.connection)
        assertEquals(1, transport.resets, "an answer from the node is not a dead pool")
    }

    /** Answers every GET with [status]; the heartbeat's `GET /works` is all a probe sends. */
    private class StatusTransport(private val status: Int) : HttpTransport {
        override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(status, "")
        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse = HttpResponse(405, "")
    }

    // heyarr-kmp#185: with no token configured the probe sends no credential, so the node's
    // 401 says this network is not a guest network for it — not that a token was refused.
    @Test
    fun aGuestProbeRefusedIsAGuestRefusalNotARefusedToken() = runBlocking {
        val s = session(StatusTransport(401), bearerToken = "")

        s.probe()

        assertEquals(Connection.GUEST_REFUSED, s.connection)
    }

    @Test
    fun aGuestsRefusedCallLeavesTheBannerToTheHeartbeat() {
        val s = session(StatusTransport(200), bearerToken = "")
        val before = s.connection

        s.noteTransportFailure(McpTransportException("HTTP 401", null, 401))

        assertEquals(before, s.connection, "a guest's 401 must not read as a refused token")
    }

    @Test
    fun aProbeIsClassifiedInTheModeItWasSentWith() = runBlocking {
        // The probe goes out with a bearer token; while it is in flight the user removes the
        // token (now a guest). The node's 401 refused the TOKEN, so the verdict must blame the
        // credential, not report a refused guest (#188).
        lateinit var s: AppSession
        var switched = false
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                if (!switched) {
                    switched = true
                    s.save(s.config.copy(bearerToken = ""))
                }
                return HttpResponse(401, "")
            }
            override fun post(
                url: String,
                body: String?,
                contentType: String?,
                headers: Map<String, String>,
            ): HttpResponse = HttpResponse(405, "")
        }
        // A dead scope: save() would also restart the heartbeat, whose fresh guest probe rightly
        // lands GUEST_REFUSED for the NEW state. Only the in-flight probe's verdict is under test.
        s = session(transport, scope = CoroutineScope(Job().apply { cancel() }))

        s.probe()

        assertEquals(Connection.UNAUTHORIZED, s.connection, "the bearer token that was sent is what the node refused")
    }
}
