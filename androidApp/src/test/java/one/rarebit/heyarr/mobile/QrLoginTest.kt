package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.login.QrLoginClient
import one.rarebit.heyarr.mobile.login.VoidbindLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted transport: POST /login returns [createBody]; GET /login/{id} plays [pollBodies] in order. */
private class FakeTransport(
    private val createBody: String,
    private val pollBodies: List<String>,
) : HttpTransport {
    var pollCount = 0
        private set
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        assertTrue("post should hit /login", url.endsWith("/login"))
        return HttpResponse(200, createBody)
    }
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val body = pollBodies[minOf(pollCount, pollBodies.lastIndex)]
        pollCount++
        return HttpResponse(200, body)
    }
}

class QrLoginTest {

    private fun client(t: FakeTransport) = QrLoginClient(
        http = t,
        rpBase = "https://heyarr.example",
        sleep = { /* no real delay in tests */ },
        pollIntervalMs = 0,
    )

    @Test fun beginParsesTheTupleAndExposesTheQr() {
        val t = FakeTransport(
            createBody = """{"id":"login-42","qr":"voidbind:login?id=login-42&rp=https%3A%2F%2Fheyarr.example"}""",
            pollBodies = listOf("""{"status":"pending"}"""),
        )
        val pending = client(t).begin()
        assertEquals("login-42", pending.loginId)
        assertEquals("https://heyarr.example", pending.tuple.rp)
        assertEquals("login-42", pending.tuple.id)
        assertTrue(pending.qrTuple.startsWith("voidbind:login?"))
    }

    @Test fun pollsUntilApprovedThenReturnsToken() {
        val t = FakeTransport(
            createBody = """{"id":"login-42","qr":"voidbind:login?id=login-42&rp=https%3A%2F%2Fheyarr.example"}""",
            pollBodies = listOf(
                """{"status":"pending"}""",
                """{"status":"pending"}""",
                """{"status":"approved","token":"sess-abc","user":"jaryl"}""",
            ),
        )
        val c = client(t)
        val result = c.awaitApproval(c.begin())
        assertTrue(result is VoidbindLogin.Result.Approved)
        result as VoidbindLogin.Result.Approved
        assertEquals("sess-abc", result.sessionToken)
        assertEquals("jaryl", result.user)
        assertEquals(3, t.pollCount)
    }

    @Test fun deniedApprovalSurfacesReason() {
        val t = FakeTransport(
            createBody = """{"id":"x","qr":"voidbind:login?id=x&rp=https%3A%2F%2Fheyarr.example"}""",
            pollBodies = listOf("""{"status":"denied"}"""),
        )
        val c = client(t)
        val result = c.awaitApproval(c.begin())
        assertTrue(result is VoidbindLogin.Result.Denied)
    }
}
