package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.books.BooksClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BooksClientTest {

    @Test
    fun authorsUrlHasLimitAndCursor() {
        assertEquals(
            "https://h.example/api/v1/authors?limit=200",
            BooksClient.authorsUrl("https://h.example/"),
        )
        assertTrue(BooksClient.authorsUrl("https://h.example", "c/d").endsWith("&cursor=c%2Fd"))
    }

    @Test
    fun booksUrlFiltersByAuthor() {
        val url = BooksClient.booksUrl("https://h.example", "Ursula K. Le Guin")
        assertTrue(url.contains("/api/v1/works?limit=200"))
        assertTrue(url.contains("content_type=book"))
        assertTrue(url.contains("author=Ursula+K.+Le+Guin"))
        assertTrue(url.contains("include=artwork%2Cprimary_asset"))
    }

    @Test
    fun listBooksSendsBearerAndReFiltersAuthor() {
        val seenAuth = ArrayList<String>()
        val body = """
            {"items":[
              {"id":"w1","title":"The Dispossessed","attributes":{"author":"Ursula K. Le Guin"}},
              {"id":"w2","title":"Some Other Book","attributes":{"author":"Someone Else"}}
            ]}
        """.trimIndent()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                seenAuth += headers["Authorization"].orEmpty()
                return HttpResponse(200, body)
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val books = BooksClient(
            transport,
            "https://h.example",
            Credential.Bearer("heyarr_1_s"),
        ).listBooks("Ursula K. Le Guin")
        // Node returned a mismatched author too; the client drops it defensively.
        assertEquals(listOf("The Dispossessed"), books.map { it.title })
        assertTrue(seenAuth.all { it == "Bearer heyarr_1_s" })
    }
}
