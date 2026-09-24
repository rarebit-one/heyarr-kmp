package one.rarebit.heyarr.mobile.device

/**
 * The enrolment flow's automatic steps, as pure decisions so they are unit-tested
 * without a keyring or a relay. The same-phone path (Cruciform's "Add a device" →
 * "Send to heyarr") asks the human for nothing in this app: the invite provisions the
 * key, the key joins the invite, the registered admission signs the phone in. The
 * human's one decision — allowing the device — is taken in Cruciform, behind its
 * biometric. Cross-device pairing (a scanned or pasted invite) still stops at the
 * security-code comparison, which is the boundary against a relay in the middle.
 */
object EnrolAdvance {
    /** What to do with an invite that just arrived by deep link, given the current state. */
    enum class OnInvite {
        /** Keys exist and are unenrolled: join now. */
        JOIN,

        /** No device key yet: park the invite and create the key — the prompt has its reason on screen. */
        PROVISION_THEN_JOIN,

        /** Keys still being read, or a pairing already in flight: park it; it joins when the phone is ready. */
        PARK,

        /** This phone already holds an admission; only the human can choose to forget it. */
        REFUSE,
    }

    fun onInvite(state: EnrolUiState): OnInvite = when (state) {
        is EnrolUiState.Ready -> OnInvite.JOIN

        EnrolUiState.Unprovisioned -> OnInvite.PROVISION_THEN_JOIN

        is EnrolUiState.Error -> if (state.info == null) OnInvite.PROVISION_THEN_JOIN else OnInvite.JOIN

        is EnrolUiState.Enrolled -> OnInvite.REFUSE

        EnrolUiState.Loading, is EnrolUiState.Joining, is EnrolUiState.CompareSas,
        is EnrolUiState.Registering, is EnrolUiState.Removed,
        -> OnInvite.PARK
    }

    /**
     * Whether a finished pairing signs the phone in by itself: only when the node
     * accepted the admission. An admission the node does not know yet (an admin must
     * register it) or could not be registered (retry) stays on screen with its reason.
     */
    fun adoptsOnEnrolled(registered: Boolean, needsAdmin: Boolean, retriable: Boolean): Boolean =
        registered && !needsAdmin && !retriable
}
