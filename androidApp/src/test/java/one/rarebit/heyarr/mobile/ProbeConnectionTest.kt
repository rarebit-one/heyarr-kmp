package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.state.ExternalMetadata
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.Connection
import one.rarebit.heyarr.mobile.state.RecentSearches
import one.rarebit.voidwhichbinds.AuthenticationRequiredException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Liveness probe status mapping — mirrors the desktop AppSession contract.
 * [Connection.fromProbe] maps HTTP status codes; [AppSession.probe] drives the Connection
 * enum from actual transport outcomes including local signing failures.
 */
class ProbeConnectionTest {

    // ── Connection.fromProbe() ────────────────────────────────────────────────

    @Test fun `status 200 is online`() {
        assertEquals("200 → ONLINE", Connection.ONLINE, Connection.fromProbe(200))
    }

    @Test fun `status 401 is unauthorized`() {
        assertEquals("401 → UNAUTHORIZED", Connection.UNAUTHORIZED, Connection.fromProbe(401))
    }

    @Test fun `status 403 is unauthorized`() {
        assertEquals("403 → UNAUTHORIZED", Connection.UNAUTHORIZED, Connection.fromProbe(403))
    }

    @Test fun `status 0 transport failure is offline`() {
        assertEquals("0 → OFFLINE", Connection.OFFLINE, Connection.fromProbe(0))
    }

    @Test fun `non-200 non-auth statuses are offline`() {
        assertEquals("404 → OFFLINE", Connection.OFFLINE, Connection.fromProbe(404))
        assertEquals("500 → OFFLINE", Connection.OFFLINE, Connection.fromProbe(500))
    }

    // ── AppSession.probe() ────────────────────────────────────────────────────

    private fun session(transport: HttpTransport): AppSession = AppSession(
        api = HeyarrApi(transport, "https://node.example", Credential.Guest),
        defaultProfile = "standard",
        settings = InMemorySettingsStore(),
        external = ExternalMetadata(File("/dev/null"), { false }, { null }),
        recent = RecentSearches(File("/dev/null")),
        scope = CoroutineScope(Dispatchers.Default),
    )

    @Test fun `probe sets ONLINE on 200`() {
        runBlocking {
            val s = session(SubstringTransport(listOf("GET /works" to HttpResponse(200, ""))))
            s.probe()
            assertEquals("200 probe → ONLINE", Connection.ONLINE, s.connection)
        }
    }

    @Test fun `probe sets UNAUTHORIZED on 401`() {
        runBlocking {
            val s = session(SubstringTransport(listOf("GET /works" to HttpResponse(401, ""))))
            s.probe()
            assertEquals("401 probe → UNAUTHORIZED", Connection.UNAUTHORIZED, s.connection)
        }
    }

    @Test fun `probe sets OFFLINE when transport throws IOException`() {
        runBlocking {
            val s = session(ThrowingTransport(IOException("refused")))
            s.probe()
            assertEquals("IOException probe → OFFLINE", Connection.OFFLINE, s.connection)
        }
    }

    @Test fun `probe sets SIGNING_FAILED when signing key needs re-auth`() {
        runBlocking {
            val s = session(ThrowingTransport(AuthenticationRequiredException("biometric required")))
            s.probe()
            assertEquals("AuthenticationRequired → SIGNING_FAILED", Connection.SIGNING_FAILED, s.connection)
        }
    }
}

/** A transport whose [get] throws the supplied exception; other methods return 500. */
private class ThrowingTransport(private val ex: Throwable) : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse = throw ex
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
        HttpResponse(500, "")
    override fun delete(url: String, headers: Map<String, String>) = HttpResponse(500, "")
    override fun patch(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
        HttpResponse(500, "")
}
