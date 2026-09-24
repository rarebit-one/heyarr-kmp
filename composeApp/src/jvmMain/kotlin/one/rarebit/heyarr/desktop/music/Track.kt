package one.rarebit.heyarr.desktop.music

import one.rarebit.heyarr.core.library.EpisodeFile
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.desktop.library.PrimaryAsset

/**
 * One asset of a work — heyarr-core `WorkAsset` from `GET /api/v1/works/{id}/assets`
 * (heyarr-core #429: the route is joined, so each row already inlines its blob size,
 * media type and edition label — no per-asset fan-out). For music this is a track; the
 * album's tracks are its playable audio assets.
 *
 * heyarr sends NO track- or disc-number field, so ordering and titles are derived from
 * [filename] (mirrors heyarr-mobile).
 */
data class Track(
    override val id: String,
    val editionId: String,
    override val blobHash: String? = null,
    override val filename: String? = null,
    override val mime: String? = null,
    override val role: String? = null,
    val sizeBytes: Long? = null,
    val missingSince: String? = null,
    override val editionLabel: String? = null,
    override val sourcePath: String? = null,
) : EpisodeFile {
    /** A blob we can stream, and the file is present. */
    override val isPlayable: Boolean get() = !blobHash.isNullOrBlank() && missingSince.isNullOrBlank()

    /** A `primary`-role (or unroled) asset — the album track shape, not artwork/subtitles. */
    val isPrimaryRole: Boolean get() = role.isNullOrBlank() || role == "primary"

    /** True when the asset looks like audio (by MIME, else filename extension). */
    val isAudio: Boolean get() = MediaMime.isAudio(mime, filename)

    /** Display title: filename minus extension and a leading `NN - ` track prefix. */
    val title: String
        get() {
            val base = (filename?.substringBeforeLast('.') ?: id).trim()
            return base.replace(TRACK_PREFIX, "").ifBlank { base }
        }

    /** A one-line subtitle for the row (edition, then size). */
    val subtitle: String
        get() = listOfNotNull(
            editionLabel?.takeIf { it.isNotBlank() },
            sizeBytes?.let { PrimaryAsset.formatBytes(it) },
        ).joinToString(" · ")

    companion object {
        private val TRACK_PREFIX = Regex("^\\s*\\d{1,3}\\s*[-._ ]+\\s*")
    }
}

/** Audio detection by MIME (`audio/…`) or a known audio filename extension. */
object MediaMime {
    private val AUDIO_EXT = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "aac", "wav", "wma", "alac", "aiff",
    )

    fun isAudio(mime: String?, filename: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        if (m != null && m.startsWith("audio/")) return true
        val ext = filename?.substringAfterLast('.', "")?.lowercase()
        return ext != null && ext in AUDIO_EXT
    }
}

/**
 * Reader for the `GET /works/{id}/assets` body — envelope keys `items` / `assets` /
 * `data`, or a bare array. Same hand-rolled [JsonScan] stance as the library readers; an
 * asset with no `id` or no `edition_id` is skipped.
 */
object TracksJson {

    private val ENVELOPE_KEYS = listOf("items", "assets", "data")

    fun parse(body: String): List<Track> = JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    fun nextCursor(body: String): String? =
        JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "next_cursor") }?.takeIf { it.isNotBlank() }

    private fun parseObject(obj: String): Track? {
        val id = JsonScan.stringField(obj, "id") ?: return null
        val editionId = JsonScan.stringField(obj, "edition_id") ?: return null
        return Track(
            id = id,
            editionId = editionId,
            blobHash = JsonScan.stringField(obj, "blob_hash")?.takeIf { it.isNotBlank() },
            filename = JsonScan.stringField(obj, "filename"),
            mime = JsonScan.firstString(obj, listOf("mime", "blob_mime")),
            role = JsonScan.stringField(obj, "role"),
            sizeBytes = JsonScan.longField(obj, "blob_size"),
            missingSince = JsonScan.stringField(obj, "missing_since"),
            editionLabel = JsonScan.stringField(obj, "edition_label"),
            sourcePath = JsonScan.stringField(obj, "source_path"),
        )
    }
}
