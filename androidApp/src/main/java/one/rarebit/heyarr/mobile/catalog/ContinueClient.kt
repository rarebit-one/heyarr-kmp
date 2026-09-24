package one.rarebit.heyarr.mobile.catalog

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan

/**
 * One card of the continue rail (heyarr-core ADR-0075 `ContinueEntry`): the newest
 * unfinished session on a work, with the work, edition and asset inlined.
 */
data class ContinueEntry(
    val sessionId: String,
    val state: String,
    val verb: String? = null,
    val progressLocator: String? = null,
    val progressUnit: String? = null,
    val workId: String,
    val workTitle: String,
    val contentType: String? = null,
    val year: Int? = null,
    val artworkPath: String? = null,
    val editionLabel: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val assetId: String,
    val blobHash: String? = null,
    val mime: String? = null,
    val durationSeconds: Double? = null,
) {
    val isPlayable: Boolean get() = !blobHash.isNullOrBlank()

    /** The seconds already consumed, when the position is in seconds. */
    val positionSeconds: Double? get() = if (progressUnit == "seconds") progressLocator?.toDoubleOrNull() else null

    /** 0..1 for the progress bar, when both the position and the duration are known. */
    val fraction: Float? get() {
        val pos = positionSeconds ?: return null
        val dur = durationSeconds?.takeIf { it > 0 } ?: return null
        return (pos / dur).coerceIn(0.0, 1.0).toFloat()
    }

    /** `23:45 / 55:12`, `40%`, or the locator as sent — never a made-up number. */
    val progressLabel: String?
        get() {
            val v = progressLocator?.toDoubleOrNull()
            return when {
                v == null -> progressLocator
                progressUnit == "seconds" || progressUnit == "s" -> clock(v.toLong()) + (durationSeconds?.let { " / " + clock(it.toLong()) } ?: "")
                progressUnit == "percent" || progressUnit == "%" -> "${v.toInt()}%"
                else -> "$progressLocator ${progressUnit ?: ""}".trim()
            }
        }

    private fun clock(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** `S01E02` when both numbers are known, else the edition label. */
    val subtitle: String? get() = when {
        season != null && episode != null -> "S%02dE%02d".format(season, episode)
        else -> editionLabel?.takeIf { it.isNotBlank() }
    }
}

/**
 * `GET /api/v1/consumption/continue` — the rail (ADR-0075). History is refused to a
 * Guest (403) and absent on an older node (404); both are [Outcome.Unavailable], so
 * the Home row simply does not show rather than showing an error.
 */
class ContinueClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    sealed interface Outcome {
        data class Rail(val entries: List<ContinueEntry>) : Outcome
        data class Unavailable(val why: String) : Outcome
    }

    fun rail(limit: Int = DEFAULT_LIMIT): Outcome {
        val resp = http.get(continueUrl(baseUrl, limit), credential.asHeader())
        return when (resp.status) {
            200 -> Outcome.Rail(parse(resp.body))
            403 -> Outcome.Unavailable("history is not available to this session")
            404 -> Outcome.Unavailable("this node has no continue rail")
            else -> throw IllegalStateException("continue: GET /consumption/continue failed: HTTP ${resp.status}")
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 12

        fun continueUrl(baseUrl: String, limit: Int): String =
            baseUrl.trimEnd('/') + "/api/v1/consumption/continue?limit=" + limit

        /** Each entry is read from its own nested slices; one missing its work or asset id is skipped. */
        fun parse(body: String): List<ContinueEntry> = JsonScan.objectsOf(body, listOf("items")).mapNotNull { obj ->
            val session = JsonScan.objectAt(obj, "session") ?: return@mapNotNull null
            val work = JsonScan.objectAt(obj, "work") ?: return@mapNotNull null
            val asset = JsonScan.objectAt(obj, "asset") ?: return@mapNotNull null
            val edition = JsonScan.objectAt(obj, "edition")
            val attrs = edition?.let { JsonScan.objectAt(it, "attributes") }
            val progress = JsonScan.objectAt(session, "progress")
            ContinueEntry(
                sessionId = JsonScan.stringField(session, "id") ?: return@mapNotNull null,
                state = JsonScan.stringField(session, "state") ?: "",
                verb = JsonScan.stringField(session, "verb"),
                progressLocator = progress?.let { JsonScan.stringField(it, "locator") },
                progressUnit = progress?.let { JsonScan.stringField(it, "unit") },
                workId = JsonScan.stringField(work, "id") ?: return@mapNotNull null,
                workTitle = JsonScan.stringField(work, "title") ?: "",
                contentType = JsonScan.stringField(work, "content_type"),
                year = JsonScan.intField(work, "year"),
                artworkPath = JsonScan.objectAt(work, "artwork")?.let { JsonScan.stringField(it, "content_url") },
                editionLabel = edition?.let { JsonScan.stringField(it, "label") },
                season = attrs?.let { JsonScan.intField(it, "season") },
                episode = attrs?.let { JsonScan.intField(it, "episode") },
                assetId = JsonScan.stringField(asset, "asset_id") ?: return@mapNotNull null,
                blobHash = JsonScan.stringField(asset, "blob_hash"),
                mime = JsonScan.stringField(asset, "mime"),
                durationSeconds = JsonScan.doubleField(asset, "duration_seconds"),
            )
        }
    }
}
