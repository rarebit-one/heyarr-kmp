package one.rarebit.heyarr.mobile

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import one.rarebit.heyarr.mobile.net.AuthHeaderSource
import one.rarebit.heyarr.mobile.net.AuthInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

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

    private class FakeChain(private val initial: Request) : Interceptor.Chain {
        var intercepted: Request? = null
            private set

        override fun request(): Request = initial

        override fun proceed(request: Request): Response {
            intercepted = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = error("not used")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test fun markedRequestProceededWithoutAuthAndWithoutMarker() {
        val interceptor = AuthInterceptor(baseUrl = { base }, header = liveHeader)
        val marked = Request.Builder()
            .url(apiUrl)
            .header(AuthInterceptor.NO_AUTH_MARKER, AuthInterceptor.NO_AUTH_VALUE)
            .build()
        val chain = FakeChain(marked)
        interceptor.intercept(chain)
        val sent = checkNotNull(chain.intercepted)
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
        val interceptor = AuthInterceptor(baseUrl = { base }, header = liveHeader)
        val req = Request.Builder().url(apiUrl).build()
        val chain = FakeChain(req)
        interceptor.intercept(chain)
        assertEquals("Bearer tok", chain.intercepted!!.header(AuthInterceptor.AUTHORIZATION))
        assertNull(
            "NO_AUTH_MARKER must never appear on a normal request",
            chain.intercepted!!.header(AuthInterceptor.NO_AUTH_MARKER),
        )
    }
}
