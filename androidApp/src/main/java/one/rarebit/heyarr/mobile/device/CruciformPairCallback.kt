package one.rarebit.heyarr.mobile.device

/**
 * The **same-phone one-tap** report this app fires back at Cruciform (voidbind-kmp
 * ADR-0008), and the URI shape it travels in.
 *
 * Cruciform hands us an invite by `heyarr-mobile://pair?invite=…` when both apps are on
 * one phone (ADR-0006). Once we have joined that invite — our relay commit posted, the
 * SAS derived — we tell Cruciform, over a LOCAL intent the relay cannot touch:
 *
 *     cruciform://pair-joined?session=<relay session>&dev=<our ed25519:… key>&sas=<our SAS>
 *
 * Cruciform compares those against what the relay revealed for the same session and, on
 * a match, asks the human ONE question behind its biometric instead of making them read
 * seven digits off two screens. Nothing here is secret: `dev` is a public key we already
 * committed to on the relay, `sas` is derived from both sides' public keys and the
 * invite salt, and `session` came out of the invite Cruciform sent us. The admission
 * still arrives sealed over the relay; this channel carries no authority at all.
 *
 * **Only for a deep-linked invite.** An invite that was scanned or pasted came from
 * another device, where there is no local channel and the human comparison is the whole
 * point — those keep the SAS, unchanged.
 *
 * Pure Kotlin (no `android.*`) so the URI shape is unit-tested on the JVM.
 */
object CruciformPairCallback {

    const val SCHEME = "cruciform"
    const val HOST = "pair-joined"

    /**
     * The URI to fire at Cruciform for a joined session. [deviceId] is this phone's
     * device signing key as `ed25519:<hex>` — the same rendering the relay reveal
     * carries, so Cruciform's comparison is byte-for-byte on the value it already has.
     */
    fun joinedUri(session: String, deviceId: String, sas: String): String {
        require(session.isNotBlank()) { "no relay session" }
        require(deviceId.isNotBlank()) { "no device key" }
        require(sas.isNotBlank()) { "no security code" }
        return "$SCHEME://$HOST" +
            "?session=${encode(session)}" +
            "&dev=${encode(deviceId)}" +
            "&sas=${encode(sas)}"
    }

    /**
     * RFC 3986 percent-encoding of a query VALUE: the unreserved set `A-Za-z0-9-_.~`
     * survives, everything else becomes uppercase `%XX` over UTF-8. Deliberately not a
     * form encoder — a space must be `%20`, never `+`, so the receiver can decode with
     * any standard percent-decoder. (The same rule voidbind-kmp's `RpPairHandoff` uses
     * for the invite it sends us.)
     */
    internal fun encode(s: String): String {
        val bytes = s.encodeToByteArray()
        val out = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' ||
                ch == '~'
            ) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

/**
 * How the coordinator reaches Cruciform, behind a seam so the state machine stays
 * unit-testable with no Android runtime. Returns **true only if an activity actually
 * took the intent** — a false (Cruciform absent, or an older build with no
 * `pair-joined` filter) means the one-tap path is not available and this phone keeps
 * showing the SAS for the human to compare, exactly as before.
 */
fun interface CruciformAnnouncer {
    fun announceJoined(session: String, deviceId: String, sas: String): Boolean

    companion object {
        /** No local channel: the SAS is shown and compared by the human. */
        val None = CruciformAnnouncer { _, _, _ -> false }
    }
}
