package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport

/**
 * A caller's own authority, as `GET /api/v1/session` reports it (heyarr-core
 * `internal/api/resources/session.go` `SessionView`, ADR-0061).
 *
 * This is the one read a client makes to decide whether to show follow-management
 * UI as active, and — when it may not yet — which device key an operator must
 * authorise. [canWrite] is the field the UI wires on: whether Follow / Unfollow will
 * succeed for this caller as it stands.
 */
data class SessionAuthority(
    /** How the caller authenticated: `session` (QR web-login), `device`, `service`, `anonymous`. */
    val kind: String,
    /** The identity the caller acts as (`ed25519:<hex>`), or empty for anonymous. */
    val principalId: String,
    /**
     * The approving/authenticated device key — the value an operator names to grant
     * management (`ed25519:<hex>`). Empty for a service/anonymous caller.
     */
    val deviceKey: String,
    /** The effective scopes this credential carries. */
    val scopes: List<String>,
    /** Whether Follow / Unfollow will succeed as-is (Scopes admits write). */
    val canWrite: Boolean,
    /**
     * True when this is a session whose approving device holds a follow-management
     * grant — the reason a session can, unusually, write. False for a read-only
     * session.
     */
    val managementAuthorized: Boolean,
) {
    /** A read-only QR/web-login session: it can browse and list, but not follow yet. */
    val isReadOnlySession: Boolean get() = kind == "session" && !canWrite

    /** An enrolled device presenting its cert (`Authorization: Device …`). */
    val isDevice: Boolean get() = kind == "device"

    /**
     * Read-only however we authenticated. On current heyarr-core `main` an enrolled
     * device is read-scoped unconditionally; once an admin-authorised device earns
     * write (heyarr-core #417 / ADR-0065) `can_write` flips and this follows it.
     */
    val isReadOnly: Boolean get() = !canWrite
}

/**
 * Reads the caller's authority so the Follow UI can be honest about the read floor
 * (ADR-0061). A QR/web-login session is minted read-scoped, so Follow / Unfollow
 * `403`; the interim path to write is a **follow-management grant** an operator issues
 * for a trusted device (`POST /session/management-grants {device_key}`, an *admin*
 * action — not something this read-only client can self-issue). So the client's job is
 * the three reads the server doc names: learn the scope, surface the [deviceKey][SessionAuthority.deviceKey]
 * to authorise, and re-check after — [refresh] is that re-check.
 */
class SessionClient(private val http: HttpTransport, private val baseUrl: String, private val credential: Credential) {
    /**
     * Fetch the caller's authority. Returns null when the session endpoint cannot be
     * read (a transport failure, or an unexpected status) — the caller treats an
     * unknown authority as read-only, the safe floor, rather than guessing write.
     */
    fun authority(): SessionAuthority? {
        val resp = http.get(sessionUrl(baseUrl), credential.asHeader())
        if (resp.status != 200) return null
        return SessionJson.parse(resp.body)
    }

    companion object {
        /** `GET /api/v1/session` — the caller's-own-authority route. */
        fun sessionUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/session"
    }
}
