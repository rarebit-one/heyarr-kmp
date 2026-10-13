package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.core.library.CatalogWork
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus

/**
 * A library entry as browsed from heyarr's native resources API
 * (`GET /api/v1/works`, `GET /api/v1/works/{id}` — heyarr-core `Work`). A thin
 * projection — id, a display title, a kind, a year, the normalised [workKey] and the
 * server timestamps (which drive the settings-free "recent first" order) — of the far
 * richer server model (§ works/editions/assets). The work's assets, wants and
 * followed source are loaded separately by the detail screen ([WorkDetailClient]).
 *
 * [blobHash] / [mime] are the OPTIONAL playback handles: when a browse row carries a
 * primary asset's content hash, the M10 player can stream it directly
 * (`/api/v1/blobs/{hash}/content`) — that is the "tap → it plays" path. The live
 * `Work` view does not inline one, so the detail screen's asset list is where Play
 * actually lives; the row keeps the handle for a server that does inline it.
 */
data class Work(
    override val id: String,
    override val title: String,
    override val kind: String? = null,
    /** A directly-streamable asset's BLAKE3 content hash, when the browse row carries one. */
    val blobHash: String? = null,
    /** The primary asset's MIME, when known — drives video-vs-audio and the container hint. */
    val mime: String? = null,
    /** The primary asset's id, when the row carries one — what `POST /playback/plan` is asked about. */
    val primaryAssetId: String? = null,
    /**
     * The poster's byte route, RELATIVE to the node (`/api/v1/blobs/{hash}/content`), from
     * the `artwork` embed (heyarr-core ADR-0075). Null when the row carried no embed or the
     * work has no poster; `catalog/Artwork` decides what to fetch either way.
     */
    val artworkPath: String? = null,
    /**
     * Thumbnail size map from the `artwork` embed's `sizes` sub-object (heyarr-core
     * ADR-0109): keys are pixel widths ("256", "512", "1024"), values are blob-route
     * paths in the same form as [artworkPath]. Empty when the row predates ADR-0109
     * or was loaded without `include=artwork`.
     */
    val artworkSizes: Map<String, String> = emptyMap(),
    /** `attributes.artist` (music) / `attributes.author` (books), when the identifier wrote one. */
    val artist: String? = null,
    val author: String? = null,
    val year: Int? = null,
    /** heyarr's normalised identity (`work_key`), so a rescan converges on the same work. */
    val workKey: String? = null,
    val sortTitle: String? = null,
    /**
     * The identifiers this work is known by outside heyarr, keyed by source
     * (`tmdb` / `imdb` / `tvdb`), as `GET /works/{id}` inlines them (heyarr-core #431,
     * ADR-0050's REST twin — read-only). Empty when nothing has been reconciled or on
     * the list read, which does not carry them.
     */
    val externalIds: Map<String, String> = emptyMap(),
    /**
     * The work's string attributes as the identifier wrote them (an overview /
     * synopsis when a provider filled one, a genre, a runtime…). Read from the same
     * `attributes` object [artist] / [author] come from; empty on most nodes today.
     */
    val attributes: Map<String, String> = emptyMap(),
    /** RFC 3339 server timestamps, as sent; parsed only for ordering/display. */
    val createdAt: String? = null,
    val updatedAt: String? = null,
) : CatalogWork {
    /** True when this row can be streamed directly (the player has a hash to point at). */
    val isPlayable: Boolean get() = !blobHash.isNullOrBlank()

    /** The timestamp "recent first" orders on: last touched, else created. */
    val recency: String? get() = updatedAt ?: createdAt

    /** The node's own synopsis, when a provider wrote one under any of the usual keys. */
    val synopsis: String? get() = listOf("overview", "synopsis", "description", "summary").firstNotNullOfOrNull { k ->
        attributes[k]?.takeIf { it.isNotBlank() }
    }

    /**
     * The best poster path for [sizePx] from the thumbnail size map (heyarr-core ADR-0109).
     * When [artworkSizes] is empty — an older node or a list row without `include=artwork` —
     * the call falls back to [artworkPath], preserving existing behaviour exactly.
     *
     * Pass the result to `rememberCover` or `Artwork.posterUrl` as you would [artworkPath]
     * today; path-vs-URL handling is unchanged.
     *
     * The server publishes 256, 512 and 1024 px buckets. The call picks the smallest
     * published bucket that covers [sizePx]; when all are smaller it takes the largest.
     */
    fun artworkFor(sizePx: Int): String? {
        if (artworkSizes.isEmpty()) return artworkPath
        val sorted = artworkSizes.keys.mapNotNull { it.toIntOrNull() }.sorted()
        val key = (sorted.firstOrNull { it >= sizePx } ?: sorted.lastOrNull())?.toString()
        return key?.let { artworkSizes[it] } ?: artworkPath
    }
}

/**
 * A listed work's availability. The listing carries its `primary_asset` embed, so a work with
 * nothing to play (one a want created before anything arrived) reads from its want status.
 */
fun Work.availability(status: LibraryStatus): Availability = Availability.of(status, playable = isPlayable)
