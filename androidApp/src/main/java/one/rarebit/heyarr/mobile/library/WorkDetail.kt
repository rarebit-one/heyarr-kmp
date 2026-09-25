package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.core.library.EpisodeFile
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.mobile.net.Timestamps

/**
 * One file of a work — heyarr-core `Asset` (`GET /api/v1/assets`, `/assets/{id}`),
 * joined through its edition to the work. [blobHash] is null for a `linked` asset
 * (ADR-0020: no blob at all); only an asset with a hash can be played directly.
 * [sizeBytes] comes from a second read (`GET /blobs/{hash}` → `size`) — the asset
 * view itself carries no size.
 */
data class WorkAsset(
    override val id: String,
    val editionId: String,
    override val role: String? = null,
    override val filename: String? = null,
    override val mime: String? = null,
    override val blobHash: String? = null,
    /** `managed` / `linked` / `vault`. */
    val sourceClass: String? = null,
    override val sourcePath: String? = null,
    /** Set when the source path vanished (not a deletion). */
    val missingSince: String? = null,
    val sizeBytes: Long? = null,
    val createdAt: String? = null,
    /** The edition's label (`GET /editions/{id}` → `label`), e.g. "1080p BluRay". */
    override val editionLabel: String? = null,
) : EpisodeFile {
    override val isPlayable: Boolean get() = !blobHash.isNullOrBlank() && missingSince == null
    val isMissing: Boolean get() = missingSince != null

    /** A one-line "quality" summary: edition label, role, MIME, size. */
    val quality: String
        get() = listOfNotNull(
            editionLabel?.takeIf { it.isNotBlank() },
            role?.takeIf { it.isNotBlank() && it != "primary" },
            mime?.takeIf { it.isNotBlank() },
            sizeBytes?.let { formatBytes(it) },
            sourceClass?.takeIf { it.isNotBlank() && it != "managed" },
        ).joinToString(" · ")

    companion object {
        /** `1.4 GB`, `812 MB`, `3.2 KB` — base-1024, one decimal above KB. Pure, unit-tested. */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = -1
            while (value >= 1024 && unit < units.size - 1) {
                value /= 1024
                unit++
            }
            return if (value >=
                100
            ) {
                "${value.toLong()} ${units[unit]}"
            } else {
                String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
            }
        }
    }
}

/**
 * A want for this work — heyarr-core `DesiredItem` (`GET /api/v1/desired?work_id=`).
 * The §64 acquisition facts ride along as the server derives them ([state],
 * [phase], [content], [placement]) so the screen can say "searching", "acquired",
 * "we have it but not everywhere" without re-deriving anything.
 */
data class Want(
    val id: String,
    /** `work` or `edition` (an item-scoped want a followed source projected). */
    val scope: String? = null,
    val workId: String? = null,
    val editionId: String? = null,
    val qualityProfileId: String? = null,
    val monitor: Boolean = true,
    val reason: String? = null,
    val state: String? = null,
    val phase: String? = null,
    val managed: Boolean? = null,
    val content: String? = null,
    val placement: String? = null,
    val detail: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    /** The one line the screen shows for the want's status. */
    val status: String
        get() = listOfNotNull(
            state?.takeIf { it.isNotBlank() },
            phase?.takeIf { it.isNotBlank() && it != state },
            if (monitor) "monitored" else "one-off",
        ).joinToString(" · ")

    val recency: String? get() = updatedAt ?: createdAt
}

/**
 * Dependency-free readers for the detail screen's reads — the `{items, next_cursor?}`
 * asset page, a single `Edition`, a `Blob` (for its size) and the `DesiredItem` page.
 * Hand-rolled over [JsonScan], JVM-tested, the same stance as [WorksJson].
 */
object WorkDetailJson {

    fun parseAssets(body: String): List<WorkAsset> =
        JsonScan.objectsOf(body, listOf("items", "assets", "data")).mapNotNull { parseAsset(it) }

    fun parseAsset(body: String): WorkAsset? {
        val obj = JsonScan.rootObject(body) ?: return null
        val id = JsonScan.stringField(obj, "id") ?: return null
        val editionId = JsonScan.stringField(obj, "edition_id") ?: return null
        // `GET /works/{id}/assets` (#429) inlines the edition label/type and the blob's
        // size + media type, so a WorkAsset off that route is complete with no edition
        // or blob join; those fields are simply absent on the plain `/assets` shape.
        return WorkAsset(
            id = id,
            editionId = editionId,
            role = JsonScan.stringField(obj, "role"),
            filename = JsonScan.stringField(obj, "filename"),
            mime = JsonScan.stringField(obj, "mime") ?: JsonScan.stringField(obj, "blob_mime"),
            blobHash = JsonScan.stringField(obj, "blob_hash"),
            sourceClass = JsonScan.stringField(obj, "source_class"),
            sourcePath = JsonScan.stringField(obj, "source_path"),
            missingSince = JsonScan.stringField(obj, "missing_since"),
            sizeBytes = JsonScan.longField(obj, "blob_size"),
            createdAt = JsonScan.stringField(obj, "created_at"),
            editionLabel = JsonScan.stringField(obj, "edition_label"),
        )
    }

