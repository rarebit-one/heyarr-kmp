package one.rarebit.heyarr.desktop.device

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.auth.PossessionProof
import one.rarebit.voidbind.flow.DevicePairing
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome
import one.rarebit.voidbind.net.HttpTransport as VoidbindHttpTransport
import one.rarebit.voidbind.net.JdkHttpTransport as VoidbindJdkHttpTransport

/**
 * The real [PairingSteps]: voidbind-client's `DevicePairing` (this desktop as the relay
 * responder) over a [PatientRelayTransport] bound to the session's deadline, the
 * admission persisted through the [DesktopDeviceKeyring], and `POST /enrol` with the ops
 * this device knows ([EnrolClient]). The desktop analog of heyarr-mobile's
 * `device/DevicePairingSteps` — same voidbind flow, no biometric gate (the desktop
 * signer is software and prompts for nothing).
 *
 * Blocking; the [PairingCoordinator] runs each step inside `runInterruptible` so a
 * cancel tears a relay wait down. Only [register] signs (the possession proof).
 *
 * @param relayTransport the raw voidbind transport for the relay; defaults to the JDK one.
 */
class DevicePairingSteps(
    private val keyring: DesktopDeviceKeyring,
    private val nodeTransport: HttpTransport,
    private val baseUrl: () -> String,
    private val deviceName: () -> String,
    private val credential: () -> Credential?,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val relayTransport: VoidbindHttpTransport = VoidbindJdkHttpTransport(),
) : PairingSteps {

    private var pairing: DevicePairing? = null
    private var handshake: DevicePairing.Handshake? = null

    override fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<PairingSteps.Handshaked> {
        val identity = keyring.identity()
        val p = DevicePairing(
            PatientRelayTransport(relayTransport, deadlineMillis, clockMillis),
            identity,
            clock = { clockMillis() / 1000 },
        )
        pairing = p
        return when (val o = p.beginCatching(inviteQr)) {
            is PairingOutcome.Failed -> o

            is PairingOutcome.Ready -> {
                handshake = o.value
                PairingOutcome.Ready(
                    PairingSteps.Handshaked(
                        sas = o.value.sas,
                        deviceId = KeyRef.ed25519(identity.signPublicKey).render(),
                    ),
                )
            }
        }
    }

    override fun receive(deadlineMillis: Long): PairingOutcome<String> {
        val p = pairing ?: return notReady()
        val h = handshake ?: return notReady()
        return when (val o = p.confirmCatching(h)) {
            is PairingOutcome.Failed -> o

            is PairingOutcome.Ready -> try {
                keyring.saveAdmission(o.value)
                PairingOutcome.Ready(o.value.op)
            } catch (e: Exception) {
                PairingOutcome.Failed(PairingFailureKind.PROTOCOL, "The admission could not be stored: ${e.message}", "")
            }
        }
    }

    override fun register(op: String): EnrolClient.Outcome {
        val proof = try {
            PossessionProof.mint(op, keyring.identity().asSigner(), clockMillis() / 1000)
        } catch (e: Exception) {
            return EnrolClient.Outcome.Failed("could not sign with the device key (${e.message})")
        }
        val outcome = EnrolClient(nodeTransport, baseUrl()).register(
            op,
            proof,
            deviceName(),
            credential(),
            ops = MembershipOps.presentable(keyring.knownOps(), op),
        )
        // Persist the identity's recovery encryption PUBLIC key when `/enrol` delivered one, so a
        // new vault space can be wrapped for recovery from enrolment onward (mirrors heyarr-mobile).
        (outcome as? EnrolClient.Outcome.Registered)?.recoveryEncryptionKey
            ?.let { runCatching { keyring.saveRecoveryRecipient(it) } }
        return outcome
    }

    private fun notReady(): PairingOutcome.Failed =
        PairingOutcome.Failed(PairingFailureKind.PROTOCOL, "The pairing handshake has not run yet — start again.", "")
}
