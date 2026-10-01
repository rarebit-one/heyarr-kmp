package one.rarebit.heyarr.mobile.login

import one.rarebit.voidwhichbinds.VoidbindDeepLink

/**
 * Same-phone hand-off to the Cruciform authenticator app: instead of a second phone
 * scanning a QR, the app fires an `ACTION_VIEW` intent carrying the very same
 * `voidbind:` URI the QR encodes, and the authenticator (which registers the
 * `voidbind` scheme) approves it in place. Pure URI construction; the Android intent
 * plumbing is [one.rarebit.heyarr.mobile.device.HandoffLauncher].
 *
 * The login URI is the login tuple verbatim (`voidbind:login?id=…&rp=…`), optionally
 * with a `callback` the authenticator can open to bring this app back to the
 * foreground once it has approved ([CALLBACK_URI], matched by the manifest
 * intent-filter). The RP is still polled for the outcome — the callback is a
 * courtesy, never the channel. The URI shape is voidbind-client's
 * [VoidbindDeepLink] (voidbind-kmp ADR-0003) — this app does not re-derive it.
 */
object VoidbindHandoff {

    /** The URI the authenticator may open after approving, to foreground this app. */
    const val CALLBACK_URI = "heyarr-mobile://login"

    /**
     * Build the `voidbind:login?…` URI to hand off from the tuple the broker returned;
     * appends `callback=` when given. Delegates to [VoidbindDeepLink.loginUriFromTuple],
     * which parses the tuple (refusing a non-login string) and re-renders it canonically.
     */
    fun loginUri(qrTuple: String, callback: String? = CALLBACK_URI): String =
        VoidbindDeepLink.loginUriFromTuple(qrTuple, callback?.takeIf { it.isNotEmpty() })

    /** A pairing invite is handed off verbatim — it already carries relay, session and salt. */
    fun pairUri(inviteQr: String): String {
        require(inviteQr.startsWith("voidbind:pair?")) { "not a voidbind pairing invite" }
        return inviteQr
    }
}
