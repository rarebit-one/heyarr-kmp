package one.rarebit.heyarr.core.auth

/**
 * What one liveness probe (`GET /api/v1/works?limit=1`, the heartbeat both apps run) says
 * about the node, read together with the [ClientMode] the probe was sent in. Each app maps
 * this onto its own `Connection` banner state; the rule lives here so the two clients cannot
 * drift on who a refusal blames (heyarr-kmp#185).
 *
 *  - [ONLINE] — the node answered 200.
 *  - [OFFLINE] — no answer (status 0) or any other status: the node, not the credential.
 *  - [CREDENTIAL_REFUSED] — a 401/403 to a request that carried a credential. The node
 *    refused what the client presented, so "sign in again / re-check this device" is honest.
 *  - [GUEST_REFUSED] — a 401/403 to a request that carried NO credential. The node does not
 *    admit this caller as a guest (its source is outside the node's guest trusted networks,
 *    or guest access is off). Nothing was presented, so nothing may be blamed: the honest
 *    next step is to sign in.
 */
enum class ProbeVerdict {
    ONLINE,
    OFFLINE,
    CREDENTIAL_REFUSED,
    GUEST_REFUSED,
    ;

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403

        /** [status] is the probe's HTTP status, or 0 when the transport could not connect at all. */
        fun of(status: Int, mode: ClientMode): ProbeVerdict = when (status) {
            HTTP_OK -> ONLINE

            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                if (mode == ClientMode.GUEST) GUEST_REFUSED else CREDENTIAL_REFUSED

            else -> OFFLINE
        }
    }
}
