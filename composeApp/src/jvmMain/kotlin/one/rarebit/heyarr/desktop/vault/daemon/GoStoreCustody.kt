package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.desktop.vault.OpenedVault
import one.rarebit.heyarr.desktop.vault.VaultKeys
import one.rarebit.voidbind.crypto.VoidbindEncryption

/**
 * Opens a CLI-created vault space for the headless daemon by reusing the voidbind-go device store
 * ([GoDeviceStore]) — the software-tier analog of [one.rarebit.heyarr.desktop.vault.VaultCustody],
 * but reading the Go store's plaintext-hex enc seed instead of the Kotlin device keyring.
 *
 * Unlike [one.rarebit.heyarr.desktop.vault.VaultCustody] it never mints/bootstraps a space: the
 * space already exists (created by the CLI), so custody is a pure OPEN — find this device's wrapped
 * copy in the space's key list and unwrap it with the enc seed. A space with no wrapped copy for
 * this device throws (this device isn't a recipient) rather than forking state.
 */
class GoStoreCustody(private val store: GoDeviceStore, private val keys: VaultKeys) {
    /** Open [spaceId]: match this device's recipient in the wrapped-key list and unwrap with the enc seed. */
    fun open(spaceId: String): OpenedVault {
        val mine = store.encKeyRef()
        val wrapped = keys.listKeys(spaceId).firstOrNull { it.recipient == mine }
            ?: error("vault $spaceId has no wrapped key for this device ($mine) — not a recipient yet")
        val key = VoidbindEncryption.unwrap(wrapped.wrapped, store.encSeed())
        return OpenedVault(spaceId, key)
    }
}
