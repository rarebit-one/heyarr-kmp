package one.rarebit.heyarr.mobile.catalog

import one.rarebit.heyarr.mobile.library.Work
import java.net.URLEncoder

/**
 * Where a work's poster is fetched from (heyarr-core ADR-0075).
 *
 * A row that carried the `artwork` embed names the blob route directly — no redirect,
 * and a URL keyed by the hash, which is what makes the image cache honest. A row
 * that did not (an older node, or a list read without `include=`) falls back to
 * `GET /works/{id}/artwork`, which 307s to the same place or 404s; the Poster
 * composable renders a placeholder on either failure. Pure, unit-tested.
 */
object Artwork {

    fun posterUrl(baseUrl: String, work: Work): String = posterUrl(baseUrl, work.id, work.artworkPath)

    fun posterUrl(baseUrl: String, workId: String, artworkPath: String?): String {
        val base = baseUrl.trimEnd('/')
        val path = artworkPath?.takeIf { it.isNotBlank() }
        if (path != null) {
            return if (path.startsWith("http://") ||
                path.startsWith("https://")
            ) {
                path
            } else {
                base + "/" + path.trimStart('/')
            }
        }
        return base + "/api/v1/works/" + URLEncoder.encode(workId, "UTF-8") + "/artwork"
    }
}
