package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.catalog.GroupingJson
import one.rarebit.heyarr.desktop.music.MusicClient
import one.rarebit.heyarr.desktop.music.TracksJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicClientTest {

    @Test
    fun artistsUrlHasLimitAndCursor() {
        assertEquals(
            "https://h.example/api/v1/artists?limit=200",
            MusicClient.artistsUrl("https://h.example/"),
        )
        assertTrue(MusicClient.artistsUrl("https://h.example", "a b").endsWith("&cursor=a+b"))
    }

    @Test
    fun albumsUrlFiltersByArtistWithIncludeEncoded() {
        val url = MusicClient.albumsUrl("https://h.example", "Aphex Twin")
        assertTrue(url.contains("/api/v1/works?limit=200"))
        assertTrue(url.contains("content_type=music"))
        assertTrue(url.contains("artist=Aphex+Twin"))
        assertTrue(url.contains("sort=title"))
        // include=artwork,primary_asset must be percent-encoded (comma → %2C).
        assertTrue(url.contains("include=artwork%2Cprimary_asset"))
    }

    @Test
    fun tracksUrlIsWorkAssetsRoute() {
        assertEquals(
            "https://h.example/api/v1/works/w1/assets?limit=200",
            MusicClient.tracksUrl("https://h.example", "w1"),
        )
    }

    @Test
    fun parsesArtistsGrouping() {
        val body = """
            {"items":[
              {"name":"Aphex Twin","work_count":12,"artwork":{"content_url":"/api/v1/blobs/blake3:ab/content"}},
              {"work_count":3},
              {"name":"Boards of Canada","work_count":7}
            ],"next_cursor":"n1"}
        """.trimIndent()
        val artists = GroupingJson.parse(body)
        assertEquals(2, artists.size) // the nameless row is skipped
        assertEquals("Aphex Twin", artists[0].name)
        assertEquals(12, artists[0].workCount)
        assertEquals("/api/v1/blobs/blake3:ab/content", artists[0].artworkPath)
        assertEquals("n1", GroupingJson.nextCursor(body))
    }

    @Test
    fun parsesTracksAndFiltersPlayableAudio() {
        val body = """
            {"items":[
              {"id":"a1","edition_id":"e1","filename":"02 - Second.flac","mime":"audio/flac",
               "blob_hash":"blake3:${"b".repeat(64)}","blob_size":10485760,"edition_label":"Vinyl"},
              {"id":"a2","edition_id":"e1","filename":"01 - First.flac","blob_mime":"audio/flac",
               "blob_hash":"blake3:${"a".repeat(64)}"},
              {"id":"a3","edition_id":"e1","filename":"cover.jpg","mime":"image/jpeg","blob_hash":"blake3:${"c".repeat(64)}"},
              {"id":"a4","edition_id":"e1","filename":"gone.flac","mime":"audio/flac","blob_hash":"blake3:${"d".repeat(64)}","missing_since":"2026-01-01T00:00:00Z"}
            ]}
        """.trimIndent()
        val tracks = TracksJson.parse(body)
        assertEquals(4, tracks.size)
        val playableAudio = tracks.filter { it.isPlayable && it.isPrimaryRole && it.isAudio }
        assertEquals(2, playableAudio.size) // cover (image) and missing file excluded
        // Title strips extension and the leading "NN - " prefix.
        assertEquals("Second", tracks[0].title)
        assertTrue(tracks[0].subtitle.contains("Vinyl"))
    }

    @Test
    fun listTracksSortsByFilenameThenId() {
        val body = """
            {"items":[
              {"id":"a1","edition_id":"e1","filename":"02 - Second.flac","mime":"audio/flac","blob_hash":"blake3:${"b".repeat(64)}"},
              {"id":"a2","edition_id":"e1","filename":"01 - First.flac","mime":"audio/flac","blob_hash":"blake3:${"a".repeat(64)}"}
            ]}
        """.trimIndent()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(200, body)
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = HttpResponse(405, "")
        }
        val tracks = MusicClient(transport, "https://h.example", Credential.Bearer("t")).listTracks("w1")
        assertEquals(listOf("First", "Second"), tracks.map { it.title }) // ordered by filename
    }
}
