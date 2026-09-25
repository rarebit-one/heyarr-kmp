package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.net.AuthInterceptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stamping rule, as a table: same origin + under /api/v1/ + no auth of its own. */
class AuthInterceptorTest {

    private val base = "https://node.example:7777"

    @Test fun stampsAPosterOnOurNode() {
        assertTrue(AuthInterceptor.shouldStamp("$base/api/v1/works/w1/artwork", base, hasAuth = false))
        assertTrue(AuthInterceptor.shouldStamp("$base/api/v1/blobs/blake3:aa/content", base, hasAuth = false))
    }

    @Test fun leavesARequestThatAlreadyHasAuth() {
        assertFalse(AuthInterceptor.shouldStamp("$base/api/v1/works", base, hasAuth = true))
    }

    @Test fun neverStampsAnotherHost() {
        assertFalse(AuthInterceptor.shouldStamp("https://images.example/api/v1/poster.jpg", base, hasAuth = false))
        assertFalse(AuthInterceptor.shouldStamp("https://node.example.evil:7777/api/v1/works", base, hasAuth = false))
    }

    @Test fun portMustMatch() {
        assertFalse(AuthInterceptor.shouldStamp("https://node.example:8888/api/v1/works", base, hasAuth = false))
        assertFalse(AuthInterceptor.shouldStamp("https://node.example/api/v1/works", base, hasAuth = false))
        // Default ports are normalised: an explicit :443 IS https://host.
        assertTrue(
            AuthInterceptor.shouldStamp(
                "https://node.example:443/api/v1/works",
                "https://node.example",
                hasAuth = false,
            ),
        )
    }

    @Test fun schemeMustMatch() {
        assertFalse(AuthInterceptor.shouldStamp("http://node.example:7777/api/v1/works", base, hasAuth = false))
    }

    @Test fun onlyUnderTheApiPrefix() {
        assertFalse(AuthInterceptor.shouldStamp("$base/login/abc", base, hasAuth = false))
        assertFalse(AuthInterceptor.shouldStamp("$base/pair/v1/sessions", base, hasAuth = false))
        assertFalse(AuthInterceptor.shouldStamp("$base/api/v1", base, hasAuth = false))
        assertFalse(AuthInterceptor.shouldStamp("$base/api/v10/works", base, hasAuth = false))
    }

    @Test fun garbageIsNotStamped() {
        assertFalse(AuthInterceptor.shouldStamp("::not a url::", base, hasAuth = false))
        assertFalse(AuthInterceptor.shouldStamp("$base/api/v1/works", "::", hasAuth = false))
    }
}
