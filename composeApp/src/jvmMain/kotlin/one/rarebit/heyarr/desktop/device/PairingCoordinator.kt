package one.rarebit.heyarr.desktop.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import one.rarebit.voidbind.Invite
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome

/**
 * The desktop holder for the join → handshake → confirm → receive → enrol pipeline
 * (the relay **responder**, voidbind-client `DevicePairing`). The desktop analog of
 * heyarr-mobile's `device/PairingCoordinator`, trimmed for desktop: the invite is PASTED
 * (no camera, so no deep-link / same-phone one-tap channel and no foreground service),
 * and there is no biometric gate. Runs on an app-wide [scope] so a wait outlives the
 * enrol sheet leaving composition; the relay is polled up to the session TTL.
 *
 * A blocking relay step is run inside [runInterruptible] on [Dispatchers.IO], so
 * cancelling the coordinator's job (a new invite supersedes the old, or the user backs
 * out) tears the relay wait down at once via [PatientRelayTransport].
 *
 * The human gate between the SAS and the admission is [confirmMatch] / [rejectMatch].
 */
class PairingCoordinator(
    private val scope: CoroutineScope,
    private val steps: () -> PairingSteps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = RELAY_SESSION_TTL_MILLIS,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var job: Job? = null
    private var live: PairingSteps? = null
    private var verdict = CompletableDeferred<Boolean>()

    /** Join [inviteQr]. Any in-flight session is cancelled first; an invalid invite fails fast. */
    fun start(inviteQr: String) {
        val invite = inviteQr.trim()
        val checked = runCatching { Invite.decode(invite) }
        if (checked.isFailure) {
            cancelLive()
            _state.value = PairingState.Failed(
                invite,
                PairingFailure.INVALID,
                checked.exceptionOrNull()?.message ?: "That is not a voidbind pairing invite.",
            )
            return
        }
        cancelLive()
        val deadline = clock() + ttlMillis
        val s = steps()
        live = s
        verdict = CompletableDeferred()
        _state.value = PairingState.Joining(invite, deadline)
        job = scope.launch { run(invite, s, deadline) }
    }

    private suspend fun run(inviteQr: String, s: PairingSteps, deadline: Long) {
        val handshaked = when (val h = runInterruptible(Dispatchers.IO) { s.handshake(inviteQr, deadline) }) {
            is PairingOutcome.Failed -> return fail(inviteQr, h)
            is PairingOutcome.Ready -> h.value
        }
        val sas = handshaked.sas
        _state.value = PairingState.CompareSas(inviteQr, deadline, sas, awaitingAdmission = false)
        val matched = verdict.await()
        if (!matched) {
            return finish(
                PairingState.Failed(
                    inviteQr,
                    PairingFailure.MISMATCH,
                    "Security codes differed — pairing aborted. Nothing was exchanged.",
                ),
            )
        }
        _state.value = PairingState.CompareSas(inviteQr, deadline, sas, awaitingAdmission = true)
        val op = when (val r = runInterruptible(Dispatchers.IO) { s.receive(deadline) }) {
            is PairingOutcome.Failed -> return fail(inviteQr, r)
            is PairingOutcome.Ready -> r.value
        }
        register(s, op)
    }

    private suspend fun register(s: PairingSteps, op: String) {
        _state.value = PairingState.Registering(op)
        val out = runInterruptible(Dispatchers.IO) { s.register(op) }
        _state.value = when (out) {
            is EnrolClient.Outcome.Registered -> PairingState.Enrolled(
                op,
                registered = true,
                registration = "Registered with the node via ${out.via}.",
                needsAdmin = false,
                retriable = false,
            )

            is EnrolClient.Outcome.NeedsAdmin -> PairingState.Enrolled(
                op,
                registered = false,
                registration = "The admission is stored, but the node does not know it yet (${out.reason}). " +
                    "An admin must register it.",
                needsAdmin = true,
                retriable = false,
            )

            is EnrolClient.Outcome.Failed -> PairingState.Enrolled(
                op,
                registered = false,
                registration = "The admission is stored, but registering it with the node failed: ${out.message}",
                needsAdmin = false,
                retriable = true,
            )
        }
    }

    private fun fail(inviteQr: String, f: PairingOutcome.Failed) = finish(
        PairingState.Failed(
            inviteQr,
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
        live = null
        _state.value = terminal
    }

    /** The human saw the SAME code on both screens: wait for Cruciform's admission. */
    fun confirmMatch() {
        val s = _state.value as? PairingState.CompareSas ?: return
        if (s.awaitingAdmission) return
        if (!verdict.isCompleted) verdict.complete(true)
    }

    /** The codes differ — abort; the SAS never authorised anything. */
    fun rejectMatch() {
        _state.value as? PairingState.CompareSas ?: return
        if (!verdict.isCompleted) verdict.complete(false)
    }

    /** `POST /enrol` again for an admission the node has not accepted yet. */
    fun retryRegister() {
        val e = _state.value as? PairingState.Enrolled ?: return
        if (e.registered || !e.retriable) return
        val s = live ?: return
        job = scope.launch { register(s, e.op) }
    }

    /** Abandon whatever is in flight (the user backed out). */
    fun cancel() {
        cancelLive()
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
