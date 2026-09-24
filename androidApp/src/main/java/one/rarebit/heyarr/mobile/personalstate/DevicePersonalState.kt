package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.voidbind.Membership

/**
 * Builds this device's [PersonalStateCoordinator] for a node + credential, and works out
 * who else a space this phone creates is wrapped for (the other member devices and the
 * identity's recovery key). Split out of `AppViewModel`; the keyring is read per call, so
 * it follows enrolment without being rebuilt.
 */
internal class DevicePersonalState(
    private val transport: HttpTransport,
    private val spaceRegistry: SpaceRegistry,
    private val keyring: () -> DeviceKeyring?,
) {
    /**
     * A [PersonalStateCoordinator] for the
     * given node + credential, or null when this device is not enrolled (no X25519 key
     * to unwrap a space key). Encrypted personal state — playlists, starred, play
     * history, reading positions — decrypts ONLY on this device (Invariant 6). The
     * coordinator is cheap and stateless; build one per use.
     */
    fun coordinator(baseUrl: String, cred: Credential): PersonalStateCoordinator? {
        val ring = keyring()?.takeIf { it.isProvisioned() } ?: return null
        return PersonalStateCoordinator(
            SpaceSession(
                PersonalStateClient(transport, baseUrl, cred),
                KeyringDeviceEncKey(ring),
                additionalRecipients = { memberEncRecipients(ring) + recoveryRecipients(ring) },
            ),
            spaceRegistry,
        )
    }

    /**
     * The X25519 enc keys of the OTHER authorised member devices, so a space this phone
     * creates is wrapped for them too and they can decrypt it (ADR-0049) — the peer half
     * of the gateway acceptance. Derived from the membership this device already holds
     * (each add op carries the device's `denc`); the recovery key is not obtainable on
     * the phone (paper-secret only), so it stays out until it can be provisioned.
     */
    private fun memberEncRecipients(ring: DeviceKeyring): List<ByteArray> {
        val usr = ring.userId() ?: return emptyList()
        val view =
            runCatching { Membership.evaluate(usr, ring.knownOps(), nowSeconds()) }.getOrNull() ?: return emptyList()
        val self = ring.peek()?.deviceEncKey
        return view.members.values
            .map { it.deviceEnc }
            .filter { it.isNotEmpty() && it != self }
            .distinct()
            .mapNotNull { parseX25519Recipient(it) }
    }

    /**
     * The identity's recovery encryption **public** key, if enrolment provisioned one
     * (issue #41 part 2, Option A): a `x25519:<hex>` recipient a new space is also wrapped
     * for, so state survives losing every device. Empty (the honest degraded default)
     * until enrolment carries the key, or on a node/identity that has none.
     */
    private fun recoveryRecipients(ring: DeviceKeyring): List<ByteArray> = ring.recoveryRecipient()
        ?.let { parseX25519Recipient(it) }
        ?.let { listOf(it) }
        ?: emptyList()

    private fun nowSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND
}

private const val MILLIS_PER_SECOND = 1000L