    /** `GET /editions/{id}` → (work_id, label), or null. */
    fun parseEdition(body: String): Edition? {
        val obj = JsonScan.rootObject(body) ?: return null
        val id = JsonScan.stringField(obj, "id") ?: return null
        val workId = JsonScan.stringField(obj, "work_id") ?: return null
        return Edition(id, workId, JsonScan.stringField(obj, "label"), JsonScan.stringField(obj, "edition_type"))
    }

    /** `GET /blobs/{hash}` → `size`, or null. */
    fun parseBlobSize(body: String): Long? = JsonScan.rootObject(body)?.let { JsonScan.longField(it, "size") }

    fun parseWants(body: String): List<Want> =
        JsonScan.objectsOf(body, listOf("items", "desired", "data")).mapNotNull { parseWantObject(it) }

    /** One `DesiredItem` object (a `GET /desired/{id}` or `PATCH` response), or null. */
    fun parseWant(body: String): Want? = JsonScan.rootObject(body)?.let { parseWantObject(it) }

    private fun parseWantObject(obj: String): Want? {
        val id = JsonScan.stringField(obj, "id") ?: return null
        val acq = JsonScan.objectAt(obj, "acquisition")
        return Want(
            id = id,
            scope = JsonScan.stringField(obj, "scope"),
            workId = JsonScan.stringField(obj, "work_id"),
            editionId = JsonScan.stringField(obj, "edition_id"),
            qualityProfileId = JsonScan.stringField(obj, "quality_profile_id"),
            monitor = JsonScan.boolField(obj, "monitor") ?: true,
            reason = JsonScan.stringField(obj, "reason"),
            state = acq?.let { JsonScan.stringField(it, "state") },
            phase = acq?.let { JsonScan.stringField(it, "phase") },
            managed = acq?.let { JsonScan.boolField(it, "managed") },
            content = acq?.let { JsonScan.stringField(it, "content") },
            placement = acq?.let { JsonScan.stringField(it, "placement") },
            detail = acq?.let { JsonScan.stringField(it, "detail") },
            createdAt = JsonScan.stringField(obj, "created_at"),
            updatedAt = JsonScan.stringField(obj, "updated_at"),
        )
    }

    /** The page's `next_cursor`, when there is another page. */
    fun nextCursor(body: String): String? = WorksJson.nextCursor(body)

    /** Order most-recent first on each want's `updated_at` (else `created_at`). */
    fun recentFirst(wants: List<Want>): List<Want> = Timestamps.recentFirst(wants) { it.recency }
}

/** heyarr-core `Edition`, reduced to what joins an asset to its work and labels it. */
data class Edition(val id: String, val workId: String, val label: String? = null, val editionType: String? = null)

/**
 * A correction to a work's catalogue facts — the `PATCH /api/v1/works/{id}` body
 * (heyarr-core #428, `WorkPatch`). Every field is optional and an omitted one is left
 * alone; [year] is the one distinction the API draws twice — `null` here **omits** it
 * (leave the year as it is) while [clearYear] sends `"year": 0`, which *clears* the
 * year. [contentType] must be one the node knows or the PATCH answers 400.
 *
 * The wire body is built here (not the transport) so the "omit vs. clear" encoding is
 * unit-tested on plain JVM, the same stance as [one.rarebit.heyarr.mobile.playback.ClientCapabilities].
 */
data class WorkPatch(
    val title: String? = null,
    val year: Int? = null,
    val clearYear: Boolean = false,
    val contentType: String? = null,
) {
    /** True when nothing would change — the screen refuses to send an empty PATCH. */
    val isEmpty: Boolean get() = title == null && year == null && !clearYear && contentType == null

    /** The JSON body: only the fields actually being changed; `year:0` when [clearYear]. */
    fun body(): String {
        val parts = ArrayList<String>(3)
        title?.let { parts.add("\"title\":${quote(it)}") }
        when {
            clearYear -> parts.add("\"year\":0")
            year != null -> parts.add("\"year\":$year")
        }
        contentType?.let { parts.add("\"content_type\":${quote(it)}") }
        return parts.joinToString(",", "{", "}")
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
