package one.rarebit.heyarr.desktop.library

import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.core.theme.MediaType

/**
 * The one playable file a work-detail read hands back — heyarr-core `PrimaryAssetRef`
 * (ADR-0075): the first `primary`-role asset of the work that actually holds bytes.
 * Enough to play the blob without a second read. [blobHash] is heyarr's content hash
 * (`blake3:<64 hex>`) and goes into the blob route verbatim; [contentUrl] is the
 * server-assembled relative path (`/api/v1/blobs/<hash>/content`), kept for reference —
 * the player builds its own absolute URL from [blobHash] so the path shape is tested.
 */
data class PrimaryAsset(
    val blobHash: String,
    val assetId: String? = null,
    val editionId: String? = null,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val contentUrl: String? = null,
) {
    /** A one-line summary for the detail pane (media type, then size). */
    val summary: String
        get() = listOfNotNull(
            mime?.takeIf { it.isNotBlank() },
            sizeBytes?.let { formatBytes(it) },
        ).joinToString(" · ")

    companion object {
        /** `1.4 GB`, `812 MB`, `3.2 KB` — base-1024, one decimal above KB. Copied from mobile's WorkAsset. */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = -1
            while (value >= 1024 && unit < units.size - 1) {
                value /= 1024
                unit++
            }
            return if (value >= 100) {
                "${value.toLong()} ${units[unit]}"
            } else {
                String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
            }
        }
    }
}

/**
 * A single work with its browse embeds — heyarr-core `WorkDetail` from
 * `GET /api/v1/works/{id}`. The node ALWAYS inlines `primary_asset` on the detail read
 * (the handler asks for both embeds unconditionally), so no `include=` is needed; the
 * field is simply `null` when the work has no file that holds bytes.
 */
data class WorkDetail(
    val work: Work,
    val primaryAsset: PrimaryAsset? = null,
    /** The `artwork` embed's relative content path (`/api/v1/blobs/<hash>/content`), when the work has a poster. */
    val artworkPath: String? = null,
    val artworkHash: String? = null,
    /**
     * The `backdrop` embed's relative content path, when the node holds a wide image for the
     * work (heyarr-core ADR-0122: a shipped fanart, or the TMDB backdrop the node's enrich job
     * stored). Null on a node that predates the embed, and null — never the poster — when the
     * work has none.
     */
    val backdropPath: String? = null,
    val backdropHash: String? = null,
    /** The work's string attributes (overview / synopsis when a provider or the node's enrich job filled them). */
    val attributes: Map<String, String> = emptyMap(),
) {
    /** True when there is a blob we can hand to the player. */
    val isPlayable: Boolean get() = primaryAsset != null && primaryAsset.blobHash.isNotBlank()

    /**
     * The node image the WIDE detail hero should show: a film's or series' backdrop when the
     * node has one, else its poster (centre-cropped, as before). Every other kind shows its
     * poster/cover. The same landscape-first rule the external fallback applies in `Cover`.
     */
    fun heroArtPath(type: MediaType): String? = when (type) {
        MediaType.MOVIE, MediaType.SERIES -> backdropPath ?: artworkPath
        else -> artworkPath
    }

    /**
     * True when the node already describes this work as fully as the per-device public lookup
     * would, so the detail screen need not ask (and cache) it again on every device: a film with
     * a node poster and a node overview. A series still asks, because the lookup is also where
     * its unheld episodes' stills and air dates come from; other kinds are unchanged.
     */
    fun nodeDescribes(type: MediaType): Boolean =
        type == MediaType.MOVIE &&
            artworkPath != null &&
            !attributes["overview"].isNullOrBlank()
}

/**
 * Dependency-free reader for the `GET /api/v1/works/{id}` object — the [Work] header
 * plus its inlined `primary_asset`. Hand-rolled over [JsonScan], JVM-tested, the same
 * stance as [WorksJson]. Each embed is read from its OWN balanced slice, so a
 * `blob_hash` inside `primary_asset` is never mistaken for a top-level field.
 */
object WorkDetailJson {

    /** Parse the detail body, or null if it has no id. */
    fun parse(body: String): WorkDetail? {
        val work = WorksJson.parseOne(body) ?: return null
        val obj = JsonScan.rootObject(body) ?: return WorkDetail(work)
        val artwork = JsonScan.objectAt(obj, "artwork")
        val backdrop = JsonScan.objectAt(obj, "backdrop")
        return WorkDetail(
            work,
            parsePrimaryAsset(obj),
            artworkPath = artwork?.let { JsonScan.stringField(it, "content_url") },
            artworkHash = artwork?.let { JsonScan.stringField(it, "blob_hash") },
            backdropPath = backdrop?.let { JsonScan.stringField(it, "content_url") },
            backdropHash = backdrop?.let { JsonScan.stringField(it, "blob_hash") },
            attributes =
            JsonScan.objectAt(obj, "attributes")?.let { a ->
                listOf(
                    "overview",
                    "synopsis",
                    "description",
                    "summary",
                    "author",
                    "artist",
                    "narrator",
                    "genre",
                ).mapNotNull { k ->
                    JsonScan.stringField(a, k)?.let {
                        k to
                            it
                    }
                }.toMap()
            }
                ?: emptyMap(),
        )
    }

    /**
     * The `primary_asset` embed of a work object, or null when it is absent, JSON `null`,
     * or carries no `blob_hash` (a `linked` asset with no bytes cannot be played).
     */
    fun parsePrimaryAsset(workObj: String): PrimaryAsset? {
        val primary = JsonScan.objectAt(workObj, "primary_asset") ?: return null
        val hash = JsonScan.stringField(primary, "blob_hash")?.takeIf { it.isNotBlank() } ?: return null
        return PrimaryAsset(
            blobHash = hash,
            assetId = JsonScan.stringField(primary, "asset_id"),
            editionId = JsonScan.stringField(primary, "edition_id"),
            mime = JsonScan.stringField(primary, "mime"),
            sizeBytes = JsonScan.longField(primary, "size"),
            contentUrl = JsonScan.stringField(primary, "content_url"),
        )
    }
}
