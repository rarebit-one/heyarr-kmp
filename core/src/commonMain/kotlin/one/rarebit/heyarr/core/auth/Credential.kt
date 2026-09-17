package one.rarebit.heyarr.core.auth

import one.rarebit.voidbind.auth.DeviceCredential

/**
 * The credential a first-party client presents to heyarr on every `/api/v1` call.
 *
 * heyarr accepts two credential shapes (client contract, ADR-0048):
 *
 *  - [Device] — the **primary** credential a first-party client carries once it is an
 *    enrolled device: a user-signed enrolment cert plus a fresh possession proof,
 *    presented under heyarr's own `Device` auth scheme:
 *    `Authorization: Device <cert>~<proof>` (the halves joined by the enrolment
 *    separator `~`). It authenticates **offline** — the server verifies the cert
 *    against a pinned key and checks the possession proof; no token round-trip.
 *    Producing the proof needs the device private key, which lives **non-exportable**
 *    in the platform key store (Android StrongBox / a desktop software key / iOS
 *    Secure Enclave) and signs in-enclave — voidbind-client's [DeviceCredential] owns
 *    the wire format (`~` join, `Device ` scheme, possession proof). Formatting the
 *    header from an already-obtained cert+proof is pure; obtaining the proof is
 *    platform-gated.
 *
 *  - A Bearer token, in two flavours that render the same `Authorization: Bearer <t>`:
 *    [Session] — the **bootstrap** credential from a QR web-login (a short-lived
 *    session token minted by the weblogin broker, how a brand-new install reaches the
 *    library before/without enrolling) — and [Bearer] — an opaque long-lived token
 *    (ADR-0011's `heyarr_<id>_<secret>`, e.g. the one a desktop Settings screen pastes).
 *
 * Unified across the desktop and android clients (heyarr-kmp Gate B): desktop uses
 * [Bearer]; the android client uses [Session] and [Device]; all now share this one type.
 */
sealed interface Credential {

    /** The `Authorization` header value to send. */
    fun headerValue(): String

    /** Convenience: the single-entry header map to merge into a request. */
    fun asHeader(): Map<String, String> = mapOf(HEADER to headerValue())

    /** Bootstrap: a short-lived Bearer session token from a QR web-login. */
    data class Session(val token: String) : Credential {
        override fun headerValue() = "Bearer $token"
    }

    /** Opaque long-lived bearer token: `heyarr_<id>_<secret>` (ADR-0011) or a session token. */
    data class Bearer(val token: String) : Credential {
        override fun headerValue() = "Bearer $token"
    }

    /** Primary: an enrolled device's cert + possession proof under the `Device` scheme. */
    data class Device(val cert: String, val proof: String) : Credential {
        override fun headerValue() = DeviceCredential.headerValue(cert, proof)
    }

    /**
     * No credential at all. On a trusted network heyarr serves a request that carries no
     * `Authorization` header as an anonymous **guest** principal (caps browse/play/subtitle),
     * minted as a short-lived lease (heyarr-core Phase 1). So the desktop client's default,
     * before any enrolment, is [Guest]: it sends no header and gets a browse+play session.
     * Everything that writes desired state or reads encrypted personal state needs the
     * "Sign in to save" upgrade to one of the credentialled shapes above.
     */
    data object Guest : Credential {
        // A guest presents nothing; there is no header value to send.
        override fun headerValue() = ""

        // The whole point of a guest request: NO Authorization header, so heyarr's
        // trusted-network guest path applies rather than a credential check.
        override fun asHeader(): Map<String, String> = emptyMap()
    }

    /**
     * A credential whose headers are minted FRESH on every request. The primary
     * [Device] credential carries a short-lived possession proof (it expires in
     * ~2 min), and a long-running background client (the headless vault-sync
     * daemon) makes many requests over a pass that can outlast one proof — so it
     * needs to re-mint per call rather than hold a stale header. [headers] is
     * invoked for each request and returns the full header map to send
     * (`Authorization` plus, on first contact, `Voidbind-Membership`). It is the
     * seam by which the daemon injects `Authorization: Device …` freshly obtained
     * from its device store, without this module owning the proof-minting.
     */
    class Dynamic(private val headers: () -> Map<String, String>) : Credential {
        override fun headerValue(): String = headers()[HEADER] ?: ""
        override fun asHeader(): Map<String, String> = headers()
    }

    companion object {
        const val HEADER = "Authorization"
    }
}
