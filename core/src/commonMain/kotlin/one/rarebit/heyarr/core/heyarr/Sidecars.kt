package one.rarebit.heyarr.core.heyarr

import one.rarebit.heyarr.core.mcp.JsonWrite
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
