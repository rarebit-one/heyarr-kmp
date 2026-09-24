package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.search.AcquireClient
import one.rarebit.heyarr.mobile.search.SearchResult
import one.rarebit.heyarr.mobile.search.SearchResultsJson
import one.rarebit.heyarr.mobile.search.SessionClient
import one.rarebit.heyarr.mobile.search.SessionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M12 Follow write-scope wiring: a search hit now carries the `tvdb_id` feed
 * identity a follow needs (so follow-from-search is one tap), and `GET /session`
 * tells the client whether it may write at all (ADR-0061 management grant).
 */
class FollowWriteScopeWiringTest {

    // ── tvdb_id: /search now carries the feed identity a follow needs ──────────────

    @Test fun searchResultParsesTvdbId() {
        val body = """{"works":[
            {"work_id":"w1","title":"Severance","content_type":"series","tvdb_id":"424242"},
            {"work_id":"w2","title":"A Local Movie","content_type":"movie"}
        ]}"""
        val results = SearchResultsJson.parse(body)
        assertEquals(2, results.size)
        assertEquals("424242", results[0].tvdbId)
        assertNull("a work with no stored id carries none", results[1].tvdbId)
    }

    @Test fun followFromSearchSendsTheTvdbIdToTheServer() {
        val t = CapturingTransport(post = HttpResponse(201, """{"id":"src-7"}"""))
        val client = AcquireClient(t, "https://h.example", Credential.Session("tok"), "living-room")
        val hit = SearchResult(workId = "w1", title = "Severance", type = "series", tvdbId = "424242")

        val result = client.follow(hit, tvdbId = hit.tvdbId)

        assertTrue(result is AcquireClient.Result.Following)
        val posted = t.lastPostBody!!
        assertTrue("the follow carries the search hit's feed identity", posted.contains("\"tvdb_id\":\"424242\""))
        assertTrue("still names the work", posted.contains("\"work_id\":\"w1\""))
    }

    // ── GET /session: the read floor + the interim write path ──────────────────────

    @Test fun sessionJsonParsesAWriteAuthorizedSession() {
        val body = """{
            "kind":"session","principal_id":"ed25519:abc","device_key":"ed25519:phone",
            "scopes":["read","write"],"can_write":true,"management_authorized":true
        }"""
        val a = SessionJson.parse(body)!!
        assertEquals("session", a.kind)
        assertEquals("ed25519:phone", a.deviceKey)
        assertTrue(a.canWrite)
        assertTrue(a.managementAuthorized)
        assertFalse("a writable session is not the read-only floor", a.isReadOnlySession)
        assertEquals(listOf("read", "write"), a.scopes)
    }

    @Test fun sessionJsonParsesAReadOnlySession() {
        val body = """{"kind":"session","device_key":"ed25519:phone","scopes":["read"],
            "can_write":false,"management_authorized":false}"""
        val a = SessionJson.parse(body)!!
        assertFalse(a.canWrite)
        assertTrue("a read-only session is the case the UI must surface", a.isReadOnlySession)
        assertEquals("ed25519:phone", a.deviceKey)
    }

    @Test fun sessionClientReturnsNullOnNon200SoTheUiTreatsItAsReadOnly() {
        val t = CapturingTransport(get = HttpResponse(500, "boom"))
        val client = SessionClient(t, "https://h.example", Credential.Session("tok"))
        assertNull("an unreadable session is unknown authority → the safe read-only floor", client.authority())
    }

    @Test fun sessionClientReadsTheAuthorityAndHitsTheRightRoute() {
        val t = CapturingTransport(
            get = HttpResponse(200, """{"kind":"session","device_key":"ed25519:phone","scopes":["read"],"can_write":false,"management_authorized":false}"""),
        )
        val client = SessionClient(t, "https://h.example", Credential.Session("tok"))
        val a = client.authority()!!

        assertEquals("https://h.example/api/v1/session", t.lastGetUrl)
        assertEquals("Bearer tok", t.lastGetAuth)
        assertTrue(a.isReadOnlySession)
    }

    private class CapturingTransport(
        private val get: HttpResponse = HttpResponse(405, ""),
        private val post: HttpResponse = HttpResponse(405, ""),
    ) : HttpTransport {
        var lastGetUrl: String? = null
            private set
        var lastGetAuth: String? = null
            private set
        var lastPostBody: String? = null
            private set

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            lastGetUrl = url
            lastGetAuth = headers["Authorization"]
            return get
        }

        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
            lastPostBody = body
            return post
        }
    }
}
