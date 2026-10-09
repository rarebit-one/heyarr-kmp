package one.rarebit.heyarr.mobile.device

import one.rarebit.voidwhichbinds.Invite
import one.rarebit.voidwhichbinds.LoginQr

/**
 * The outcome of checking something the user **scanned or pasted** as a pairing invite
 * before it is handed to voidbind-client's `DevicePairing`.
 *
 * The invite is the `void-which-binds:pair?v=4&relay=…&session=…&salt=…&usr=…` tuple that a
 * MEMBER device renders — Cruciform's "Add a device", or `void-which-binds pair-initiate` on
 * the machine holding the identity (ADR-0005: `usr` names the identity, so this phone
 * can judge the initiator's membership before any SAS); this phone joins it as the
 * NEW device. Validation is the library's own
 * [Invite.decode] — this file re-derives nothing about the wire format, it only turns
 * the parser's refusals (and the obvious near-misses: a *login* tuple, a random URL)
 * into a message a person can act on. A camera decodes whatever is in frame, so the
 * near-miss messages matter more here than for the paste path.
 */
sealed interface PairInvite {
    /** A well-formed invite, whitespace-trimmed, ready for `DevicePairing.begin`; [user] is the identity it enrols into. */
    data class Valid(val inviteQr: String, val relay: String, val session: String, val user: String) : PairInvite

    /** Not something this phone can join; [message] says why, for the screen. */
    data class Invalid(val message: String) : PairInvite

    companion object {
        // Gen2 only (void-which-binds-go ADR-0022): a gen1 `voidbind:` tuple is not an invite
        // this build can join, so it falls through to the "not an invite" message below.
        private const val PAIR_PREFIX = "${Invite.SCHEME}:pair?"
        private const val LOGIN_PREFIX = "${LoginQr.SCHEME}:login?"

        fun check(raw: String): PairInvite {
            val text = raw.trim()
            if (text.isEmpty()) {
                return Invalid("Nothing to join yet — scan the invite QR or paste the void-which-binds:pair?… text.")
            }
            if (text.startsWith(LOGIN_PREFIX)) {
                return Invalid(
                    "That is a Void-Which-Binds LOGIN code, not a pairing invite. Enrolment needs the " +
                        "void-which-binds:pair?… invite that `void-which-binds pair-initiate` prints.",
                )
            }
            if (!text.startsWith(PAIR_PREFIX)) {
                return Invalid(
                    "Not a Void-Which-Binds pairing invite (saw \"${peek(text)}\"). Expected " +
                        "void-which-binds:pair?v=4&relay=…&session=…&salt=…&usr=…",
                )
            }
            val parsed = try {
                Invite.decode(text)
            } catch (e: IllegalArgumentException) {
                return Invalid("Malformed pairing invite: ${e.message ?: "unparseable"}.")
            }
            return Valid(inviteQr = text, relay = parsed.relay, session = parsed.session, user = parsed.user)
        }

        /** A short, single-line glimpse of what was scanned — enough to recognise, never the lot. */
        private fun peek(text: String): String {
            val line = text.lineSequence().first()
            return if (line.length <= 40) line else line.take(40) + "…"
        }
    }
}
