package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.mcp.McpTransportException
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
        assertEquals("200 → ONLINE", Connection.ONLINE, Connection.fromProbe(200, ClientMode.ENROLLED))
        assertEquals("200 → ONLINE (guest)", Connection.ONLINE, Connection.fromProbe(200, ClientMode.GUEST))
    }

    @Test fun `status 401 with a credential is unauthorized`() {
        assertEquals("401 → UNAUTHORIZED", Connection.UNAUTHORIZED, Connection.fromProbe(401, ClientMode.ENROLLED))
    }

    @Test fun `status 403 with a credential is unauthorized`() {
        assertEquals("403 → UNAUTHORIZED", Connection.UNAUTHORIZED, Connection.fromProbe(403, ClientMode.ENROLLED))
    }

    // heyarr-kmp#185: a guest sent no credential, so a refusal must not blame one.
    @Test fun `status 401 or 403 as a guest is a guest refusal`() {
        assertEquals("guest 401 → GUEST_REFUSED", Connection.GUEST_REFUSED, Connection.fromProbe(401, ClientMode.GUEST))
        assertEquals("guest 403 → GUEST_REFUSED", Connection.GUEST_REFUSED, Connection.fromProbe(403, ClientMode.GUEST))
    }

    @Test fun `status 0 transport failure is offline`() {
        assertEquals("0 → OFFLINE", Connection.OFFLINE, Connection.fromProbe(0, ClientMode.ENROLLED))
        assertEquals("0 → OFFLINE (guest)", Connection.OFFLINE, Connection.fromProbe(0, ClientMode.GUEST))
    }

    @Test fun `non-200 non-auth statuses are offline`() {
        assertEquals("404 → OFFLINE", Connection.OFFLINE, Connection.fromProbe(404, ClientMode.ENROLLED))
        assertEquals("500 → OFFLINE", Connection.OFFLINE, Connection.fromProbe(500, ClientMode.GUEST))
    }

    // ── AppSession.probe() ────────────────────────────────────────────────────

    private fun session(transport: HttpTransport, credential: Credential = Credential.Guest): AppSession = AppSession(
        api = HeyarrApi(transport, "https://node.example", credential),
        defaultProfile = "standard",
        settings = InMemorySettingsStore(),
        external = ExternalMetadata(File("/dev/null"), { false }, { null }),
        recent = RecentSearches(File("/dev/null")),
        scope = CoroutineScope(Dispatchers.Default),
        credential = credential,
    )

    @Test fun `probe sets ONLINE on 200`() {
        runBlocking {
            val s = session(SubstringTransport(listOf("GET /works" to HttpResponse(200, ""))))
            s.probe()
            assertEquals("200 probe → ONLINE", Connection.ONLINE, s.connection)
        }
    }

    @Test fun `probe sets UNAUTHORIZED on 401 to a credential`() {
        runBlocking {
            val s = session(
                SubstringTransport(listOf("GET /works" to HttpResponse(401, ""))),
                credential = Credential.Session("tok"),
            )
            s.probe()
            assertEquals("401 probe → UNAUTHORIZED", Connection.UNAUTHORIZED, s.connection)
        }
    }

    // heyarr-kmp#185: the heartbeat a guest sends carries no Authorization header, so the
    // node's 401 means "not a guest network for this node", never "refused the credential".
    @Test fun `probe sets GUEST_REFUSED on 401 to a guest`() {
        runBlocking {
            val s = session(SubstringTransport(listOf("GET /works" to HttpResponse(401, ""))))
            s.probe()
            assertEquals("guest 401 probe → GUEST_REFUSED", Connection.GUEST_REFUSED, s.connection)
        }
    }

    @Test fun `a guest's refused call leaves the banner to the heartbeat`() {
        val s = session(SubstringTransport(listOf("GET /works" to HttpResponse(200, ""))))
        s.noteTransportFailure(McpTransportException("HTTP 401", null, status = 401))
        assertEquals("not blamed on a credential", Connection.UNKNOWN, s.connection)
    }

    @Test fun `an enrolled refused call is still the credential's refusal`() {
        val s = session(
            SubstringTransport(listOf("GET /works" to HttpResponse(200, ""))),
            credential = Credential.Session("tok"),
        )
        s.noteTransportFailure(McpTransportException("HTTP 401", null, status = 401))
        assertEquals(Connection.UNAUTHORIZED, s.connection)
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
