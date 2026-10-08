package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.desktop.vault.OpenedVault
import one.rarebit.heyarr.vault.SpaceOpen
import one.rarebit.heyarr.vault.VaultKeys
import one.rarebit.heyarr.vault.gostore.GoDeviceStore
import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption

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
    /**
     * Open [spaceId]: match this device's recipient in the wrapped-key list, unwrap with the enc
     * seed and unroll the space's key history ([SpaceOpen], ADR-0103).
     */
    fun open(spaceId: String): OpenedVault {
        val mine = store.encKeyRef()
        val ring = SpaceOpen.open(keys, spaceId, mine) { VoidbindEncryption.unwrap(it, store.encSeed()) }
            ?: error("vault $spaceId has no wrapped key for this device ($mine) — not a recipient yet")
        return OpenedVault(spaceId, ring)
    }
}
