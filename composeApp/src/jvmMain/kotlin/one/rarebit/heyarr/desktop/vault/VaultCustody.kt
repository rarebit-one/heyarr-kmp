package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.desktop.device.DesktopDeviceKeyring
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.crypto.VoidbindEncryption
import java.util.UUID

/** An opened vault: the space id and the space key unwrapped for THIS device. */
class OpenedVault(val spaceId: String, val spaceKey: ByteArray)

/**
 * Space-key custody for the desktop vault (W4.3), software tier: the space key is unwrapped with
 * this device's own X25519 key (sealed at rest by [DesktopDeviceKeyring]), so the key never
 * leaves the laptop and the node stays admin-blind. Custody swaps to cruciform later behind this
 * same seam with no engine change — the daemon only ever receives an [OpenedVault].
 *
 * The app self-bootstraps the first device (the decided enrolment): on first run it mints the
 * space key, wraps it for this device and — when enrolment provisioned one — the identity's
 * recovery key, and records the space with [VaultKeys.createSpace]. On every later launch it
 * finds this device's wrapped copy in [VaultKeys.listKeys] and unwraps it. The server enforces
 * enrol-before-wrap (ADR-0049), so a mint succeeds only once this device is voidbind-enrolled;
 * before that [openOrBootstrap] reports "not ready" rather than throwing.
 */
class VaultCustody(
    private val keyring: DesktopDeviceKeyring,
    private val keys: VaultKeys,
    private val newSpaceId: () -> String = { UUID.randomUUID().toString() },
    private val kind: String = DEFAULT_KIND,
) {
    /**
     * Open [spaceId] by finding this device's wrapped copy and unwrapping it with the sealed
     * device X25519 key. Null when this device has no readable copy — not (yet) a recipient, or
     * the list/unwrap failed — which the caller treats as "not ready", never as a reason to mint
     * a second space.
     */
    fun open(spaceId: String): OpenedVault? {
        val identity = keyring.identity()
        val mine = identity.deviceEncId.render()
        val wrapped = runCatching { keys.listKeys(spaceId) }.getOrNull()
            ?.firstOrNull { it.recipient == mine } ?: return null
        val key = runCatching { VoidbindEncryption.unwrap(wrapped.wrapped, identity.encPrivateKey) }.getOrNull()
            ?: return null
        return OpenedVault(spaceId, key)
    }

    /**
     * Mint a new vault space: a fresh space key wrapped for THIS device and, when enrolment
     * provisioned one, the recovery recipient; recorded via [VaultKeys.createSpace]. Throws when
     * the server refuses (e.g. this device is not enrolled — the enrol-before-wrap gate).
     */
    fun bootstrap(): OpenedVault {
        val identity = keyring.identity()
        val key = VoidbindEncryption.newSpaceKey()
        val recipients = ArrayList<WrappedKey>()
        recipients.add(WrappedKey(identity.deviceEncId.render(), VoidbindEncryption.seal(key, identity.encPublicKey)))
        keyring.recoveryRecipient()?.let { ref ->
            val pub = runCatching { KeyRef.parse(ref).bytes }.getOrNull()
            if (pub != null && pub.size == 32) recipients.add(WrappedKey(ref, VoidbindEncryption.seal(key, pub)))
        }
        val id = keys.createSpace(newSpaceId(), kind, recipients)
        return OpenedVault(id, key)
    }

    /**
     * The one entry point the daemon wiring uses. When a space is already configured, OPEN it (and
     * never mint — a configured-but-unreadable space means this device isn't wrapped in yet, so we
     * wait for a re-wrap, not fork state). With no space configured, self-bootstrap a fresh one.
     */
    fun openOrBootstrap(configuredSpaceId: String?): Result {
        if (!configuredSpaceId.isNullOrBlank()) {
            val opened = open(configuredSpaceId)
            return Result(
                opened,
                minted = false,
                error = if (opened == null) "vault $configuredSpaceId is not readable by this device yet" else null,
            )
        }
        return runCatching { bootstrap() }.fold(
            onSuccess = { Result(it, minted = true) },
            onFailure = { Result(null, minted = false, error = it.message ?: "could not create the vault space") },
        )
    }

    /** The outcome of [openOrBootstrap]: the opened vault (null when not ready), whether a space was minted, and why not. */
    class Result(val opened: OpenedVault?, val minted: Boolean, val error: String? = null)

    companion object {
        /**
         * A vault is one person's drive → the `personal` space kind. The server's closed kind set
         * (`personal|family|shared|research`) has no dedicated `vault`/`drive` kind; `personal`
         * is the honest fit for a single-user designated-folder sync.
         */
        const val DEFAULT_KIND = "personal"
    }
}
