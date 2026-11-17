package one.rarebit.heyarr.mobile

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import one.rarebit.heyarr.mobile.net.RedirectNoStoreInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending-thumbnail redirect (heyarr-core ADR-0109) must not let its full-size target
 * be cached under the sized artwork URL (#140). The final response is what the image
 * loader caches, so the `no-store` has to travel from the redirect hop onto it.
 */
class RedirectNoStoreInterceptorTest {

    private val sized = "https://node.example:7777/api/v1/works/w1/artwork?size=512&v=abc"
    private val blob = "https://node.example:7777/api/v1/blobs/h1/content"
    private val immutable = "public, max-age=31536000, immutable"

    private fun redirect(cacheControl: String) = Response.Builder()
        .request(Request.Builder().url(sized).build())
        .protocol(Protocol.HTTP_1_1)
        .code(307)
        .message("Temporary Redirect")
        .header("Location", blob)
        .header("Cache-Control", cacheControl)
        .build()

    private fun blobResponse(prior: Response?) = Response.Builder()
        .request(Request.Builder().url(blob).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Cache-Control", immutable)
        .priorResponse(prior)
        .body("jpeg".toResponseBody(null))
        .build()

    /** What [RedirectNoStoreInterceptor] hands back when the network answered with [response]. */
    private fun intercept(response: Response) =
        runThrough(RedirectNoStoreInterceptor(), Request.Builder().url(sized).build()) { response }

    @Test fun pendingRedirectMakesTheOriginalNoStore() {
        val out = intercept(blobResponse(redirect("no-store")))
        assertTrue("the original reached through a no-store hop must not be cached", out.cacheControl.noStore)
    }

    @Test fun readyRedirectKeepsTheThumbnailImmutable() {
        val out = intercept(blobResponse(redirect("private, max-age=300")))
        assertEquals("a ready thumbnail keeps its own headers", immutable, out.header("Cache-Control"))
    }

    @Test fun directResponseIsUntouched() {
        val out = intercept(blobResponse(null))
        assertEquals("a response with no redirect keeps its own headers", immutable, out.header("Cache-Control"))
    }

    @Test fun noStoreAnywhereInTheChainCounts() {
        val inner = redirect("no-store")
        val outer = Response.Builder()
            .request(Request.Builder().url(sized).build())
            .protocol(Protocol.HTTP_1_1)
            .code(307)
            .message("Temporary Redirect")
            .header("Cache-Control", "private, max-age=300")
            .priorResponse(inner)
            .build()
        assertTrue(
            "a no-store on an earlier hop still applies",
            RedirectNoStoreInterceptor.reachedThroughNoStore(blobResponse(outer)),
        )
        assertFalse(
            "a chain without no-store is cacheable",
            RedirectNoStoreInterceptor.reachedThroughNoStore(blobResponse(redirect("private, max-age=300"))),
        )
    }
}
