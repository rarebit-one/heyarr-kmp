package one.rarebit.heyarr.core.net

/**
 * An opaque HTTP response: the status code and the raw body string.
 *
 * Shape is copied verbatim from heyarr-mobile's `net.HttpResponse` (and, in turn,
 * voidbind-kmp's `net.HttpResponse`) so that when the shared KMP client is extracted,
 * this desktop transport and its callers move across unchanged.
 */
data class HttpResponse(val status: Int, val body: String)

/**
 * The pluggable HTTP seam — same interface shape as heyarr-mobile's [HttpTransport].
 * `login/`, `library/` … depend only on this interface, so tests inject a fake and the
 * desktop app injects the [JdkHttpTransport] actual (java.net.http, JDK 17). No
 * network in CI.
 *
 * Calls are BLOCKING by deliberate choice (mirrors mobile/voidbind): the UI drives
 * them off the main thread (Dispatchers.IO / Dispatchers.Default).
 */
interface HttpTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    fun post(
        url: String,
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    /** DELETE — defaulted to a 405 so fakes that never delete keep compiling. */
    fun delete(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(405, "")

    /**
     * DELETE with a request body — some endpoints (e.g. the vault's placement unpin,
     * `DELETE /vault/placements` with `{blob_hash, peer_id}`) require one. Defaulted to a
     * 405 like [delete]/[patch] so fakes that never need it keep compiling.
     */
    fun delete(
        url: String,
        body: String?,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = HttpResponse(405, "")

    /**
     * Forget any pooled connection. After the machine changes network — a laptop
     * leaving the LAN for a VPN — a pooled connection to the old network is dead but
     * not closed; every request on it waits for its timeout. A transport that pools
     * drops the pool here; a fake has nothing to drop.
     */
    fun reset() {}

    /** PATCH — defaulted to a 405 for the same reason as [delete]. */
    fun patch(
        url: String,
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = HttpResponse(405, "")
}
