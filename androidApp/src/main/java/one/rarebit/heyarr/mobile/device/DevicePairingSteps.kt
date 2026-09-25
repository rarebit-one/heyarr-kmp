package one.rarebit.heyarr.mobile.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.auth.PossessionProof
import one.rarebit.voidbind.flow.DevicePairing
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome
import one.rarebit.voidbind.net.HttpTransport as VoidbindHttpTransport

/**
 * The real [PairingSteps]: voidbind-client's `DevicePairing` (this phone as the relay
 * responder) over a [PatientRelayTransport] bound to the session's deadline, the
 * admission persisted through the [DeviceKeyring], and `POST /enrol` with the ops
 * this device knows ([EnrolClient]). Every step blocks on `Dispatchers.IO`
 * **interruptibly**, so cancelling the coordinator's job tears a relay wait down.
 *
 * The keyring is looked up per step, not captured: it is bound to the Activity that
 * hosts the biometric prompt, and the Activity in front may have been recreated
 * between the handshake and the registration. Only [register] signs (the possession
 * proof) and so may prompt; the handshake and the receive never do.
 */
class DevicePairingSteps(
    private val keyring: () -> DeviceKeyring?,
    private val relayTransport: VoidbindHttpTransport,
    private val nodeTransport: HttpTransport,
    private val baseUrl: () -> String,
    private val deviceName: () -> String,
    private val credential: () -> Credential?,
    private val clock: () -> Long = System::currentTimeMillis,
) : PairingSteps {

    private var pairing: DevicePairing? = null
    private var handshake: DevicePairing.Handshake? = null

    private fun noKeys(): PairingOutcome.Failed = PairingOutcome.Failed(
        PairingFailureKind.PROTOCOL,
        "This phone's device keys are not available. Open the Device screen and try again.",
        "",
    )

    override suspend fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<PairingSteps.Handshaked> =
        runInterruptible(Dispatchers.IO) {
            val ring = keyring() ?: return@runInterruptible noKeys()
            val identity = ring.identity()
            val p = DevicePairing(
                PatientRelayTransport(relayTransport, deadlineMillis, clock),
                identity,
                clock = { clock() / 1000 },
            )
            pairing = p
            when (val o = p.beginCatching(inviteQr)) {
                is PairingOutcome.Failed -> o

                is PairingOutcome.Ready -> {
                    handshake = o.value
                    // This phone's device signing key, rendered the way the wire renders
                    // it — the same string the initiator sees in the relay reveal, so its
                    // one-tap comparison (voidbind-kmp ADR-0008) is on identical bytes.
                    PairingOutcome.Ready(
                        PairingSteps.Handshaked(
                            sas = o.value.sas,
                            deviceId = KeyRef.ed25519(identity.signPublicKey).render(),
                        ),
                    )
                }
            }
        }

    override suspend fun receive(deadlineMillis: Long): PairingOutcome<String> = runInterruptible(Dispatchers.IO) {
        val ring = keyring() ?: return@runInterruptible noKeys()
        val p = pairing ?: return@runInterruptible noKeys()
        val h = handshake ?: return@runInterruptible noKeys()
        when (val o = p.confirmCatching(h)) {
            is PairingOutcome.Failed -> o

            is PairingOutcome.Ready -> try {
                ring.saveAdmission(o.value)
                PairingOutcome.Ready(o.value.op)
            } catch (e: Exception) {
                PairingOutcome.Failed(
                    PairingFailureKind.PROTOCOL,
                    "The admission could not be stored: ${e.message}",
                    "",
                )
            }
        }
    }

    override suspend fun register(op: String): EnrolClient.Outcome = runInterruptible(Dispatchers.IO) {
        val ring = keyring() ?: return@runInterruptible EnrolClient.Outcome.Failed("device keys unavailable")
        val proof = try {
            PossessionProof.mint(op, ring.identity().asSigner(), clock() / 1000)
        } catch (e: Exception) {
            return@runInterruptible EnrolClient.Outcome.Failed(
                "could not sign with the device key (${e.message}) — bring the app to the front and register again",
            )
        }
        val outcome = EnrolClient(nodeTransport, baseUrl()).register(
            op,
            proof,
            deviceName(),
            credential(),
            ops = MembershipOps.presentable(ring.knownOps(), op),
        )
        // Option A (issue #41 part 2): the enrolment response carried the identity's
        // recovery encryption PUBLIC key — persist it so new spaces wrap for recovery.
        (outcome as? EnrolClient.Outcome.Registered)?.recoveryEncryptionKey
            ?.let { runCatching { ring.saveRecoveryRecipient(it) } }
        outcome
    }
}
