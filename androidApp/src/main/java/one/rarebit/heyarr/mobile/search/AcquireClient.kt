package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.net.ProblemDetail

/**
 * The two acquisition intents behind a search result, and where each one goes on the
 * wire. This is the UI counterpart of heyarr's MCP acquisition verbs.
 *
 * - **[getOnce]** — a **one-off** acquisition: "get me this, once." Wire target
 *   **exists today**: `POST /api/v1/desired` with `monitor:false` (heyarr-core
 *   `internal/api/resources/desired.go` `createDesired` → `WantContentRequest`; the
 *   REST form of the MCP `want_content` verb). A one-off is exactly `monitor:false`:
 *   want it, acquire it, then stop looking (§60 keeps monitoring first-class, so the
 *   one-off is the deliberate opt-out).
 *
 * - **[follow]** — an **ongoing subscription**: "follow this source and keep
 *   archiving whatever it emits." Wire target is now **live** (M12 Slice 5):
 *   `POST /api/v1/followed-sources` with a `FollowSourceRequest` body ⇒ `201`
 *   `FollowedSource` (+ `Location`) — heyarr-core `internal/api/resources/followed.go`
 *   `FollowSource`/`createFollowedSource`.
 *
 *   **Phase-1 reality:** a followed source needs a *feed identity* the server can
 *   poll — a `tvdb_id` or a TVDB `url` — and only `tv_series` is implemented; anything
 *   else (or a work with no feed identity) is **refused server-side** with a `400`
 *   whose `detail` explains why. A search result carries a `work_id` but no feed
 *   identity, so a bare follow of a search hit is refused today. Per the brief we do
 *   **not pre-filter** — we send the intent and surface the server's `detail` as a
 *   user-visible [Result.Failed], and accept an optional [tvdbId]/[url] so a caller
 *   that has one can follow for real.
 *
 * The distinction is the whole feature: a one-off is a single `DesiredItem`; a follow
 * is a `FollowedSource` that *projects* new `DesiredItem`s forever.
 */
class AcquireClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
    private val qualityProfile: String,
) {

    /** Outcome of an acquire action — kept as a value so the VM/tests read it directly. */
    sealed interface Result {
        /** The one-off want was created (`201`); [desiredId] is the new desired-item id if known. */
        data class Wanted(val desiredId: String?) : Result

        /** The follow was created (`201`/`200`); [sourceId] is the followed-source id if known. */
        data class Following(val sourceId: String?) : Result

        /** The request was rejected; [status] is the HTTP code, [message] a short reason. */
        data class Failed(val status: Int, val message: String) : Result
    }

    private val jsonHeaders: Map<String, String>
        get() = credential.asHeader() + ("Content-Type" to "application/json")

    /**
     * **Get once** — a one-off want against the live `POST /api/v1/desired` route.
     * `monitor:false` is what makes it one-off. Returns [Result.Wanted] on `201`.
     */
    fun getOnce(result: SearchResult, reason: String? = null): Result {
        val resp = http.post(
            url = desiredUrl(baseUrl),
            body = oneOffWantBody(result, qualityProfile, reason),
            contentType = "application/json",
            headers = jsonHeaders,
        )
        return when {
            resp.status == 201 || resp.status == 200 -> Result.Wanted(desiredId = idFromBody(resp.body))
            resp.status == 403 -> Result.Failed(403, READ_ONLY_GET_HINT)
            else -> Result.Failed(resp.status, ProblemDetail.message(resp.body, resp.status, "want"))
        }
    }

    /**
     * **Follow / Subscribe** — an ongoing follow against the **live**
     * `POST /api/v1/followed-sources` route. Returns [Result.Following] on `201`
     * (or `200`). On any other status the server's problem-`detail` is surfaced as
     * [Result.Failed] — which is how the Phase-1 refusals (needs a feed identity /
     * `tv_series` only) reach the user as a message rather than a crash.
     *
     * [tvdbId]/[url] give the source's feed identity when the caller has one (a search
     * result does not); without one the follow is refused loudly by the server, which
     * is the intended, surfaced behaviour.
     */
    fun follow(
        result: SearchResult,
        backfill: String = DEFAULT_BACKFILL,
        reason: String? = null,
        tvdbId: String? = null,
        url: String? = null,
    ): Result {
        val resp = http.post(
            url = followedUrl(baseUrl),
            body = followBody(result, qualityProfile, backfill, reason, tvdbId, url),
            contentType = "application/json",
            headers = jsonHeaders,
        )
        return when {
            resp.status == 201 || resp.status == 200 -> Result.Following(sourceId = idFromBody(resp.body))
            resp.status == 403 -> Result.Failed(403, READ_ONLY_FOLLOW_HINT)
            else -> Result.Failed(resp.status, ProblemDetail.message(resp.body, resp.status, "follow"))
        }
    }

    companion object {
        /** heyarr's `FollowSourceRequest` default backfill — `from_now` (vs `full`). */
        const val DEFAULT_BACKFILL = "from_now"

        /**
         * Both acquire actions are **write** routes. A QR/web-login **session** (and the
         * device bootstrap credential) is minted **read-scoped** — it authenticates on
         * `/api/v1` but heyarr's `RequireScope(write)` returns `403` ("this token does
         * not carry the write scope", heyarr-core `internal/api/http/auth.go`). That is
         * a *scope* gap, not a token/401 gap: only a write-scoped device-cert enrolment
         * unlocks Get-once / Follow. So a `403` is surfaced as an honest, actionable
         * message rather than a raw scope string or a faked success.
         */
        const val READ_ONLY_GET_HINT =
            "This is a read-only session. Authorize this device to manage your library, then try again."
        const val READ_ONLY_FOLLOW_HINT =
            "This is a read-only session. Authorize this device to follow, then try again."

        /** `POST /api/v1/desired` — the live want route (`want_content`). */
        fun desiredUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/desired"

        /** `POST /api/v1/followed-sources` — the live `follow_source` route (M12 Slice 5). */
        fun followedUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/followed-sources"

        /**
         * The one-off want body — heyarr's `WantContentRequest` shape (real):
         * `{work_id, quality_profile, monitor:false, reason?}`. `monitor:false` is the
         * one-off marker.
         */
        fun oneOffWantBody(result: SearchResult, qualityProfile: String, reason: String? = null): String {
            val fields = buildList {
                add("\"work_id\":" + jsonString(result.workId))
                add("\"quality_profile\":" + jsonString(qualityProfile))
                add("\"monitor\":false")
                if (!reason.isNullOrBlank()) add("\"reason\":" + jsonString(reason))
            }
            return "{" + fields.joinToString(",") + "}"
        }

        /**
         * The follow body — heyarr's `FollowSourceRequest` shape (live): the series is
         * named by `work_id` (from search), the profile by name (`quality_profile`),
         * `monitor:true` (following is inherently monitored), and a `backfill` policy
         * (`from_now`/`full`). An optional feed identity — `tvdb_id` or a TVDB `url` —
         * is included when the caller has one (Phase 1 needs it to actually poll).
         *
         * Note the server refuses naming the series by *both* `work_id` and `title`, so
         * only `work_id` is sent here; and `content_type` is never a request field for a
         * followed source (it is always `series` in Phase 1, inferred server-side).
         */
        fun followBody(
            result: SearchResult,
            qualityProfile: String,
            backfill: String = DEFAULT_BACKFILL,
            reason: String? = null,
            tvdbId: String? = null,
            url: String? = null,
        ): String {
            val fields = buildList {
                add("\"work_id\":" + jsonString(result.workId))
                if (!tvdbId.isNullOrBlank()) add("\"tvdb_id\":" + jsonString(tvdbId))
                if (!url.isNullOrBlank()) add("\"url\":" + jsonString(url))
                add("\"quality_profile\":" + jsonString(qualityProfile))
                add("\"monitor\":true")
                add("\"backfill\":" + jsonString(backfill))
                if (!reason.isNullOrBlank()) add("\"reason\":" + jsonString(reason))
            }
            return "{" + fields.joinToString(",") + "}"
        }

        /** Read an `id` (or `desired_id` / `source_id`) from a small JSON response body. */
        private fun idFromBody(body: String): String? = SearchResultsJson.parse("[$body]").firstOrNull()?.workId

        /** Minimal JSON string escaping for the flat bodies this builds. */
        internal fun jsonString(s: String): String {
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
}
