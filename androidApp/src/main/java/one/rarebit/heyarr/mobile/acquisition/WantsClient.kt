package one.rarebit.heyarr.mobile.acquisition

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.mobile.net.ProblemDetail
import one.rarebit.heyarr.mobile.search.AcquireClient
import java.net.URLEncoder

/** One release the indexers offered for a want, as the profile judged it (§63). */
data class Candidate(
    val id: String,
    val provider: String? = null,
    val title: String,
    val accepted: Boolean,
    val score: Int = 0,
    val terminal: Boolean = false,
    val selected: Boolean = false,
    /** Every rule's verdict, in order; the rejecting ones are also under [rejectedBy]. */
    val reasons: List<Reason> = emptyList(),
    val rejectedBy: List<Reason> = emptyList(),
) {
    data class Reason(val rule: String, val section: String?, val result: String?, val detail: String?) {
        val line: String get() = listOfNotNull(rule, detail?.takeIf { it.isNotBlank() }).joinToString(" — ")
    }
}

/** A want's candidate set: the search it came from, which one is chosen, and the releases. */
data class CandidateSet(val searchId: String? = null, val selectedId: String? = null, val candidates: List<Candidate>)

/**
 * The acquisition surface a client needs to *manage* a want (§60, §63): the releases
 * an indexer search produced for one — each accepted or rejected by the quality
 * profile with its reasons — and a manual pick. The cancel / pause / retry /
 * search-again writes stay on `WorkDetailClient`.
 */
class WantsClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    sealed interface SelectOutcome {
        data object Done : SelectOutcome
        data class Refused(val status: Int, val message: String) : SelectOutcome
    }

    /** `GET /desired/{id}/candidates`; a want with no search yet answers an empty set. */
    fun candidates(wantId: String): CandidateSet {
        val resp = http.get(candidatesUrl(baseUrl, wantId), credential.asHeader())
        if (resp.status == 404) return CandidateSet(candidates = emptyList())
        require(resp.status == 200) { "candidates: GET /desired/$wantId/candidates failed: HTTP ${resp.status}" }
        return CandidatesJson.parse(resp.body)
    }

    /** `POST /desired/{id}/select {candidate_id}` — a manual pick; a profile-rejected candidate is refused by name. */
    fun select(wantId: String, candidateId: String): SelectOutcome {
        val resp = http.post(selectUrl(baseUrl, wantId), selectBody(candidateId), "application/json", credential.asHeader() + ("Content-Type" to "application/json"))
        return if (resp.status in 200..299) SelectOutcome.Done
        else SelectOutcome.Refused(resp.status, if (resp.status == 403) READ_ONLY else ProblemDetail.message(resp.body, resp.status, "select"))
    }

    companion object {
        const val READ_ONLY = "This session is read-only — choosing a release needs an authorised device."

        fun candidatesUrl(base: String, id: String) = base.trimEnd('/') + "/api/v1/desired/" + URLEncoder.encode(id, "UTF-8") + "/candidates"
        fun selectUrl(base: String, id: String) = base.trimEnd('/') + "/api/v1/desired/" + URLEncoder.encode(id, "UTF-8") + "/select"
        fun selectBody(candidateId: String) = "{\"candidate_id\":" + AcquireClient.jsonString(candidateId) + "}"
    }
}

object CandidatesJson {
    fun parse(body: String): CandidateSet {
        val root = JsonScan.rootObject(body) ?: return CandidateSet(candidates = emptyList())
        val candidates = JsonScan.objectsOf(body, listOf("candidates")).mapNotNull { obj ->
            val id = JsonScan.stringField(obj, "candidate_id") ?: return@mapNotNull null
            Candidate(
                id = id,
                provider = JsonScan.stringField(obj, "provider"),
                title = JsonScan.stringField(obj, "title") ?: id,
                accepted = JsonScan.boolField(obj, "accepted") ?: false,
                score = JsonScan.intField(obj, "score") ?: 0,
                terminal = JsonScan.boolField(obj, "terminal") ?: false,
                selected = JsonScan.boolField(obj, "selected") ?: false,
                reasons = reasons(obj, "reasons"),
                rejectedBy = reasons(obj, "rejected_by"),
            )
        }
        return CandidateSet(
            searchId = JsonScan.stringField(root, "search_id"),
            selectedId = JsonScan.stringField(root, "selected"),
            candidates = candidates,
        )
    }

    private fun reasons(obj: String, key: String): List<Candidate.Reason> =
        JsonScan.objectsOf(obj, listOf(key)).mapNotNull { r ->
            val rule = JsonScan.stringField(r, "rule") ?: return@mapNotNull null
            Candidate.Reason(rule, JsonScan.stringField(r, "section"), JsonScan.stringField(r, "result"), JsonScan.stringField(r, "detail"))
        }
}
