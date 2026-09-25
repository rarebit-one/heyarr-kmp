package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.core.library.CatalogWork

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
}
