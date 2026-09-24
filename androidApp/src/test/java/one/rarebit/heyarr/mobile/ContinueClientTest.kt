package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.catalog.ContinueClient
import one.rarebit.heyarr.mobile.catalog.ContinueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class OneTransport(private val response: HttpResponse) : HttpTransport {
    var lastUrl: String? = null
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        return response
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = HttpResponse(405, "")
}

class ContinueClientTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")

    private val body = """{"items":[
      {"session":{"id":"s3","asset_id":"a1","device_id":"d1","verb":"watch","state":"stopped","progress":{"locator":"2000","unit":"seconds"}},
       "work":{"id":"w1","content_type":"movie","title":"Arrival","year":2016,"artwork":{"asset_id":"a5","blob_hash":"blake3:33","content_url":"/api/v1/blobs/blake3:33/content"}},
       "edition":{"id":"e1","label":"2160p","attributes":{"season":1,"episode":4}},
       "asset":{"asset_id":"a1","edition_id":"e1","blob_hash":"blake3:11","mime":"video/x-matroska","size":1,"duration_seconds":8000,"content_url":"/api/v1/blobs/blake3:11/content"}},
      {"session":{"id":"s9","state":"paused","progress":{"locator":"12","unit":"page"}},
       "work":{"id":"w3","content_type":"book","title":"Dune","year":null,"artwork":null},
       "edition":{"id":"e3","label":"first","attributes":{}},
       "asset":{"asset_id":"a3","edition_id":"e3","blob_hash":null,"mime":null,"size":null,"duration_seconds":null,"content_url":""}}
    ]}"""

    @Test fun buildsTheUrl() {
        assertEquals("$base/api/v1/consumption/continue?limit=12", ContinueClient.continueUrl("$base/", 12))
    }

    @Test fun parsesEntriesFromTheirNestedSlices() {
        val entries = ContinueClient.parse(body)
        assertEquals(2, entries.size)
        val film = entries[0]
        assertEquals("s3", film.sessionId)
        assertEquals("stopped", film.state)
        assertEquals("w1", film.workId)
        assertEquals("Arrival", film.workTitle)
        assertEquals(2016, film.year)
        assertEquals("/api/v1/blobs/blake3:33/content", film.artworkPath)
        assertEquals("a1", film.assetId)
        assertEquals("blake3:11", film.blobHash)
        assertEquals(8000.0, film.durationSeconds!!, 0.0)
        assertEquals(2000.0, film.positionSeconds!!, 0.0)
        assertEquals(0.25f, film.fraction!!, 0.0001f)
        assertEquals("S01E04", film.subtitle)
        assertTrue(film.isPlayable)

        val book = entries[1]
        assertNull(book.blobHash)
        assertNull(book.positionSeconds) // pages are not seconds
        assertNull(book.fraction)
        assertEquals("first", book.subtitle)
        assertNull(book.artworkPath)
    }

    @Test fun railAndUnavailable() {
        val ok = OneTransport(HttpResponse(200, body))
        val rail = ContinueClient(ok, base, cred).rail(limit = 5)
        assertTrue(rail is ContinueClient.Outcome.Rail && rail.entries.size == 2)
        assertEquals("$base/api/v1/consumption/continue?limit=5", ok.lastUrl)

        assertTrue(ContinueClient(OneTransport(HttpResponse(404, "")), base, cred).rail() is ContinueClient.Outcome.Unavailable)
        assertTrue(ContinueClient(OneTransport(HttpResponse(403, "")), base, cred).rail() is ContinueClient.Outcome.Unavailable)
        assertTrue(runCatching { ContinueClient(OneTransport(HttpResponse(500, "")), base, cred).rail() }.isFailure)
    }

    @Test fun anEntryMissingItsWorkOrAssetIsSkipped() {
        val entries = ContinueClient.parse("""{"items":[{"session":{"id":"s1"},"work":{"id":"w1"}},{"session":{"id":"s2"},"asset":{"asset_id":"a"}}]}""")
        assertEquals(0, entries.size)
        assertEquals(emptyList<ContinueEntry>(), ContinueClient.parse("""{"items":[]}"""))
    }
}
