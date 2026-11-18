package one.rarebit.heyarr.mobile.device

import one.rarebit.voidwhichbinds.AuthenticationRequiredException

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

        is EnrolUiState.Enrolled, is EnrolUiState.Locked -> OnInvite.REFUSE

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

    /**
     * Where a failed sign-in with the stored admission leaves the Enrol state. The key refusing
     * for want of a recent user authentication — the 1-hour window lapsed and the prompt was
     * dismissed, failed or locked out — is [EnrolUiState.Locked]: the enrolment is intact and
     * one more prompt fixes it (heyarr-kmp#243). Anything else (a key-store fault) is an
     * [EnrolUiState.Error] that still carries the enrolled [info], so nothing reads it as
     * un-enrolled.
     */
    fun afterAdoptFailed(info: DeviceKeyInfo?, error: Throwable): EnrolUiState =
        if (info != null && info.isEnrolled && isLockedKey(error)) {
            EnrolUiState.Locked(info)
        } else {
            EnrolUiState.Error(info, "could not sign with the device key: ${error.message}")
        }

    /**
     * True when [error] is, or was caused by, the key store's refusal to sign without a recent
     * user authentication ([AuthenticationRequiredException], which [BiometricGate.gated]
     * rethrows when the prompt does not succeed), including the transport's
     * [one.rarebit.heyarr.mobile.net.CredentialUnavailableException] wrapping of it.
     */
    fun isLockedKey(error: Throwable?): Boolean = generateSequence(error) { it.cause.takeIf { c -> c !== it } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is AuthenticationRequiredException }

    /**
     * Whether "Try again" on an [EnrolUiState.Error] should sign in with the stored admission
     * again rather than fall back to the resting screen: the phone holds an admission, no
     * pairing is being shown, and it is not already signed in with it. Without this, a phone
     * whose adopt failed fell back to "Enrolled" while still browsing as a guest.
     */
    fun retryAdopts(info: DeviceKeyInfo?, showingPairing: Boolean, signedIn: Boolean): Boolean =
        info?.isEnrolled == true && !showingPairing && !signedIn

    /**
     * What Settings → This device says about this phone (null = the not-enrolled default).
     * An enrolled phone is never described as un-enrolled, whatever state its sign-in is in.
     */
    fun deviceSummary(state: EnrolUiState): String? = when (state) {
        is EnrolUiState.Enrolled -> "This phone is enrolled as a device and signs in with its own key."

        is EnrolUiState.Locked ->
            "This phone is enrolled, but its device key is locked. Unlock it with your fingerprint " +
                "to sign in; until then it browses as a guest."

        is EnrolUiState.Removed -> "This phone was removed from your identity. ${state.message}"

        is EnrolUiState.Error -> if (state.info?.isEnrolled == true) {
            "This phone is enrolled, but couldn't sign in with its key: ${state.message}"
        } else {
            null
        }

        is EnrolUiState.Ready -> "A device key exists; this phone is not enrolled yet."

        is EnrolUiState.Unprovisioned -> "No device key yet — a QR session signs this phone in."

        else -> null
    }

    /**
     * Settings → This device's one button: unlock a [EnrolUiState.Locked] key (heyarr-kmp#243),
     * else "Sign in to save" for a guest, else manage the enrolment.
     */
    fun deviceAction(locked: Boolean, isGuest: Boolean): String = when {
        locked -> UNLOCK_ACTION
        isGuest -> "Sign in to save"
        else -> "Device enrolment"
    }

    private const val MAX_CAUSE_DEPTH = 16
}
