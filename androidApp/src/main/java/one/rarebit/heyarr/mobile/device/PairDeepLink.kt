package one.rarebit.heyarr.mobile.device

/**
 * The **same-phone pairing handoff** the Cruciform authenticator fires at us
 * (voidbind-kmp ADR-0006): after its "Add a device" mints a v3 invite it cannot show
 * us as a QR — we share the screen — so it opens our own pair-callback URI instead:
 *
 *     heyarr-mobile://pair?invite=<percent-encoded voidbind:pair?v=3&relay=…&session=…&salt=…&usr=… tuple>
 *
 * The invite is the byte-identical QR tuple, so once decoded it goes through
 * [PairInvite.check] — the library's `Invite.decode` — exactly as a scan or paste
 * would, and then into the same `DevicePairing.begin` join path. The link carries no
 * secret and no result: joining only runs the relay handshake to a SAS; the admission
 * is released by the human confirming that SAS on Cruciform, behind its biometric.
 *
 * Pure Kotlin over the intent's `action` + `dataString` (no `android.net.Uri`, which is
 * unavailable to JVM unit tests). The URI is untrusted input from another app.
 */
sealed interface PairDeepLink {
    /** A well-formed handoff: [inviteQr] is the trimmed invite ready for the join path. */
    data class Invite(val inviteQr: String) : PairDeepLink

    /** Ours (`heyarr-mobile://pair…`) but unusable; [message] says why, for the screen. */
    data class Invalid(val message: String) : PairDeepLink

    /**
     * `heyarr-mobile://pair-done?session=…` — the RETURN leg of the same-phone one-tap
     * enrolment (voidbind-kmp ADR-0008). Cruciform opens it after it has signed and
     * delivered the admission, purely to bring the human back here, on the Device tab,
     * enrolled. It carries the session id only and grants nothing: the admission arrived
     * sealed over the relay and this app's own pipeline is what verified and stored it,
     * so the link is a navigation hint, never evidence that anything succeeded.
     */
    data class Done(
        val session: String?,
        /**
         * `outcome=refused`: Cruciform compared this phone's one-tap report against the
         * relay reveal and they disagreed, so it signed nothing. [reason] is its wording,
         * verbatim. Still a navigation hint, not evidence — the pairing here is failed on
         * the strength of the relay session never producing an admission; this only says
         * so now instead of at the session's expiry.
         */
        val outcome: String? = null,
        val reason: String? = null,
    ) : PairDeepLink {
        val refused: Boolean get() = outcome == OUTCOME_REFUSED
    }

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val SCHEME = "heyarr-mobile"
        const val HOST = "pair"
        const val DONE_HOST = "pair-done"
        const val PARAM = "invite"
        const val PARAM_SESSION = "session"
        const val PARAM_OUTCOME = "outcome"
        const val PARAM_REASON = "reason"
        const val OUTCOME_REFUSED = "refused"

        private const val PREFIX = "$SCHEME://$HOST"
        private const val DONE_PREFIX = "$SCHEME://$DONE_HOST"

        /**
         * Route an incoming intent. Returns null when the intent is not a `heyarr-mobile://pair`
         * VIEW at all (a launcher start, the `://login` foregrounding callback, anything else),
         * so the caller can ignore it; [Invalid] when it is ours but the invite is missing or
         * refused by the library's parser.
         */
        fun route(action: String?, dataString: String?): PairDeepLink? {
            if (action != ACTION_VIEW) return null
            val uri = dataString?.trim() ?: return null
            // `pair-done` first: it shares the `…//pair` prefix, and the invite route
            // below deliberately refuses a trailing `-done`.
            if (uri.startsWith(DONE_PREFIX)) {
                val rest = uri.substring(DONE_PREFIX.length)
                if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '/') return null
                val q = rest.indexOf('?')
                val query = if (q < 0) "" else rest.substring(q + 1)
                fun decoded(key: String): String? = queryParam(query, key)
                    ?.let { runCatching { percentDecode(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
                return Done(decoded(PARAM_SESSION), decoded(PARAM_OUTCOME), decoded(PARAM_REASON))
            }
            if (!uri.startsWith(PREFIX)) return null
            val rest = uri.substring(PREFIX.length)
            // `heyarr-mobile://pair`, `…/pair/`, `…/pair?…` — nothing else (e.g. `pairing`) is ours.
            if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '/') return null
            val q = rest.indexOf('?')
            val query = if (q < 0) "" else rest.substring(q + 1)
            val raw = queryParam(query, PARAM)
                ?: return Invalid(
                    "The link from Cruciform carried no invite. Tap \"Send to heyarr\" again on its Add-a-device screen.",
                )
            val decoded = try {
                percentDecode(raw)
            } catch (e: IllegalArgumentException) {
                return Invalid("The invite in the link is garbled (${e.message}). Send it again from Cruciform.")
            }
            return when (val checked = PairInvite.check(decoded)) {
                is PairInvite.Valid -> Invite(checked.inviteQr)
                is PairInvite.Invalid -> Invalid(checked.message)
            }
        }

        /** First value of [key] in a `k=v&k=v` query, raw (still percent-encoded), or null. */
        private fun queryParam(query: String, key: String): String? {
            if (query.isEmpty()) return null
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                val k = if (eq < 0) pair else pair.substring(0, eq)
                if (k == key) return if (eq < 0) "" else pair.substring(eq + 1)
            }
            return null
        }

        /**
         * RFC 3986 percent-decoding (`%XX` → byte, UTF-8). A `+` is left as-is: the
         * sender encodes a space as `%20` (voidbind-kmp `RpPairHandoff.percentEncode`)
         * and an invite never contains one anyway, so `+` must not be turned into a space.
         */
        internal fun percentDecode(s: String): String {
            val out = ByteArray(s.length)
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    require(i + 2 < s.length) { "truncated percent-escape" }
                    val hi = hexVal(s[i + 1])
                    val lo = hexVal(s[i + 2])
                    out[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                } else {
                    require(c.code < 0x80) { "non-ASCII character in the encoded invite" }
                    out[n++] = c.code.toByte()
                    i++
                }
            }
            return out.copyOf(n).decodeToString()
        }

        private fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("bad percent-escape digit '$c'")
        }
    }
}
