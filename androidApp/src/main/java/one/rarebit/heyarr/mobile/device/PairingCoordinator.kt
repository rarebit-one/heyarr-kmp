package one.rarebit.heyarr.mobile.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome

/**
 * A pairing this phone had in flight, persisted (in [PendingPairingStore]) the moment
 * it starts and cleared the moment it ends. It exists so a return to the app after a
 * **process death** can say what became of the pairing instead of showing a blank
 * Enrol screen: the library's handshake state (`DevicePairing.Handshake`) cannot be
 * serialised — the relay's slots are write-once, so a used session cannot be re-joined
 * either — which means the honest outcome after a restart is "interrupted, start
 * again in Cruciform", or "expired" once the relay's session TTL has passed.
 */
data class PendingPairing(
    /** The relay session id — the key every pairing is identified by. */
    val session: String,
    /** The `voidbind:pair?…` invite tuple, byte-identical to what was joined. */
    val inviteQr: String,
    /** The invite came from Cruciform on THIS phone (the deep link), not another device. */
    val sameDevice: Boolean,
    /** Wall-clock millis when this phone joined; the relay session expires TTL after. */
    val startedAtMillis: Long,
)

/** Where the pending record lives — SharedPreferences on the phone, memory in tests. */
interface PendingPairingStore {
    var pending: PendingPairing?
}

class InMemoryPendingPairingStore : PendingPairingStore {
    override var pending: PendingPairing? = null
}

/**
 * The three relay/node steps of ONE pairing session, in order, behind a seam so the
 * state machine ([PairingCoordinator]) is unit-testable without a relay or a keystore.
 * An instance is created per session ([PairingCoordinator.steps]) and is stateful:
 * [receive] resumes the handshake [handshake] ran. Each step suspends for as long as
 * the relay takes (the real implementation, [DevicePairingSteps], blocks on
 * `Dispatchers.IO` interruptibly) and MUST honour [deadlineMillis] — the relay
 * session's TTL — resolving to a `TIMEOUT` failure once it passes, never earlier.
 */
interface PairingSteps {
    /**
     * What the handshake produced: the SAS to show, and THIS phone's device signing key
     * as `ed25519:<hex>` — the key the initiator's add op will name, and the value the
     * same-phone one-tap report carries back to Cruciform (voidbind-kmp ADR-0008).
     */
    data class Handshaked(val sas: String, val deviceId: String)

    /** Join the invite and run the commit-before-reveal handshake → the SAS to show. */
    suspend fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<Handshaked>

    /**
     * After the human matched the SAS: wait for the initiator's sealed admission,
     * verify it and PERSIST it. Yields the admitting op (the credential token).
     */
    suspend fun receive(deadlineMillis: Long): PairingOutcome<String>

    /** Register the persisted admission with the heyarr node (`POST /enrol` with ops). */
    suspend fun register(op: String): EnrolClient.Outcome
}

/**
 * Why a pairing ended without an admission. The first four are the library's own
 * classification ([PairingFailureKind]) — kept distinct so the screen can say
 * "the relay was unreachable" apart from "the other side never showed up before the
 * session expired"; the rest are this app's.
 */
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

    /** The app process died mid-pairing; the session cannot be resumed. */
    INTERRUPTED,

    /** A pairing found on restart whose relay session TTL has already passed. */
    EXPIRED,

    /** The invite was not a joinable `voidbind:pair?` v3 tuple. */
    INVALID,
}

/** The state of the app's one pairing, observed by the Enrol screen. */
sealed interface PairingState {
    data object Idle : PairingState

    /** A session in flight: the relay is being waited on until [deadlineMillis]. */
    sealed interface Live : PairingState {
        val session: String
        val inviteQr: String
        val sameDevice: Boolean

        /** When the relay session expires (wall-clock millis) — what the countdown shows. */
        val deadlineMillis: Long
    }

    /** Joined; waiting on Cruciform's commit + reveal to derive the SAS. */
    data class Joining(
        override val session: String,
        override val inviteQr: String,
        override val sameDevice: Boolean,
        override val deadlineMillis: Long,
    ) : Live

