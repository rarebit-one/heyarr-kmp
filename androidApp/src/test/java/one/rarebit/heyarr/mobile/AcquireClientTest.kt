package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.search.AcquireClient
import one.rarebit.heyarr.mobile.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PostCapturingTransport(private val status: Int, private val respBody: String = "") : HttpTransport {
    var lastUrl: String? = null
        private set
    var lastBody: String? = null
        private set
    var lastAuth: String? = null
        private set
    var lastContentType: String? = null
        private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(405, "")
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastBody = body
        lastAuth = headers["Authorization"]
        lastContentType = contentType
        return HttpResponse(status, respBody)
    }
}

class AcquireClientTest {

    private val movie = SearchResult(workId = "w1", title = "Dune", type = "movie", year = 2021)
    private val series = SearchResult(workId = "w2", title = "Severance", type = "series")

    // ── One-off ("Get once") → the LIVE POST /api/v1/desired, monitor:false ──────

    @Test fun getOnceHitsDesiredRouteWithMonitorFalse() {
        val t = PostCapturingTransport(201, """{"id":"d-1"}""")
        val client = AcquireClient(t, "https://h.example", Credential.Session("tok"), "living-room")
        val result = client.getOnce(movie)

        assertEquals("https://h.example/api/v1/desired", t.lastUrl)
        assertEquals("application/json", t.lastContentType)
        assertEquals("Bearer tok", t.lastAuth)
        val body = t.lastBody!!
        assertTrue("carries work_id", body.contains("\"work_id\":\"w1\""))
        assertTrue("one-off ⇒ monitor:false", body.contains("\"monitor\":false"))
        assertTrue("carries the quality profile", body.contains("\"quality_profile\":\"living-room\""))
        assertTrue(result is AcquireClient.Result.Wanted)
        assertEquals("d-1", (result as AcquireClient.Result.Wanted).desiredId)
    }

    // ── Follow ("Subscribe") → the LIVE POST /api/v1/followed-sources, monitor:true ─

    @Test fun followHitsFollowedSourcesRouteWithMonitorTrue() {
        val t = PostCapturingTransport(201, """{"id":"src-7","work_id":"w2"}""")
        val client = AcquireClient(t, "https://h.example", Credential.Session("tok"), "living-room")
        val result = client.follow(series)

        assertEquals("https://h.example/api/v1/followed-sources", t.lastUrl)
        val body = t.lastBody!!
        // FollowSourceRequest names the series by work_id (never work_id + title both).
        assertTrue("carries work_id", body.contains("\"work_id\":\"w2\""))
        assertTrue("does not send source_id", !body.contains("source_id"))
        assertTrue("does not send content_type", !body.contains("content_type"))
        assertTrue("follow ⇒ monitor:true", body.contains("\"monitor\":true"))
        assertTrue("backfill defaults to from_now", body.contains("\"backfill\":\"from_now\""))
        assertTrue("carries the quality profile", body.contains("\"quality_profile\":\"living-room\""))
        assertTrue(result is AcquireClient.Result.Following)
        assertEquals("src-7", (result as AcquireClient.Result.Following).sourceId)
    }

    @Test fun followCarriesAFeedIdentityWhenGiven() {
        val t = PostCapturingTransport(201, """{"id":"src-8"}""")
        AcquireClient(t, "https://h", Credential.Session("t"), "p").follow(series, tvdbId = "12345")
        assertTrue(t.lastBody!!.contains("\"tvdb_id\":\"12345\""))
    }

    // ── The dispatch difference is the whole feature ─────────────────────────────

    @Test fun getOnceAndFollowGoToDifferentRoutes() {
        val once = PostCapturingTransport(201)
        val follow = PostCapturingTransport(201)
        AcquireClient(once, "https://h", Credential.Session("t"), "p").getOnce(movie)
        AcquireClient(follow, "https://h", Credential.Session("t"), "p").follow(series)
        assertEquals("https://h/api/v1/desired", once.lastUrl)
        assertEquals("https://h/api/v1/followed-sources", follow.lastUrl)
        assertTrue(once.lastBody!!.contains("\"monitor\":false"))
        assertTrue(follow.lastBody!!.contains("\"monitor\":true"))
    }

    // ── Phase-1 refusals surface the server's `detail`, not a bare status ────────

    @Test fun tvSeriesOnlyRefusalSurfacesServerDetail() {
        // A follow of a search hit with no feed identity is refused server-side (400).
        val detail = "following this source is not implemented yet — Phase 1 follows tv_series only " +
            "(give a TVDB series id or URL)"
        val t = PostCapturingTransport(400, """{"title":"Bad Request","status":400,"detail":"$detail"}""")
        val result = AcquireClient(t, "https://h", Credential.Session("t"), "p").follow(series)
        assertTrue(result is AcquireClient.Result.Failed)
        result as AcquireClient.Result.Failed
        assertEquals(400, result.status)
        assertEquals(detail, result.message)
    }

    // ── Read-scoped session (QR web-login) 403s on the write routes ──────────────

    @Test fun followFrom403ReadScopedSessionSurfacesEnrolHint() {
        // heyarr mints the QR session read-scoped; POST /followed-sources is write → 403.
        val t = PostCapturingTransport(403, """{"status":403,"detail":"this token does not carry the write scope"}""")
        val result = AcquireClient(t, "https://h", Credential.Session("t"), "p").follow(series)
        assertTrue(result is AcquireClient.Result.Failed)
        result as AcquireClient.Result.Failed
        assertEquals(403, result.status)
        assertEquals(AcquireClient.READ_ONLY_FOLLOW_HINT, result.message)
    }

    @Test fun getOnceFrom403ReadScopedSessionSurfacesEnrolHint() {
        val t = PostCapturingTransport(403, """{"status":403,"detail":"this token does not carry the write scope"}""")
        val result = AcquireClient(t, "https://h", Credential.Session("t"), "p").getOnce(movie)
        assertTrue(result is AcquireClient.Result.Failed)
        assertEquals(AcquireClient.READ_ONLY_GET_HINT, (result as AcquireClient.Result.Failed).message)
    }

    @Test fun nonSuccessWithoutDetailFallsBackToStatus() {
        val t = PostCapturingTransport(404)
        val result = AcquireClient(t, "https://h", Credential.Session("t"), "p").follow(series)
        assertTrue(result is AcquireClient.Result.Failed)
        result as AcquireClient.Result.Failed
        assertEquals(404, result.status)
        assertEquals("follow failed: HTTP 404", result.message)
    }

    @Test fun pureBodyBuildersAreCorrect() {
        val once = AcquireClient.oneOffWantBody(movie, "living-room")
        assertTrue(once.contains("\"monitor\":false"))
        val f = AcquireClient.followBody(series, "living-room", backfill = "full")
        assertTrue(f.contains("\"monitor\":true"))
        assertTrue(f.contains("\"backfill\":\"full\""))
        assertTrue(f.contains("\"work_id\":\"w2\""))
    }
}
