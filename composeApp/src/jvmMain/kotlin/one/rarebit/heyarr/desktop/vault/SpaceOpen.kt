package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.vault.SpaceKeyring

/**
 * How the desktop opens an encrypted space over the API (heyarr-core `spaceopen.Open`, ADR-0049,
 * ADR-0103): fetch the wrapped copies and the space's current key epoch, pick the copy sealed for
 * THIS device, unwrap it, and unroll the key history so content sealed under every earlier key
 * stays readable. Both custody paths ([VaultCustody], the daemon's `GoStoreCustody`) open here, so
 * neither can open a rotated space with only its newest key.
 */
internal object SpaceOpen {
    /**
     * The keyring of [spaceId] for [recipient], or null when the space holds no copy for it (the
     * ADR-0049 gate, reached before any content is fetched). [unwrap] opens this device's copy
     * with its X25519 key. Throws when the copy is superseded or the history does not unroll.
     */
    fun open(keys: VaultKeys, spaceId: String, recipient: String, unwrap: (ByteArray) -> ByteArray): SpaceKeyring? {
        val list = keys.spaceKeys(spaceId)
        val own = list.wrapped.firstOrNull { it.recipient == recipient } ?: return null
        val current = unwrap(own.wrapped)
        // A space at epoch 0 has no history and is not asked (Go `spaceopen.History`).
        val history = if (list.keyEpoch == 0) emptyList() else keys.keyHistory(spaceId)
        return SpaceKeyring.open(current, own.epoch, list.keyEpoch, history)
    }
}