    /**
     * The SAS is up for the human to compare. Before "codes match" [awaitingAdmission]
     * is false; after it, this side waits for Cruciform's sealed admission while the
     * SAS stays on screen — the human confirms on Cruciform, with a fingerprint.
     */
    data class CompareSas(
        override val session: String,
        override val inviteQr: String,
        override val sameDevice: Boolean,
        override val deadlineMillis: Long,
        val sas: String,
        val awaitingAdmission: Boolean,
        /**
         * True when Cruciform is on this phone and TOOK our `cruciform://pair-joined`
         * report (voidbind-kmp ADR-0008): the two apps compared the device key and SAS
         * over the local intent channel, so there is nothing for the human to compare
         * here and this side went straight to awaiting the admission. The SAS is still
         * carried — the screen reveals it as a fallback if Cruciform never comes back.
         */
        val handedOff: Boolean = false,
    ) : Live

    /** The admission is persisted; `POST /enrol` is in flight. */
    data class Registering(val session: String, val op: String, val sameDevice: Boolean) : PairingState

    /**
     * Done: the admission (op + ops) is stored on this phone. [registered] when the
     * node accepted it; otherwise [registration] says what happened and [retriable]
     * whether tapping "Register" again could succeed (a transport or signing failure —
     * e.g. the biometric prompt could not show while the app was in the background)
     * as opposed to a node that needs an admin.
     */
    data class Enrolled(
        val session: String,
        val op: String,
        val sameDevice: Boolean,
        val registered: Boolean,
        val registration: String,
        val needsAdmin: Boolean,
        val retriable: Boolean,
    ) : PairingState

    data class Failed(
        val session: String?,
        val inviteQr: String?,
        val sameDevice: Boolean,
        val kind: PairingFailure,
        val message: String,
    ) : PairingState
}

/**
 * The app-scoped holder for the join → handshake → confirm → receive → enrol pipeline
 * (the relay **responder**, voidbind-client `DevicePairing`), keyed by the invite's
 * relay session id. It lives in the `Application` ([one.rarebit.heyarr.mobile.HeyarrApp])
 * and runs on an app-wide [scope], NOT a ViewModel or a composable: the same-phone
 * dance with Cruciform has the user switching apps several times (create the key,
 * compare the code, confirm there), and the relay polls — up to the relay session's
 * TTL — must not die with the Activity, the Enrol screen leaving composition, or the
 * app being backgrounded. A foreground service keeps the process alive meanwhile.
 *
 * Idempotent on the session id: re-firing the same deep link (Android re-delivers the
 * launching intent on a recreation) while that session is live is a no-op, and a
 * different invite supersedes the old one. A pending record ([PendingPairingStore])
 * is written for the life of the session so a restart after a process death reports
 * [PairingFailure.INTERRUPTED] / [PairingFailure.EXPIRED] for it rather than
 * re-joining a write-once relay session that would only be refused.
 */
