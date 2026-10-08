package one.rarebit.heyarr.vault

import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption

/**
 * How a client opens an encrypted space over the API (heyarr-core `spaceopen.Open`, ADR-0049,
 * ADR-0103): fetch the wrapped copies and the space's current key epoch, pick the copy sealed for
 * THIS device, unwrap it, and unroll the key history so content sealed under every earlier key
 * stays readable. Every opener goes through here — the desktop's two custody paths
 * (`VaultCustody`, the daemon's `GoStoreCustody`), the android app's `SpaceSession` and
 * [VaultObjects] — so none can open a rotated space with only its newest key.
 *
 * The checks are [SpaceKeyring.open]'s: a copy at an epoch other than the space's is refused as
 * superseded, history rows newer than the epoch the keys described are dropped (a rotation can
 * land between the two reads), and an incomplete or tampered chain is refused.
 */
object SpaceOpen {
    /**
     * The keyring of [spaceId] for [recipient] (`x25519:<hex>`), or null when the space holds no
     * copy for it (the ADR-0049 gate, reached before any content is fetched). [unwrap] opens this
     * device's copy with its X25519 key; [openPrev] opens one history row (the seam tests fake).
     * Throws when the copy is superseded or the history does not unroll.
     */
    fun open(
        keys: SpaceKeySource,
        spaceId: String,
        recipient: String,
        openPrev: (sealing: ByteArray, sealed: ByteArray) -> ByteArray = VoidbindEncryption::openSpaceKey,
        unwrap: (ByteArray) -> ByteArray,
    ): SpaceKeyring? {
        val list = keys.spaceKeys(spaceId)
        val own = list.wrapped.firstOrNull { it.recipient == recipient } ?: return null
        val current = unwrap(own.wrapped)
        // A space at epoch 0 has no history and is not asked (Go `spaceopen.History`).
        val history = if (list.keyEpoch == 0) emptyList() else keys.keyHistory(spaceId)
        return SpaceKeyring.open(current, own.epoch, list.keyEpoch, history, openPrev)
    }

    /**
     * The ring a WRITE must seal under, given the space's [currentEpoch] as just read from the
     * node: [ring] when it is still current, else a fresh one from [reopen]. A rotation brings no
     * blob a writer would fail to decrypt, so without this a write after a rotation would go out
     * under the retired key — readable by every recipient that rotation revoked. When the space
     * cannot be reopened at (or past) [currentEpoch], [StaleKeyException]: never write under a
     * superseded key. A null [currentEpoch] (a peer that cannot tell) keeps [ring].
     *
     * The one rule every writer follows: [VaultObjects.put], the desktop sync engine and the
     * android `SpaceSession`.
     */
    fun currentForWrite(
        currentEpoch: Int?,
        ring: SpaceKeyring,
        spaceId: String,
        reopen: () -> SpaceKeyring?,
    ): SpaceKeyring {
        if (currentEpoch == null || currentEpoch == ring.epoch) return ring
        val fresh = runCatching { reopen() }.getOrNull()
        if (fresh == null || fresh.epoch < currentEpoch) {
            throw StaleKeyException(
                "space $spaceId rotated to key epoch $currentEpoch but this device could not open it " +
                    "(holding epoch ${ring.epoch}); refusing to write under a superseded key",
            )
        }
        return fresh
    }

    /** A write refused because the space rotated and this device cannot get onto the new key. */
    class StaleKeyException(message: String) : IllegalStateException(message)
}
