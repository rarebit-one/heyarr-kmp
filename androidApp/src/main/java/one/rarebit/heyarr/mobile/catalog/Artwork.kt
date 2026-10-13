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

    /**
     * Nominal on-screen width, in dp, of a card, rail, grid or list poster (ADR-0109). The
     * request is made in physical pixels (dp × density), so a 3x phone asks for the 512
     * bucket rather than upscaling 256 (Codex on #137).
     */
    const val CARD_ARTWORK_DP = 140

    /**
     * On-screen width, in dp, of a wide-aspect card (movie, series, video) in a rail:
     * `MediaCard` draws those at 280 dp, twice [CARD_ARTWORK_DP], so sizing them as a poster
     * card asked for a bucket smaller than drawn (#142).
     */
    const val WIDE_CARD_ARTWORK_DP = 280

    /** Nominal on-screen width, in dp, of a full-width hero: Home's spotlight, a detail backdrop. */
    const val HERO_ARTWORK_DP = 360

    /**
     * The width, in dp, of one column of an `Adaptive(minCellDp)` grid laid out across
     * [availableDp] with [spacingDp] between columns — the same arithmetic Compose's
     * `GridCells.Adaptive` / `StaggeredGridCells.Adaptive` use. A grid cell can be far wider
     * than its card's nominal width (one 280 dp column fills a phone), so the grid sizes its
     * artwork from this rather than from a constant (#142).
     */
    fun adaptiveCellDp(availableDp: Int, minCellDp: Int, spacingDp: Int): Int {
        val count = maxOf((availableDp + spacingDp) / (minCellDp + spacingDp), 1)
        return (availableDp - spacingDp * (count - 1)) / count
    }

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
