package one.rarebit.heyarr.core.heyarr

import one.rarebit.heyarr.core.net.JsonScan

/**
 * One row of the node's continue rail — `GET /api/v1/consumption/continue` (ADR-0075):
 * the newest unfinished consumption session per work, with the work (and its
 * poster), the edition (the season, for a series) and the asset with its duration.
 *
 * This is NOT the encrypted personal state the MCP cannot see: consumption sessions
 * are the node's own record of what a device started through `/playback`, and the
 * bearer this client holds may read them. A progress locator arrives as text with a
 * unit (`seconds`, `percent`, `page` …); it is shown as sent and turned into a bar
 * only when the unit is one that can be.
 */
data class ContinueEntry(
    val sessionId: String,
    val verb: String,
    val state: String,
    val locator: String?,
    val unit: String?,
    val updatedAt: String?,
    val workId: String,
    val contentType: String?,
    val title: String,
    val year: Int?,
    val artworkPath: String?,
    val editionLabel: String?,
    val assetId: String?,
    val blobHash: String?,
    val durationSeconds: Double?,
) {
    /** 0..1 when the locator is seconds against a known duration, or a percent; null otherwise. */
    val fraction: Float?
        get() {
            val v = locator?.toDoubleOrNull() ?: return null
            return when (unit) {
                "seconds", "s" -> durationSeconds?.takeIf { it > 0 }?.let { (v / it).toFloat().coerceIn(0f, 1f) }
                "percent", "%" -> (v / 100.0).toFloat().coerceIn(0f, 1f)
                else -> null
            }
        }

    val progressLabel: String?
        get() {
            val v = locator?.toDoubleOrNull()
            return when {
                v == null -> locator
                unit == "seconds" || unit == "s" -> clock(v.toLong()) + (durationSeconds?.let { " / " + clock(it.toLong()) } ?: "")
                unit == "percent" || unit == "%" -> "${v.toInt()}%"
                else -> "$locator ${unit ?: ""}".trim()
            }
        }

    private fun clock(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val ss = sec.toString().padStart(2, '0')
        val mm = m.toString().padStart(2, '0')
        return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
    }
}

object ContinueJson {
    fun list(body: String): List<ContinueEntry> =
        JsonScan.objectsOf(body, listOf("items", "continue", "entries")).mapNotNull { parse(it) }

    fun parse(obj: String): ContinueEntry? {
        val session = JsonScan.objectAt(obj, "session") ?: return null
        val work = JsonScan.objectAt(obj, "work") ?: return null
        val edition = JsonScan.objectAt(obj, "edition")
        val asset = JsonScan.objectAt(obj, "asset")
        val progress = JsonScan.objectAt(session, "progress")
        val artwork = JsonScan.objectAt(work, "artwork")
        val workId = JsonScan.stringField(work, "id") ?: return null
        return ContinueEntry(
            sessionId = JsonScan.stringField(session, "id") ?: return null,
            verb = JsonScan.stringField(session, "verb") ?: "watch",
            state = JsonScan.stringField(session, "state") ?: "unknown",
            locator = progress?.let { JsonScan.stringField(it, "locator") },
            unit = progress?.let { JsonScan.stringField(it, "unit") },
            updatedAt = JsonScan.stringField(session, "updated_at"),
            workId = workId,
            contentType = JsonScan.stringField(work, "content_type"),
            title = JsonScan.stringField(work, "title") ?: workId,
            year = JsonScan.intField(work, "year"),
            artworkPath = artwork?.let { JsonScan.stringField(it, "content_url") },
            editionLabel = edition?.let { JsonScan.stringField(it, "label") },
            assetId = asset?.let { JsonScan.stringField(it, "asset_id") },
            blobHash = asset?.let { JsonScan.stringField(it, "blob_hash") },
            durationSeconds = asset?.let { doubleField(it, "duration_seconds") },
        )
    }

    private fun doubleField(json: String, key: String): Double? {
        val i = JsonScan.valueStart(json, key) ?: return null
        if (json[i] == '"') return null
        var j = i
        while (j < json.length && (json[j].isDigit() || json[j] == '.' || json[j] == '-' || json[j] == '+' || json[j] == 'e' || json[j] == 'E')) j++
        return json.substring(i, j).toDoubleOrNull()
    }
}
