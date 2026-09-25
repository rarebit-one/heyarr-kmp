package one.rarebit.heyarr.desktop.music

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.catalog.Grouping
import one.rarebit.heyarr.desktop.catalog.GroupingJson
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.WorksJson
import java.net.URLEncoder

/**
 * The MUSIC browse reach, over the shared blocking [HttpTransport] + a bearer
 * [Credential]. Three reads, all `/api/v1`, all paged on `next_cursor` to the end:
 *
 *  - [listArtists]  `GET /artists`                     — music works grouped by artist (ADR-0075)
 *  - [listAlbums]   `GET /works?content_type=music&artist=…` — one artist's works
 *  - [listTracks]   `GET /works/{id}/assets`           — a work's audio assets (tracks)
 *
 * Adapted from heyarr-mobile's `music.MusicClient` (trimmed to the desktop browse slice).
 */
class MusicClient(private val http: HttpTransport, private val baseUrl: String, private val credential: Credential) {
    /** Every music artist, name-ordered as the server pages them. */
    fun listArtists(): List<Grouping> = pageAll(
        url = { cursor -> artistsUrl(baseUrl, cursor) },
        parse = GroupingJson::parse,
        cursor = GroupingJson::nextCursor,
        what = "GET /artists",
    )

    /** One artist's works (albums/singles). Older nodes ignore `artist=`, so re-filter defensively. */
    fun listAlbums(artist: String): List<Work> = pageAll(
        url = { cursor -> albumsUrl(baseUrl, artist, cursor) },
        parse = WorksJson::parse,
        cursor = WorksJson::nextCursor,
        what = "GET /works?artist",
    ).filter { it.artist == null || it.artist == artist }

    /** A work's tracks: playable, primary-role, audio assets, ordered by filename then id. */
    fun listTracks(workId: String): List<Track> = pageAll(
        url = { cursor -> tracksUrl(baseUrl, workId, cursor) },
        parse = TracksJson::parse,
        cursor = TracksJson::nextCursor,
        what = "GET /works/$workId/assets",
    ).filter { it.isPlayable && it.isPrimaryRole && it.isAudio }
        .sortedWith(compareBy({ it.filename?.lowercase() ?: "" }, { it.id }))

    private fun <T> pageAll(
        url: (String?) -> String,
        parse: (String) -> List<T>,
        cursor: (String) -> String?,
        what: String,
    ): List<T> {
        val all = ArrayList<T>()
        var next: String? = null
        var pages = 0
        do {
            val resp = http.get(url(next), credential.asHeader())
            require(resp.status == 200) { "music: $what failed: HTTP ${resp.status}" }
            all.addAll(parse(resp.body))
            next = cursor(resp.body)
            pages++
        } while (next != null && pages < MAX_PAGES)
        return all
    }

    companion object {
        const val PAGE_LIMIT = 200
        const val MAX_PAGES = 50

        /** `include=artwork,primary_asset`, percent-encoded exactly as the mobile client sends it. */
        private val INCLUDE = URLEncoder.encode("artwork,primary_asset", "UTF-8")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun page(base: String, cursor: String?) =
            if (cursor.isNullOrBlank()) base else "$base&cursor=${enc(cursor)}"

        fun artistsUrl(baseUrl: String, cursor: String? = null): String =
            page(baseUrl.trimEnd('/') + "/api/v1/artists?limit=" + PAGE_LIMIT, cursor)

        fun albumsUrl(baseUrl: String, artist: String, cursor: String? = null): String = page(
            baseUrl.trimEnd('/') + "/api/v1/works?limit=" + PAGE_LIMIT +
                "&content_type=music&artist=" + enc(artist) + "&sort=title&include=" + INCLUDE,
            cursor,
        )

        fun tracksUrl(baseUrl: String, workId: String, cursor: String? = null): String =
            page(baseUrl.trimEnd('/') + "/api/v1/works/" + enc(workId) + "/assets?limit=" + PAGE_LIMIT, cursor)
    }
}
