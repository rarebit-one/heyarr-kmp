package one.rarebit.heyarr.mobile

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import one.rarebit.heyarr.mobile.net.AuthHeaderSource
import one.rarebit.heyarr.mobile.net.AuthInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the [AuthInterceptor.NO_AUTH_MARKER] contract at the interceptor level:
 * a guest-planned range read must reach the wire with no `Authorization` header and
 * with the private marker stripped (#132).
 */
class AuthInterceptorMarkerTest {

    private val base = "https://node.example:7777"
    private val apiUrl = "$base/api/v1/works/w1/blob"

    // A live credential that WOULD be stamped onto unmarked requests — used to confirm
    // the marker path suppresses auth even when one is available.
    private val liveHeader = AuthHeaderSource().apply { provider = { "Bearer tok" } }

    /** Runs [request] through [AuthInterceptor] and returns what it sent on. */
    private fun sentFor(request: Request): Request {
        val interceptor = AuthInterceptor(baseUrl = { base }, header = liveHeader)
        var sent: Request? = null
        runThrough(interceptor, request) { proceeded ->
            sent = proceeded
            Response.Builder()
                .request(proceeded)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }.close()
        return checkNotNull(sent)
    }

    @Test fun markedRequestProceededWithoutAuthAndWithoutMarker() {
        val marked = Request.Builder()
            .url(apiUrl)
            .header(AuthInterceptor.NO_AUTH_MARKER, AuthInterceptor.NO_AUTH_VALUE)
            .build()
        val sent = sentFor(marked)
        assertNull(
            "Authorization must not be stamped onto a guest-planned range read",
            sent.header(AuthInterceptor.AUTHORIZATION),
        )
        assertNull(
            "NO_AUTH_MARKER is private to the OkHttp stack and must not reach the wire",
            sent.header(AuthInterceptor.NO_AUTH_MARKER),
        )
    }

    @Test fun unmarkedApiRequestReceivesAuth() {
        val sent = sentFor(Request.Builder().url(apiUrl).build())
        assertEquals("Bearer tok", sent.header(AuthInterceptor.AUTHORIZATION))
        assertNull(
            "NO_AUTH_MARKER must never appear on a normal request",
            sent.header(AuthInterceptor.NO_AUTH_MARKER),
        )
    }
}
