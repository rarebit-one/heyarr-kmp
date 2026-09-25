package one.rarebit.heyarr.mobile.device

import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.voidbind.crypto.MiniJson

/**
 * Reads the membership ops a heyarr node holds for an identity —
 * `GET {base}/membership/{usr}` → `{"usr":…,"ops":[…]}` (heyarr-core ADR-0068).
 * Public and unauthenticated: an op carries its own authority, the node adds none by
 * serving it. This is how a device that was **removed** by another member while it
 * was offline learns so — the app calls [fetch] after a `401` on a Device request,
 * merges the result into its replica and re-evaluates before deciding whether to
 * retry ([one.rarebit.heyarr.mobile.net.DeviceAuthTransport]).
 *
 * A node without the route (404/405 — pre-ADR-0068) yields `null`: nothing learned,
 * the caller keeps what it has.
 */
class MembershipClient(private val http: HttpTransport, private val baseUrl: String) {
    /** The ops the node holds for [usr]; null when the node has no membership route. */
    fun fetch(usr: String): List<String>? {
        val resp = http.get(url(baseUrl, usr))
        return when (resp.status) {
            200 -> parse(resp.body)
            404, 405 -> null
            else -> throw IllegalStateException("membership: GET ${url(baseUrl, usr)}: HTTP ${resp.status}")
        }
    }

    companion object {
        fun url(baseUrl: String, usr: String) = baseUrl.trimEnd('/') + "/membership/" + usr

        /** The `ops` list out of the response body; a body without one is an empty log. */
        fun parse(body: String): List<String> = (MiniJson.parseObject(body)["ops"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }
}
