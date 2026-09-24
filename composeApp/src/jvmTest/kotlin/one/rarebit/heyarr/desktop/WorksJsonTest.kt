package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.library.LibraryClient
import one.rarebit.heyarr.desktop.library.WorksJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorksJsonTest {

    @Test
    fun parsesItemsEnvelopeAndCursor() {
        val body = """
            {
              "items": [
                {"id": "w1", "title": "The Left Hand of Darkness", "year": 1969,
                 "attributes": {"author": "Ursula K. Le Guin"}, "updated_at": "2026-01-02T00:00:00Z"},
                {"id": "w2", "name": "Blade Runner", "content_type": "movie",
                 "updated_at": "2026-03-04T00:00:00Z"}
              ],
              "next_cursor": "abc123"
            }
        """.trimIndent()

        val works = WorksJson.parse(body)
        assertEquals(2, works.size)
        assertEquals("The Left Hand of Darkness", works[0].title)
        assertEquals("Ursula K. Le Guin", works[0].author)
        assertEquals(1969, works[0].year)
        assertEquals("movie", works[1].kind)
        assertEquals("abc123", WorksJson.nextCursor(body))
    }

    @Test
    fun tolerantOfBareArrayAndNoCursor() {
        val body = """[{"id":"x","title":"Solo"}]"""
        val works = WorksJson.parse(body)
        assertEquals(1, works.size)
        assertEquals("Solo", works[0].title)
        assertNull(WorksJson.nextCursor(body))
    }

    @Test
    fun urlBuilderAddsLimitAndCursor() {
        assertEquals(
            "https://h.example/api/v1/works?limit=200&include=artwork",
            LibraryClient.worksUrl("https://h.example/", null),
        )
        assertTrue(LibraryClient.worksUrl("https://h.example", "c/d").endsWith("&cursor=c%2Fd"))
    }

    @Test
    fun libraryClientSendsBearerAndPagesToEnd() {
        val seenAuth = ArrayList<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                seenAuth += headers["Authorization"].orEmpty()
                return if (url.contains("cursor=")) {
                    HttpResponse(200, """{"items":[{"id":"w2","title":"Two"}]}""")
                } else {
                    HttpResponse(200, """{"items":[{"id":"w1","title":"One"}],"next_cursor":"n1"}""")
                }
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val works = LibraryClient(transport, "https://h.example", Credential.Bearer("heyarr_1_secret")).listWorks()
        assertEquals(2, works.size)
        assertTrue(seenAuth.all { it == "Bearer heyarr_1_secret" })
    }
}
