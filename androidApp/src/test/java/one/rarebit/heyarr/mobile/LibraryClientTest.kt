package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.LibraryClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class CapturingTransport(private val body: String, private val status: Int = 200) : HttpTransport {
    var lastUrl: String? = null
        private set
    var lastAuth: String? = null
        private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastAuth = headers["Authorization"]
        return HttpResponse(status, body)
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        HttpResponse(405, "")
}

class LibraryClientTest {

    @Test fun buildsWorksUrl() {
        assertEquals("https://h.example/api/v1/works", LibraryClient.worksUrl("https://h.example/"))
        assertEquals("https://h.example/api/v1/works/abc", LibraryClient.workUrl("https://h.example", "abc"))
    }

    // ── page() ───────────────────────────────────────────────────────────────────────

    @Test fun pageUrlContainsSortRecentAndLimit() {
        val t = CapturingTransport("""{"items":[]}""")
        val client = LibraryClient(t, "https://h.example", Credential.Session("tok"))
        client.page(cursor = null, limit = 50)
        val url = t.lastUrl ?: error("no request made")
        assertEquals("sort=recent in url", true, url.contains("sort=recent"))
        assertEquals("limit=50 in url", true, url.contains("limit=50"))
        assertEquals("bearer header", "Bearer tok", t.lastAuth)
    }

    @Test fun pageUrlForwardsCursor() {
        val t = CapturingTransport("""{"items":[]}""")
        val client = LibraryClient(t, "https://h.example", Credential.Session("tok"))
        client.page(cursor = "abc==", limit = 200)
        val url = t.lastUrl ?: error("no request made")
        // cursor must be percent-encoded
        assertEquals("cursor in url", true, url.contains("cursor=abc%3D%3D"))
    }

    @Test fun pageReturnsItemsAndNextCursor() {
        val body = """{"items":[{"id":"w1","title":"Dune"}],"next_cursor":"page2"}"""
        val t = CapturingTransport(body)
        val client = LibraryClient(t, "https://h.example", Credential.Session("tok"))
        val page = client.page()
        assertEquals("item count", 1, page.items.size)
        assertEquals("item title", "Dune", page.items[0].title)
        assertEquals("next cursor", "page2", page.nextCursor)
    }

    @Test fun pageReturnsNullCursorAtEnd() {
        val body = """{"items":[{"id":"w1","title":"Dune"}]}"""
        val t = CapturingTransport(body)
        val client = LibraryClient(t, "https://h.example", Credential.Session("tok"))
        val page = client.page()
        assertNull("no next cursor at end", page.nextCursor)
    }

    // ── queryWorks() / worksUrl(baseUrl, q, contentType, limit) ──────────────────────

    @Test fun queryWorksUrlEncodesQAndContentType() {
        // `q` and `content_type` are percent-encoded; `include` is pre-encoded in the constant.
        val url = LibraryClient.worksUrl("https://h.example/", "Dune: Part Two", "movie", 10)
        assertEquals("q encoded", true, url.contains("q=Dune%3A+Part+Two"))
        assertEquals("content_type present", true, url.contains("content_type=movie"))
        assertEquals("limit present", true, url.contains("limit=10"))
        assertEquals("include present", true, url.contains("include="))
        assertEquals("no sort param", false, url.contains("sort="))
        assertEquals("no cursor param", false, url.contains("cursor="))
    }

    @Test fun queryWorksUrlOmitsContentTypeWhenNull() {
        val url = LibraryClient.worksUrl("https://h.example/", "Dune", null, 20)
        assertEquals("no content_type", false, url.contains("content_type"))
    }

    @Test fun queryWorksReturnsParsedList() {
        // Server responds with the standard page envelope; WorksJson.parse handles both shapes.
        val body = """{"items":[{"id":"w1","title":"Dune"},{"id":"w2","title":"Dune: Part Two"}]}"""
        val t = CapturingTransport(body)
        val client = LibraryClient(t, "https://h.example", Credential.Session("tok"))
        val works = client.queryWorks("Dune", "movie", 5)
        assertEquals("work count", 2, works.size)
        assertEquals("first title", "Dune", works[0].title)
        assertEquals("bearer header", "Bearer tok", t.lastAuth)
        assertEquals("q in url", true, t.lastUrl?.contains("q=Dune") == true)
        assertEquals("limit in url", true, t.lastUrl?.contains("limit=5") == true)
    }
}
