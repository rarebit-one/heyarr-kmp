package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.net.ProblemDetail
import java.net.URLEncoder

/**
 * The reads and the management writes behind the **work detail** screen, against the
 * routes heyarr-core actually mounts (`internal/api/resources/resources.go`,
 * `desired.go`):
 *
 * Reads (the `read` floor — a QR session can do all of these):
 * - `GET /works/{id}/assets?limit=200[&cursor]` → the work's files, **joined and
 *   complete** (heyarr-core #429): each `WorkAsset` inlines its edition label/type and
 *   its blob's size + media type, so there is no more `/editions/{id}` + `/blobs/{hash}`
 *   fan-out per asset. An unknown work is a 404, not an empty page.
 * - `GET /desired?work_id=…` → the wants for this work, with §64 acquisition facts.
 *
 * Writes (`write` scope — a read-scoped session gets a `403`, surfaced honestly):
 * - `DELETE /desired/{id}` — cancel a want (physical; a statement of intent, not bytes).
 * - `PATCH /desired/{id} {monitor}` — pause / resume monitoring a want.
 * - `POST /desired/{id}/reconcile` → `202 {job_id}` — "retry": queue a reconciliation.
 * - `POST /desired/{id}/search` → `202` — "search again": queue a candidate search.
 * - `DELETE /assets/{id}` — remove a file from the catalog (logical, ADR-0018: the
 *   blob stays until GC).
 * - `PATCH /works/{id}` — correct a work's title / year / content type (#428). `year:0`
 *   clears the year; an omitted field is left alone (see [WorkPatch]).
 * - `DELETE /works/{id}` — remove the work, its editions, assets and wants (logical,
 *   ADR-0018: no byte is unlinked). A work a followed source still owns answers **409**
 *   naming `DELETE /followed-sources/{id}` as the fix; the message is surfaced verbatim.
 */
class WorkDetailClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** The outcome of a management write — a value the VM/tests read directly. */
    sealed interface Outcome {
        /** The write took (`2xx`); [body] is the response body for callers that re-parse. */
        data class Done(val status: Int, val body: String) : Outcome

        /** A `403` — this credential cannot write; the message is the honest hint. */
        data class ReadOnly(val message: String) : Outcome

        /** A `400`/`404`/`409` the server refused by policy, with its `detail`. */
        data class Refused(val status: Int, val message: String) : Outcome

        /** Any other non-2xx. */
        data class Failed(val status: Int, val message: String) : Outcome
    }

    // ── Reads ────────────────────────────────────────────────────────────────────

    /**
     * The work's files (`GET /works/{id}/assets`, #429), joined and complete: each
     * asset already carries its edition label and its blob's size + media type, so no
     * per-asset `/editions` or `/blobs` reads are made. Pages `next_cursor` to the end.
     * Throws on a non-200 (a 404 is "no such work") so the caller can surface it.
     */
    fun assetsForWork(workId: String): List<WorkAsset> {
        val all = ArrayList<WorkAsset>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = http.get(workAssetsUrl(baseUrl, workId, cursor), credential.asHeader())
            require(resp.status == 200) { "assets: GET /works/$workId/assets failed: HTTP ${resp.status}" }
            all.addAll(WorkDetailJson.parseAssets(resp.body))
            cursor = WorkDetailJson.nextCursor(resp.body)
            pages++
        } while (cursor != null && pages < LibraryClient.MAX_PAGES)
        return all
    }

    /** The wants for [workId], recent first. Throws on a non-200. */
    fun wantsForWork(workId: String): List<Want> {
        val resp = http.get(wantsUrl(baseUrl, workId), credential.asHeader())
        require(resp.status == 200) { "wants: GET /desired failed: HTTP ${resp.status}" }
        return WorkDetailJson.recentFirst(WorkDetailJson.parseWants(resp.body))
    }

    // ── Writes ───────────────────────────────────────────────────────────────────

    /** `DELETE /desired/{id}` — cancel a want. */
    fun cancelWant(wantId: String): Outcome =
        classify(http.delete(wantUrl(baseUrl, wantId), credential.asHeader()), "cancel")

    /** `PATCH /desired/{id} {"monitor": …}` — pause (`false`) or resume (`true`) monitoring. */
    fun setMonitor(wantId: String, monitor: Boolean): Outcome = classify(
        http.patch(wantUrl(baseUrl, wantId), monitorBody(monitor), "application/json", jsonHeaders),
        if (monitor) "resume" else "pause",
    )

    /** `POST /desired/{id}/reconcile` — queue a reconciliation ("retry"). */
    fun reconcile(wantId: String): Outcome =
        classify(http.post(wantUrl(baseUrl, wantId) + "/reconcile", "{}", "application/json", jsonHeaders), "retry")

    /** `POST /desired/{id}/search` — queue a fresh candidate search. */
    fun searchAgain(wantId: String): Outcome =
        classify(http.post(wantUrl(baseUrl, wantId) + "/search", "{}", "application/json", jsonHeaders), "search")

    /** `DELETE /assets/{id}` — remove the catalog row (bytes stay until GC, ADR-0018). */
    fun removeAsset(assetId: String): Outcome =
        classify(http.delete(assetUrl(baseUrl, assetId), credential.asHeader()), "remove")

    /**
     * `PATCH /works/{id}` — correct the work's title / year / content type (#428).
     * On success [Outcome.Done.body] is the corrected `Work` (the caller re-parses via
     * [WorksJson.parseOne]). A 400 (blank title, unknown content type) lands as
     * [Outcome.Refused] with the node's `detail`.
     */
    fun editWork(workId: String, patch: WorkPatch): Outcome =
        classify(http.patch(workUrl(baseUrl, workId), patch.body(), "application/json", jsonHeaders), "edit")

    /**
     * `DELETE /works/{id}` — remove the work, its editions, assets and wants (logical:
     * bytes stay until GC, ADR-0018). A work a followed source still owns answers
     * **409** ("stop following it first — `DELETE /followed-sources/{id}`"); that lands
     * as [Outcome.Refused] with the node's `detail`, surfaced verbatim rather than
     * silently dropping the subscription.
     */
    fun deleteWork(workId: String): Outcome =
        classify(http.delete(workUrl(baseUrl, workId), credential.asHeader()), "delete")

    private val jsonHeaders: Map<String, String>
        get() = credential.asHeader() + ("Content-Type" to "application/json")

    private fun classify(resp: one.rarebit.heyarr.core.net.HttpResponse, label: String): Outcome = when (resp.status) {
        in 200..299 -> Outcome.Done(resp.status, resp.body)
        403 -> Outcome.ReadOnly(READ_ONLY_HINT)
        400, 404, 409 -> Outcome.Refused(resp.status, ProblemDetail.message(resp.body, resp.status, label))
        else -> Outcome.Failed(resp.status, ProblemDetail.message(resp.body, resp.status, label))
    }

    companion object {
        /** The same honest read-floor hint the acquire/unfollow paths surface on a `403`. */
        const val READ_ONLY_HINT =
            "This is a read-only session. Authorize this device to manage your library, then try again."

        private fun api(baseUrl: String) = baseUrl.trimEnd('/') + "/api/v1"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** `GET /works/{id}/assets?limit=200[&cursor]` — the joined per-work file list (#429). */
        fun workAssetsUrl(baseUrl: String, workId: String, cursor: String? = null): String {
            val base = workUrl(baseUrl, workId) + "/assets?limit=" + LibraryClient.PAGE_LIMIT
            return if (cursor.isNullOrBlank()) base else base + "&cursor=" + enc(cursor)
        }

        /** `/works/{id}` — the PATCH/DELETE target and the assets-route base (#428). */
        fun workUrl(baseUrl: String, id: String): String = api(baseUrl) + "/works/" + enc(id)

        fun assetUrl(baseUrl: String, id: String): String = api(baseUrl) + "/assets/" + enc(id)
        fun wantsUrl(baseUrl: String, workId: String): String =
            api(baseUrl) + "/desired?work_id=" + enc(workId) + "&limit=" + LibraryClient.PAGE_LIMIT
        fun wantUrl(baseUrl: String, id: String): String = api(baseUrl) + "/desired/" + enc(id)

        /** heyarr's `UpdateDesiredRequest`, one field: `{"monitor":true|false}`. */
        fun monitorBody(monitor: Boolean): String = "{\"monitor\":$monitor}"
    }
}
