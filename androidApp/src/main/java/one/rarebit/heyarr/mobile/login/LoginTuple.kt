package one.rarebit.heyarr.mobile.login

import one.rarebit.voidwhichbinds.LoginQr

/**
 * The Voidbind web-login tuple `void-which-binds:login?rp=<origin>&id=<login-id>`, delegated
 * to voidbind-client's [LoginQr] — the canonical Kotlin port of voidbind-go's
 * `weblogin.EncodeLogin`/`DecodeLogin` (keys sorted, `id` before `rp`; decode
 * tolerant of key order and percent/plus encoding).
 *
 * This thin façade keeps the app's call sites (`LoginTuple.encode/decode`, the
 * `Parsed` value type) stable now that the wire brain is the published artifact
 * rather than a scaffold copy. It carries ONLY the RP origin and the login id: heyarr's
 * login channel is **QR** — the app renders this as a QR, the authenticator scans it
 * and pulls the full signed challenge from the RP by id. Nothing secret rides here.
 */
object LoginTuple {

    const val SCHEME = LoginQr.SCHEME

    data class Parsed(val rp: String, val id: String)

    /** Render the tuple exactly as voidbind-go does (`LoginQr.encode`). */
    fun encode(rp: String, id: String): String = LoginQr.encode(rp, id)

    /** Parse the tuple back into (rp, id) (`LoginQr.decode`). Throws on a wrong scheme / missing field. */
    fun decode(uri: String): Parsed = LoginQr.decode(uri).let { Parsed(it.rp, it.id) }
}
