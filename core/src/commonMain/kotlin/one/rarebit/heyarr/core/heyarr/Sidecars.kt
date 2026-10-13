package one.rarebit.heyarr.core.heyarr

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan

/**
 * The node's per-work sidecar levers, each a REST POST scoped to one work so "this one film" is
 * never one forgotten flag from "every video on the node":
 *
 * - [EXTRACT_CAPTIONS] lifts captions already embedded in the video's container (ADR-0084).
 * - [FETCH_CAPTIONS] creates a subtitle want per video lacking one in a language; the node's
 *   subtitle provider then fetches each (ADR-0085).
 * - [FETCH_COVER] looks a music or book work's cover and canonical id up from the node's enrich
 *   providers (ADR-0087).
 *
 * All three are idempotent on the node, so asking again only does what is still missing.
 */
enum class SidecarAction(val path: String, val countKey: String, val label: String) {
    EXTRACT_CAPTIONS("/api/v1/subtitles/backfill", "enqueued", "Extract embedded captions"),
    FETCH_CAPTIONS("/api/v1/subtitles/want", "created", "Fetch captions"),
    FETCH_COVER("/api/v1/enrich/backfill", "enqueued", "Fetch cover"),
    ;

    /** The request body; [language] (ISO-639-1) is required for [FETCH_CAPTIONS] and ignored otherwise. */
    fun body(workId: String, language: String? = null): String {
        val fields = linkedMapOf<String, Any?>("work_id" to workId)
        if (this == FETCH_CAPTIONS) fields["language"] = requireNotNull(language) { "a caption fetch needs a language" }
        return JsonWrite.obj(fields)
    }

    /**
     * Whether a client in [mode] is offered this lever. Captions follow the guest gate
     * ([Surface.SUBTITLE] is guest-allowed); a cover lookup rewrites the catalogue, so it waits
     * for enrolment. Either way the node has the last word: a credential without the scope gets
     * its 403, shown in the node's words.
     */
    fun allowedFor(mode: ClientMode): Boolean = when (this) {
        EXTRACT_CAPTIONS, FETCH_CAPTIONS -> GuestGate.allows(mode, Surface.SUBTITLE)
        FETCH_COVER -> mode != ClientMode.GUEST
    }
}

/** The node's answer to a sidecar request: what it queued, or its refusal in its own words. */
sealed interface SidecarReply {
    data class Done(val result: SidecarResult) : SidecarReply

    /** [tool] names the endpoint that refused; [code] is the Problem's stable reason, verbatim, when it sent one. */
    data class Refused(val message: String, val status: Int, val tool: String, val code: String? = null) : SidecarReply
}

/** The one place both clients send a [SidecarAction], so auth and status handling cannot drift. */
class SidecarClient(private val http: HttpTransport, private val baseUrl: String, private val credential: Credential) {
    // commonMain has no java.io.IOException; whatever the platform transport throws is a transport failure.
    @Suppress("TooGenericExceptionCaught")
    fun request(action: SidecarAction, workId: String, language: String? = null): SidecarReply {
        val resp = try {
            http.post(
                "$baseUrl${action.path}",
                action.body(workId, language),
                "application/json",
                credential.asHeader(),
            )
        } catch (e: Exception) {
            throw McpTransportException("heyarr is unreachable: ${e.message ?: e::class.simpleName}", e)
        }
        return reply(action, resp.status, resp.body, language)
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_UNAUTHORIZED = 401

        /**
         * Pure: a status and body into a reply. 401 means the credential itself was not accepted;
         * a 403 is kept as a refusal because its body says which scope is missing.
         */
        fun reply(action: SidecarAction, status: Int, body: String, language: String? = null): SidecarReply =
            when (status) {
                HTTP_OK, HTTP_ACCEPTED -> SidecarReply.Done(SidecarResult.parse(action, body, language))

                HTTP_UNAUTHORIZED -> throw McpTransportException(
                    "heyarr refused the credential (HTTP $status)",
                    null,
                    status,
                )

                else -> SidecarReply.Refused(
                    Problem.message(body, status, action.label),
                    status,
                    tool = "POST ${action.path}",
                    code = Problem.code(body),
                )
            }
    }
}

/** What the node answered: how many items were in scope, and how many jobs or wants it queued. */
data class SidecarResult(
    val action: SidecarAction,
    val candidates: Int,
    val queued: Int,
    val language: String? = null,
) {
    /** One plain sentence for the person who asked, from the node's own counts. */
    val message: String
        get() = when (action) {
            SidecarAction.EXTRACT_CAPTIONS -> when {
                queued > 0 -> "Extracting embedded captions from ${plural(queued, "video")}."
                candidates > 0 -> "Caption extraction is already queued for every video here."
                else -> "No video here is missing captions to extract."
            }

            SidecarAction.FETCH_CAPTIONS -> {
                val lang = language?.uppercase() ?: "the requested"
                when {
                    queued > 0 -> "Fetching $lang captions for ${plural(queued, "video")} from the subtitle provider."
                    candidates > 0 -> "$lang captions are already wanted for every video here."
                    else -> "Every video here already has $lang captions."
                }
            }

            SidecarAction.FETCH_COVER -> when {
                queued > 0 -> "Looking up a cover and canonical id."
                candidates > 0 -> "A cover lookup is already queued for this work."
                else -> "This work already has a cover and a canonical id."
            }
        }

    companion object {
        fun parse(action: SidecarAction, body: String, language: String? = null) = SidecarResult(
            action,
            candidates = JsonScan.intField(body, "candidates") ?: 0,
            queued = JsonScan.intField(body, action.countKey) ?: 0,
            language = language,
        )

        private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
    }
}
