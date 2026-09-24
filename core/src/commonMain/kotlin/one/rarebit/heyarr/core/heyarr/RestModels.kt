package one.rarebit.heyarr.core.heyarr

import one.rarebit.heyarr.core.mcp.Reason
import one.rarebit.heyarr.core.mcp.ReasonJson
import one.rarebit.heyarr.core.net.JsonScan

/**
 * The few REST reads the desktop makes beside the MCP tools, because no tool covers
 * them: the quality profiles a want must name (`GET /api/v1/quality-profiles`), the
 * full list of wants for the library-status index (`GET /api/v1/desired`), and one
 * want's indexer candidates (`GET /api/v1/desired/{id}/candidates`). All three were
 * verified live before being relied on; none is invented.
 */
/**
 * [contentTypes] is which works content type(s) this profile is meant to judge
 * ("movie", "book", …) — empty means unrestricted, usable for any content type.
 * It is metadata for deciding which profiles to OFFER for a given want; nothing
 * in evaluation reads it server-side, so it never changes what a profile accepts.
 */
data class QualityProfile(val id: String, val name: String, val description: String?, val contentTypes: List<String> = emptyList())

object QualityProfileJson {
    fun list(body: String): List<QualityProfile> =
        JsonScan.objectsOf(body, listOf("items", "quality_profiles", "profiles")).mapNotNull { p ->
            val name = JsonScan.stringField(p, "name") ?: return@mapNotNull null
            QualityProfile(
                id = JsonScan.stringField(p, "id") ?: name,
                name = name,
                description = JsonScan.stringField(p, "description"),
                contentTypes = JsonScan.stringArray(p, "content_types"),
            )
        }
}

/**
 * A want as the REST list carries it (`DesiredItem`): unlike the MCP list it names the
 * profile by id and inlines the acquisition state, which is what the library index
 * needs to say In library / Wanted / Missing per work.
 */
data class DesiredItem(
    val id: String,
    val workId: String?,
    val scope: String = "work",
    val editionId: String? = null,
    val qualityProfileId: String?,
    val monitor: Boolean,
    val reason: String?,
    val state: String,
    val phase: String?,
    val content: String?,
    val placement: String?,
    val detail: String?,
    val bytesTotal: Long? = null,
    val bytesDone: Long? = null,
    val managed: Boolean? = null,
    val updatedAt: String?,
) {
    /** Fraction 0f..1f of the in-flight transfer that is done, or null when
     *  there is no transfer or the client has not reported a size yet. */
    val downloadProgress: Float?
        get() = bytesTotal?.takeIf { it > 0 }?.let { total ->
            ((bytesDone ?: 0L).toFloat() / total).coerceIn(0f, 1f)
        }
}

object DesiredItemJson {
    fun list(body: String): List<DesiredItem> =
        JsonScan.objectsOf(body, listOf("items", "desired", "data")).mapNotNull { parse(it) }

    fun parseOne(body: String): DesiredItem? = JsonScan.rootObject(body)?.let { parse(it) }

    fun nextCursor(body: String): String? =
        JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "next_cursor") }?.takeIf { it.isNotBlank() }

    private fun parse(obj: String): DesiredItem? {
        val id = JsonScan.stringField(obj, "id") ?: return null
        val acq = JsonScan.objectAt(obj, "acquisition")
        return DesiredItem(
            id = id,
            workId = JsonScan.stringField(obj, "work_id"),
            scope = JsonScan.stringField(obj, "scope") ?: "work",
            editionId = JsonScan.stringField(obj, "edition_id"),
            qualityProfileId = JsonScan.stringField(obj, "quality_profile_id"),
            monitor = JsonScan.boolField(obj, "monitor") ?: true,
            reason = JsonScan.stringField(obj, "reason"),
            state = acq?.let { JsonScan.stringField(it, "state") } ?: "UNKNOWN",
            phase = acq?.let { JsonScan.stringField(it, "phase") },
            content = acq?.let { JsonScan.stringField(it, "content") },
            placement = acq?.let { JsonScan.stringField(it, "placement") },
            detail = acq?.let { JsonScan.stringField(it, "detail") },
            bytesTotal = acq?.let { JsonScan.longField(it, "bytes_total") },
            bytesDone = acq?.let { JsonScan.longField(it, "bytes_done") },
            managed = acq?.let { JsonScan.boolField(it, "managed") },
            updatedAt = JsonScan.stringField(obj, "updated_at"),
        )
    }
}

/** One indexer candidate for a want, scored — the row the Detail screen's release list shows and `acquire_release` takes. */
data class Candidate(
    val candidateId: String,
    val provider: String?,
    val title: String,
    val accepted: Boolean,
    val score: Int,
    val terminal: Boolean,
    val selected: Boolean,
    val reasons: List<Reason>,
) {
    val rejectedBy: List<Reason> get() = reasons.filter { it.section == "accept" && it.isFailure }
}

data class CandidateList(val desiredItemId: String, val searchId: String?, val candidates: List<Candidate>)

object CandidateJson {
    fun parse(body: String): CandidateList? {
        val root = JsonScan.rootObject(body) ?: return null
        val id = JsonScan.stringField(root, "desired_item_id") ?: return null
        val candidates = JsonScan.objectsOf(root, listOf("candidates", "items")).mapNotNull { c ->
            val cid = JsonScan.stringField(c, "candidate_id") ?: return@mapNotNull null
            Candidate(
                candidateId = cid,
                provider = JsonScan.stringField(c, "provider"),
                title = JsonScan.stringField(c, "title") ?: cid,
                accepted = JsonScan.boolField(c, "accepted") ?: false,
                score = JsonScan.intField(c, "score") ?: 0,
                terminal = JsonScan.boolField(c, "terminal") ?: false,
                selected = JsonScan.boolField(c, "selected") ?: false,
                reasons = ReasonJson.list(c),
            )
        }
        return CandidateList(id, JsonScan.stringField(root, "search_id"), candidates)
    }
}

/** RFC 7807 problem body → a one-line, user-safe message. */
object Problem {
    fun message(body: String, status: Int, what: String): String {
        val root = JsonScan.rootObject(body)
        val detail = root?.let { JsonScan.stringField(it, "detail") }?.takeIf { it.isNotBlank() }
        val title = root?.let { JsonScan.stringField(it, "title") }?.takeIf { it.isNotBlank() }
        return detail ?: title ?: "$what failed: HTTP $status"
    }
}
