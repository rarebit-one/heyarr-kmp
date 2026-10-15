package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.LibraryClient
import one.rarebit.heyarr.desktop.library.Work
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
    fun aListedWorkIsPlayableOnlyWithAPrimaryAsset() {
        val held = """{"id":"a","title":"Held","primary_asset":{"asset_id":"x"}}"""
        val wanted = """{"id":"b","title":"Wanted","primary_asset":null}"""
        val body = """{"items":[$held,$wanted]}"""
        val works = WorksJson.parse(body)
        assertEquals(listOf(true, false), works.map { it.playable })
        assertNull(WorksJson.parseOne("""{"id":"a","title":"Held"}""")!!.playable)
    }

    @Test
    fun urlBuilderAddsLimitAndCursor() {
        assertEquals(
            "https://h.example/api/v1/works?limit=500&include=artwork,primary_asset",
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

    @Test
    fun listenShelfRequestsOnlyItsContentTypes() {
        val urls = ArrayList<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                urls += url
                return when {
                    "content_type=music" in url -> HttpResponse(
                        200,
                        """{"items":[{"id":"m1","title":"Album","content_type":"music"}]}""",
                    )

                    else -> HttpResponse(200, """{"items":[]}""")
                }
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val works = LibraryClient(transport, "https://h.example", Credential.Bearer("token"))
            .listWorks(setOf(MediaType.MUSIC, MediaType.AUDIOBOOK, MediaType.PODCAST))
        assertEquals(listOf("m1"), works.map { it.id })
        assertEquals(3, urls.size)
        assertTrue(urls.all { "content_type=" in it && "limit=500" in it })
    }

    @Test
    fun homeStopsAfterOneRecentPageWhenItHasEnoughCards() {
        val urls = ArrayList<String>()
        val body = """{"items":[${(1..24).joinToString(",") {
            """{"id":"w$it","title":"Work $it","content_type":"movie","primary_asset":{}}"""
        }}],"next_cursor":"more"}"""
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                urls += url
                return HttpResponse(200, body)
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val works = LibraryClient(transport, "https://h.example", Credential.Bearer("token")).recentWorks()
        assertEquals(24, works.size)
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("limit=50"))
        assertTrue(urls.single().contains("sort=recent"))
    }

    @Test
    fun aSeriesDetailSearchesItsTitleInsteadOfTheWholeCatalog() {
        val urls = ArrayList<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                urls += url
                return HttpResponse(
                    200,
                    """{"items":[{"id":"series","title":"The Yellowstone","content_type":"series"},""" +
                        """{"id":"variant","title":"The Yellowstone Season 4 Mp4","content_type":"series"}]}""",
                )
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val client = LibraryClient(transport, "https://h.example", Credential.Bearer("token"))
        val series = Work("series", "The Yellowstone", "series", sortTitle = "yellowstone")
        assertEquals(listOf("variant"), client.seriesVariantsOf(series).map { it.id })
        assertEquals(1, urls.size)
        assertTrue("content_type=series" in urls.single())
        assertTrue("q=yellowstone" in urls.single())
        urls.clear()
        assertEquals(
            listOf("variant"),
            client.seriesVariantsOf(
                series,
                listOf(series, Work("variant", "The Yellowstone Season 4 Mp4", "series")),
            ).map {
                it.id
            },
        )
        assertTrue(urls.isEmpty())
        assertTrue(client.seriesVariantsOf(Work("film", "Film", "movie")).isEmpty())
        assertTrue(urls.isEmpty())
    }

    @Test
    fun downloadTitlesReadOnlyMissingIds() {
        val urls = ArrayList<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                urls += url
                return HttpResponse(200, """{"id":"w2","title":"Second"}""")
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val client = LibraryClient(transport, "https://h.example", Credential.Bearer("token"))
        val titles = client.titlesFor(listOf("w1", "w2"), catalog = listOf(Work("w1", "First")))
        assertEquals(mapOf("w1" to "First", "w2" to "Second"), titles)
        assertEquals(listOf("https://h.example/api/v1/works/w2"), urls)
    }

    @Test
    fun manyDownloadTitlesUseOneCatalogWalk() {
        val urls = ArrayList<String>()
        val body = """{"items":[${(1..11).joinToString(",") { """{"id":"w$it","title":"Work $it"}""" }}]}"""
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                urls += url
                return HttpResponse(200, body)
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val client = LibraryClient(transport, "https://h.example", Credential.Bearer("token"))
        val titles = client.titlesFor((1..11).map { "w$it" })
        assertEquals(11, titles.size)
        assertEquals(1, urls.size)
        assertTrue("?limit=500" in urls.single())
    }
}
