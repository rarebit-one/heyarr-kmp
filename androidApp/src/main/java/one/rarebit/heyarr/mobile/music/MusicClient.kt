package one.rarebit.heyarr.mobile.music

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.mobile.catalog.CatalogClient
import one.rarebit.heyarr.mobile.library.Work
import java.net.URLEncoder

/** One artist: a grouping over music works keyed by name (heyarr-core ADR-0075), never an entity. */
data class Artist(
    val name: String,
    val workCount: Int,
    val artworkPath: String? = null,
    val artworkWorkId: String? = null,
)

/**
 * Artists over `GET /api/v1/artists` (ADR-0075), with the album read delegated to the
 * catalog (`GET /works?content_type=music&artist=`). Against an older node with no
 * artists route, the same answer is derived client-side by grouping the music works
 * on `attributes.artist` — a bigger read, the same shelf.
 */
class MusicClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
    private val catalog: CatalogClient = CatalogClient(http, baseUrl, credential),
) {
    fun artists(): List<Artist> {
        val all = ArrayList<Artist>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = http.get(artistsUrl(baseUrl, cursor), credential.asHeader())
            if (resp.status == 404 && pages == 0) return MusicJson.groupByArtist(allMusicWorks())
            require(resp.status == 200) { "music: GET /artists failed: HTTP ${resp.status}" }
            all.addAll(MusicJson.parseArtists(resp.body))
            cursor =
                JsonScan.rootObject(resp.body)?.let {
                    JsonScan.stringField(it, "next_cursor")
                }?.takeIf { it.isNotBlank() }
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return all
    }

    /** An artist's albums, newest first. */
    fun albums(artist: String): List<Work> {
        val all = ArrayList<Work>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = catalog.page("music", CatalogClient.Sort.TITLE, 200, cursor, artist = artist)
            all.addAll(page.items)
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        // An older node ignores artist= and returns every album: keep only this artist's.
        return all.filter { it.artist == null || it.artist == artist }
    }

    private fun allMusicWorks(): List<Work> {
        val all = ArrayList<Work>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = catalog.page("music", CatalogClient.Sort.TITLE, 200, cursor)
            all.addAll(page.items)
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return all
    }

    companion object {
        const val MAX_PAGES = 50

        fun artistsUrl(baseUrl: String, cursor: String?): String {
            val base = baseUrl.trimEnd('/') + "/api/v1/artists?limit=200"
            return if (cursor.isNullOrBlank()) base else base + "&cursor=" + URLEncoder.encode(cursor, "UTF-8")
        }
    }
}
