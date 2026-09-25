package one.rarebit.heyarr.desktop.device

import one.rarebit.voidbind.flow.PairingOutcome

/**
 * The relay/node steps of ONE desktop pairing session, behind a seam so the state
 * machine ([PairingCoordinator]) is unit-testable without a relay, a keystore or a node.
 * An instance is stateful and created per session; [receive] resumes the handshake
 * [handshake] ran. Each step blocks for as long as the relay takes (the real
 * [DevicePairingSteps] blocks on `Dispatchers.IO` interruptibly) and honours the relay
 * session's TTL ([deadlineMillis]) — a `TIMEOUT` once it passes, never earlier.
 *
 * The desktop analog of heyarr-mobile's `device/PairingSteps`, minus the same-phone
 * one-tap handoff (there is no Cruciform on a desktop) and the biometric gate.
 */
interface PairingSteps {
    /** The SAS to compare, plus this desktop's device signing key (`ed25519:<hex>`). */
    data class Handshaked(val sas: String, val deviceId: String)

    /** Join the invite and run the commit-before-reveal handshake → the SAS to show. */
    fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<Handshaked>

    /** After the human matched the SAS: receive the sealed admission, verify + persist it → the admitting op. */
    fun receive(deadlineMillis: Long): PairingOutcome<String>

    /** Register the persisted admission with the heyarr node (`POST /enrol` with ops). */
    fun register(op: String): EnrolClient.Outcome
}

/** Why a desktop pairing ended without an admission. */
enum class PairingFailure {
    /** No response from the relay at all (no route, refused, TLS, cleartext-blocked). */
    UNREACHABLE,

    /** The relay answered, but Cruciform never posted its side before the session TTL. */
    TIMEOUT,

    /** The relay refused the request — a stale / already-used session. */
    REJECTED,

    /** The bytes arrived but the protocol did not hold (commitment, cert, envelope). */
    PROTOCOL,

    /** The human said the codes differ — aborted; nothing was exchanged. */
    MISMATCH,

    /** The pasted text was not a joinable `voidbind:pair?` v3 invite. */
    INVALID,
}

/** The state of the desktop's one pairing, observed by the enrol UI. */
sealed interface PairingState {
    data object Idle : PairingState

    /** A session in flight until [deadlineMillis] (wall-clock millis). */
    sealed interface Live : PairingState {
        val inviteQr: String
        val deadlineMillis: Long
    }

    /** Joined; waiting on Cruciform's commit + reveal to derive the SAS. */
    data class Joining(override val inviteQr: String, override val deadlineMillis: Long) : Live

    /**
     * The SAS is up for the human to compare against Cruciform's screen. Before "codes
     * match" [awaitingAdmission] is false; after it, this side waits for Cruciform's
     * sealed admission while the SAS stays on screen.
     */
    data class CompareSas(
        override val inviteQr: String,
        override val deadlineMillis: Long,
        val sas: String,
        val awaitingAdmission: Boolean,
    ) : Live

    /** The admission is persisted; `POST /enrol` is in flight. */
    data class Registering(val op: String) : PairingState

    /**
     * Done: the admission is stored on this desktop. [registered] when the node accepted
     * it; otherwise [registration] says what happened, [needsAdmin] when an operator must
     * register it, and [retriable] when tapping "Register" again could succeed.
     */
    data class Enrolled(
        val op: String,
        val registered: Boolean,
        val registration: String,
        val needsAdmin: Boolean,
        val retriable: Boolean,
    ) : PairingState

    data class Failed(val inviteQr: String?, val kind: PairingFailure, val message: String) : PairingState
}
