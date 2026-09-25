package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.catalog.CatalogClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transport that answers every GET under /works with one canned response and remembers the URL. */
private class WorksTransport(private val response: HttpResponse) : HttpTransport {
    var lastUrl: String? = null
    var lastAuth: String? = null
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastAuth = headers["Authorization"]
        return if (url.contains("/api/v1/works")) response else HttpResponse(404, "")
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
        HttpResponse(405, "")
}

class CatalogClientTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")

    @Test fun buildsThePageUrl() {
        assertEquals(
            "$base/api/v1/works?limit=24&content_type=movie&sort=recent&include=artwork%2Cprimary_asset",
            CatalogClient.pageUrl(base, "movie", CatalogClient.Sort.RECENT, 24, null),
        )
        assertEquals(
            "$base/api/v1/works?limit=5&sort=title&include=artwork%2Cprimary_asset&cursor=a%2Fb%3D",
            CatalogClient.pageUrl("$base/", null, CatalogClient.Sort.TITLE, 5, "a/b="),
        )
    }

    @Test fun parsesAPageWithEmbeds() {
        val body = """{"items":[
            {"id":"w1","title":"Arrival","content_type":"movie","year":2016,"created_at":"2026-08-01T00:00:00Z",
             "attributes":{"director":"V"},
             "artwork":{"asset_id":"a5","blob_hash":"blake3:33","mime":"image/jpeg","content_url":"/api/v1/blobs/blake3:33/content"},
             "primary_asset":{"asset_id":"a1","edition_id":"e1","blob_hash":"blake3:11","mime":"video/x-matroska","size":1,"duration_seconds":6960.5,"content_url":"/api/v1/blobs/blake3:11/content"}},
            {"id":"w2","title":"Album","content_type":"music","attributes":{"artist":"Artist A"},"artwork":null,"primary_asset":null}
        ],"next_cursor":"c2"}"""
        val t = WorksTransport(HttpResponse(200, body))
        val page = CatalogClient(t, base, cred).page("movie")
        assertEquals("c2", page.nextCursor)
        assertEquals(2, page.items.size)
        val w1 = page.items[0]
        assertEquals("a1", w1.primaryAssetId)
        assertEquals("blake3:11", w1.blobHash)
        assertEquals("video/x-matroska", w1.mime)
        assertEquals("/api/v1/blobs/blake3:33/content", w1.artworkPath)
        assertTrue(w1.isPlayable)
        val w2 = page.items[1]
        assertNull(w2.primaryAssetId)
        assertNull(w2.artworkPath)
        assertEquals("Artist A", w2.artist)
        assertEquals("Bearer tok", t.lastAuth)
    }

    @Test fun anOlderNodeWithoutEmbedsStillParses() {
        val body = """{"items":[{"id":"w1","title":"Arrival","content_type":"movie"}]}"""
        val t = WorksTransport(HttpResponse(200, body))
        val page = CatalogClient(t, base, cred).page("movie")
        assertNull(page.nextCursor)
        assertNull(page.items[0].primaryAssetId)
        assertNull(page.items[0].artworkPath)
    }

    @Test fun recentReSortsWhatTheNodeReturned() {
        // An older node ignores sort=recent and answers in title order.
        val body = """{"items":[
            {"id":"a","title":"A","created_at":"2026-08-01T00:00:00Z"},
            {"id":"b","title":"B","created_at":"2026-08-03T00:00:00Z"},
            {"id":"c","title":"C","created_at":"2026-08-02T00:00:00Z"}]}"""
        val t = WorksTransport(HttpResponse(200, body))
        val recent = CatalogClient(t, base, cred).recent(null, limit = 2)
        assertEquals(listOf("b", "c"), recent.map { it.id })
    }

    @Test fun aFailedPageThrowsWithTheStatus() {
        val t = WorksTransport(HttpResponse(503, ""))
        val e = runCatching { CatalogClient(t, base, cred).page(null) }.exceptionOrNull()
        assertTrue(e?.message?.contains("503") == true)
    }

    @Test fun posterUrlPrefersTheEmbedAndFallsBackToTheRedirect() {
        assertEquals(
            "$base/api/v1/blobs/blake3:33/content",
            Artwork.posterUrl("$base/", "w1", "/api/v1/blobs/blake3:33/content"),
        )
        assertEquals("https://cdn.example/p.jpg", Artwork.posterUrl(base, "w1", "https://cdn.example/p.jpg"))
        assertEquals("$base/api/v1/works/w%3A1/artwork", Artwork.posterUrl(base, "w:1", null))
        assertEquals("$base/api/v1/works/w1/artwork", Artwork.posterUrl(base, "w1", "  "))
    }
}

class CatalogFacetUrlTest {
    @Test fun artistAndAuthorFacets() {
        assertEquals(
            "https://h/api/v1/works?limit=200&content_type=music&artist=Artist+A&sort=title&include=artwork%2Cprimary_asset",
            CatalogClient.pageUrl("https://h", "music", CatalogClient.Sort.TITLE, 200, null, artist = "Artist A"),
        )
        assertEquals(
            "https://h/api/v1/works?limit=5&content_type=book&author=A.+B&sort=recent&include=artwork%2Cprimary_asset",
            CatalogClient.pageUrl("https://h", "book", CatalogClient.Sort.RECENT, 5, null, author = "A. B"),
        )
    }
}
