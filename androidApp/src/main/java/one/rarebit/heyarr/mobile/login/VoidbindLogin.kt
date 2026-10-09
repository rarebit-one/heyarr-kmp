package one.rarebit.heyarr.mobile.login

/**
 * The app's Voidbind QR-login seam. heyarr's login channel is **QR** (plan
 * DECISIONS LOG: "heyarr login channel = QR"). The app is the *initiator* — the
 * relying party (RP): it asks heyarr's weblogin broker to start a login, renders the
 * returned `void-which-binds:login?rp=&id=` tuple as a QR code for the user's authenticator
 * to scan, and polls until the broker reports the login approved, receiving a
 * short-lived **Bearer** session token (bootstrap credential — see
 * `auth/Credential.Session`).
 *
 * The wire brain is voidbind-client's `WebLoginClient` (published from voidbind-kmp;
 * byte-identical to voidbind-go's `weblogin` handler). [QrLoginClient] is the app's
 * state machine over its `createLogin`/`poll` calls; the device-side approval half
 * (scan → hardware-gated sign → approve) belongs to the authenticator app, not this
 * consumption client.
 */
interface VoidbindLogin {

    /** The outcome of a QR login attempt. */
    sealed interface Result {
        /** Approved: a short-lived RP Bearer session token (the bootstrap credential). */
        data class Approved(val sessionToken: String, val user: String?) : Result

        /** The user declined on the authenticator, or the challenge expired. */
        data class Denied(val reason: String) : Result

        /** A transport/protocol failure. */
        data class Failed(val error: String) : Result
    }

    /**
     * A started login: the RP login id plus the tuple to render as a QR code. The
     * app draws [qrTuple] as a QR for the authenticator to scan.
     */
    data class Pending(val loginId: String, val tuple: LoginTuple.Parsed, val qrTuple: String)

    /**
     * Begin a QR login: ask the RP to create a login and return the pending handle
     * whose [Pending.qrTuple] the UI renders as a scannable QR code.
     */
    fun begin(): Pending

    /**
     * Wait for the authenticator's decision by polling the RP, up to [timeoutMs].
     * Returns [Result.Approved] with a Bearer session token once approved.
     */
    fun awaitApproval(pending: Pending, timeoutMs: Long = 120_000L): Result
}