class PairingCoordinator(
    private val scope: CoroutineScope,
    private val store: PendingPairingStore,
    private val steps: (PendingPairing) -> PairingSteps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = RELAY_SESSION_TTL_MILLIS,
    /**
     * The local channel back to Cruciform for a SAME-PHONE pairing (voidbind-kmp
     * ADR-0008). Fired once, right after the handshake, and only for an invite that
     * arrived by deep link — a scanned or pasted invite came from another device, where
     * the human comparison is the only out-of-band channel there is.
     */
    private val announcer: CruciformAnnouncer = CruciformAnnouncer.None,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var job: Job? = null
    private var live: PairingSteps? = null

    /** The human gate between the SAS and the admission: true = codes match. */
    private var verdict = CompletableDeferred<Boolean>()

    init {
        // A record left behind by a previous process: the session is unrecoverable
        // (see PendingPairing). Say so once, then forget it.
        store.pending?.let { p ->
            store.pending = null
            _state.value = interrupted(p)
        }
    }

    private fun interrupted(p: PendingPairing): PairingState.Failed {
        val expired = clock() - p.startedAtMillis >= ttlMillis
        return PairingState.Failed(
            session = p.session,
            inviteQr = p.inviteQr,
            sameDevice = p.sameDevice,
            kind = if (expired) PairingFailure.EXPIRED else PairingFailure.INTERRUPTED,
            message = if (expired) {
                "The pairing expired: its relay session was older than ${ttlMillis / 60_000} minutes. " +
                    "Start again from \"Add a device\" in Cruciform."
            } else {
                "The pairing was interrupted — the app was closed while it was in flight, and a relay " +
                    "session cannot be resumed. Start again from \"Add a device\" in Cruciform."
            },
        )
    }

    /**
     * Join [inviteQr]. A session already live (or already reported interrupted after a
     * restart) under the same id is left alone; any other in-flight session is
     * cancelled first. [sameDevice] marks Cruciform-on-this-phone for the copy.
     */
    fun start(inviteQr: String, sameDevice: Boolean) {
        val checked = PairInvite.check(inviteQr)
        if (checked !is PairInvite.Valid) {
            cancelLive()
            _state.value =
                PairingState.Failed(
                    null,
                    inviteQr,
                    sameDevice,
                    PairingFailure.INVALID,
                    (checked as PairInvite.Invalid).message,
                )
            return
        }
        val session = checked.session
        when (val current = _state.value) {
            is PairingState.Live -> if (current.session == session && job?.isActive == true) return

            is PairingState.Failed -> if (current.session == session &&
                (current.kind == PairingFailure.INTERRUPTED || current.kind == PairingFailure.EXPIRED)
            ) {
                return
            }

            is PairingState.Registering -> if (current.session == session) return

            is PairingState.Enrolled -> if (current.session == session) return

            PairingState.Idle -> Unit
        }
        cancelLive()
        val pending = PendingPairing(session, checked.inviteQr, sameDevice, clock())
        store.pending = pending
        val deadline = pending.startedAtMillis + ttlMillis
        val s = steps(pending)
        live = s
        verdict = CompletableDeferred()
        _state.value = PairingState.Joining(session, pending.inviteQr, sameDevice, deadline)
        job = scope.launch { run(pending, s, deadline) }
    }

    private suspend fun run(p: PendingPairing, s: PairingSteps, deadline: Long) {
        val handshaked = when (val h = s.handshake(p.inviteQr, deadline)) {
            is PairingOutcome.Failed -> return fail(p, h)
            is PairingOutcome.Ready -> h.value
        }
        val sas = handshaked.sas
        // ADR-0008: on ONE phone the SAS comparison can be made by the two apps over the
        // local intent channel instead of by the human across two screens. Report what we
        // derived; Cruciform checks it against what the relay revealed and refuses on any
        // disagreement. A report that nothing takes (no Cruciform, or an older build)
        // leaves this exactly as it was — the human compares.
        val handedOff = p.sameDevice &&
            handshaked.deviceId.isNotBlank() &&
            announcer.announceJoined(p.session, handshaked.deviceId, sas)
        _state.value = PairingState.CompareSas(
            p.session,
            p.inviteQr,
            p.sameDevice,
            deadline,
            sas,
            awaitingAdmission = handedOff,
            handedOff = handedOff,
        )
        // When the apps compared, there is no human verdict to wait for on this side:
        // the gate that matters is Cruciform's biometric, and nothing reaches this phone
        // until it passes. The SAS stays in the state as the screen's fallback.
        val matched = if (handedOff) true else verdict.await()
        if (!matched) {
            return finish(
                PairingState.Failed(
                    p.session,
                    p.inviteQr,
                    p.sameDevice,
                    PairingFailure.MISMATCH,
                    "Security codes differed — pairing aborted. Nothing was exchanged.",
                ),
            )
        }
        _state.value = PairingState.CompareSas(
            p.session,
            p.inviteQr,
            p.sameDevice,
            deadline,
            sas,
            awaitingAdmission = true,
            handedOff = handedOff,
        )
        val op = when (val r = s.receive(deadline)) {
            is PairingOutcome.Failed -> return fail(p, r)
            is PairingOutcome.Ready -> r.value
        }
        // The admission is on disk now: a restart no longer needs the pending record.
        store.pending = null
        register(p.session, p.sameDevice, s, op)
    }

    private suspend fun register(session: String, sameDevice: Boolean, s: PairingSteps, op: String) {
        _state.value = PairingState.Registering(session, op, sameDevice)
        val out = s.register(op)
        _state.value = when (out) {
            is EnrolClient.Outcome.Registered -> PairingState.Enrolled(
                session,
                op,
                sameDevice,
                registered = true,
                registration = "Registered with the node via ${out.via}.",
                needsAdmin = false,
                retriable = false,
            )

            is EnrolClient.Outcome.NeedsAdmin -> PairingState.Enrolled(
                session,
                op,
                sameDevice,
                registered = false,
                registration = "The admission is stored, but the node does not know it yet (${out.reason}). An admin " +
                    "must register it: POST /api/v1/identities/devices {\"cert\":…,\"name\":…}.",
                needsAdmin = true,
                retriable = false,
            )

            is EnrolClient.Outcome.Failed -> PairingState.Enrolled(
                session,
                op,
                sameDevice,
                registered = false,
                registration = "The admission is stored, but registering it with the node failed: ${out.message}",
                needsAdmin = false,
                retriable = true,
            )
        }
    }

    private fun fail(p: PendingPairing, f: PairingOutcome.Failed) = finish(
        PairingState.Failed(
            p.session,
            p.inviteQr,
            p.sameDevice,
            kind = when (f.kind) {
                PairingFailureKind.UNREACHABLE -> PairingFailure.UNREACHABLE
                PairingFailureKind.TIMEOUT -> PairingFailure.TIMEOUT
                PairingFailureKind.REJECTED -> PairingFailure.REJECTED
                PairingFailureKind.PROTOCOL -> PairingFailure.PROTOCOL
            },
            message = f.message,
        ),
    )

    private fun finish(terminal: PairingState) {
        store.pending = null
        live = null
        _state.value = terminal
    }

    /** The human saw the SAME code on both screens: wait for Cruciform's admission. */
    fun confirmMatch() {
        val s = _state.value as? PairingState.CompareSas ?: return
        if (s.awaitingAdmission) return
        verdict.complete(true)
    }

    /**
     * Cruciform refused this phone's one-tap report for [session] (its
     * `pair-done?outcome=refused` return leg): the keys or the code disagreed with the
     * relay reveal, so no admission will ever arrive. Fail the pairing now, with
     * Cruciform's wording, instead of waiting out the relay session on "Approve in
     * Cruciform". A session that is not the live one is ignored — nothing to fail.
     */
    fun refuse(session: String, reason: String) {
        val current = _state.value
        val live = when (current) {
            is PairingState.Joining -> current
            is PairingState.CompareSas -> current
            else -> return
        }
        if (!live.session.equals(session, ignoreCase = true)) return
        cancelLive()
        finish(
            PairingState.Failed(
                live.session,
                live.inviteQr,
                live.sameDevice,
                PairingFailure.MISMATCH,
                "Cruciform refused the pairing: $reason Nothing was exchanged.",
            ),
        )
    }

    /** The codes differ — abort; the SAS never authorised anything. */
    fun rejectMatch() {
        _state.value as? PairingState.CompareSas ?: return
        verdict.complete(false)
    }

    /** `POST /enrol` again for an admission the node has not accepted yet. */
    fun retryRegister() {
        val e = _state.value as? PairingState.Enrolled ?: return
        if (e.registered || !e.retriable) return
        val s = live ?: return
        job = scope.launch { register(e.session, e.sameDevice, s, e.op) }
    }

    /** Abandon whatever is in flight (the user backed out / forgot the device). */
    fun cancel() {
        cancelLive()
        store.pending = null
        _state.value = PairingState.Idle
    }

    /** Acknowledge a terminal state (failure or enrolled) and return to idle. */
    fun dismiss() {
        when (_state.value) {
            is PairingState.Failed, is PairingState.Enrolled -> {
                live = null
                _state.value = PairingState.Idle
            }

            else -> Unit
        }
    }

    private fun cancelLive() {
        job?.cancel()
        job = null
        live = null
        if (!verdict.isCompleted) verdict.cancel()
    }

    companion object {
        /** How long the Voidbind relay keeps a session — how long Cruciform may take. */
        const val RELAY_SESSION_TTL_MILLIS = 10L * 60 * 1000
    }
}
