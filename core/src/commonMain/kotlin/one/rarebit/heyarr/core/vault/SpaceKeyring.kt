package one.rarebit.heyarr.core.vault

import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption

/**
 * One link of a space's key chain as the peer serves it (`GET /api/v1/spaces/{id}/key-history`,
 * heyarr ADR-0103): the key of epoch [epoch]-1 sealed under the key of [epoch]
 * (void-which-binds `sealSpaceKey`, 72 opaque bytes the peer cannot open).
 */
class KeyHistoryEntry(val epoch: Int, val sealedPrev: ByteArray)

/**
 * The keys of one open encrypted space (heyarr ADR-0103) — the Kotlin twin of heyarr-core's
 * `personalstate/client` keyring (`Unroll`, `Manager.Decrypt`, `vaultread.OpenManifestWithKeys`).
 *
 * A space key has an EPOCH. Rotating to epoch N mints a fresh key and stores a history row that
 * seals key N-1 under key N; nothing already written is re-encrypted. So a device opens a space
 * with its wrap of the CURRENT key plus the history, and [unroll]s back to epoch 0: content sealed
 * before a rotation (or by a writer racing one) stays readable under the key of its epoch.
 *
 * - Reads try every key newest → oldest ([open]); the AEAD refuses a wrong key, so the first key
 *   that opens a blob is the one it was sealed under.
 * - Writes use the current key only ([current]) — never an older one, or a device the rotation
 *   revoked could read them.
 *
 * Immutable: a re-fetch builds a new ring, so a reader holding an old one is never raced. The keys
 * stay in memory on this device (heyarr §40); never persist one or hand it to a peer.
 */
class SpaceKeyring private constructor(
    /** The space's current key epoch (0 until its first rotation). */
    val epoch: Int,
    // keys[0] is the current key at [epoch]; keys[i] is the key of epoch - i.
    private val keys: List<ByteArray>,
) {
    /** The current key: the only one anything new is sealed under. A copy. */
    val current: ByteArray get() = keys[0].copyOf()

    /** How many keys the ring holds (epoch + 1). */
    val size: Int get() = keys.size

    /** Every key, newest (the current key) first. Copies. */
    fun keys(): List<ByteArray> = keys.map { it.copyOf() }

    /** A blob opened by [open], with the key that opened it (a vault file's frames are under the same key). */
    class Opened(val plaintext: ByteArray, val key: ByteArray)

    /**
     * Open [blob] under whichever key on the ring sealed it, trying the current key first and then
     * each earlier one, newest to oldest. When none opens it, [NoKeyOpensException] (carrying the
     * current key's refusal as its cause, the same opaque error a single-key space gave): the
     * caller may hold a stale ring (the space rotated since it was fetched) and can re-fetch once.
     */
    fun open(blob: ByteArray, decrypt: (key: ByteArray, blob: ByteArray) -> ByteArray = ::decryptChange): Opened {
        var first: Throwable? = null
        for (k in keys) {
            val r = runCatching { decrypt(k, blob) }
            r.getOrNull()?.let { return Opened(it, k.copyOf()) }
            if (first == null) first = r.exceptionOrNull()
        }
        throw NoKeyOpensException(epoch, first)
    }

    /** [open]'s plaintext for an encrypted change (`VoidbindEncryption.decryptChange`). */
    fun decryptChange(blob: ByteArray): ByteArray = open(blob).plaintext

    /** Seal a change under the CURRENT key (`VoidbindEncryption.encryptChange`). */
    fun encryptChange(plaintext: ByteArray): ByteArray = VoidbindEncryption.encryptChange(keys[0], plaintext)

    /**
     * No key on the ring opens a blob: the wrong space, a corrupt blob, or (the case worth a retry)
     * a ring fetched before the space rotated, so the blob is under a key newer than [epoch].
     */
    class NoKeyOpensException(val epoch: Int, cause: Throwable?) :
        Exception("no key on the space's keyring (epoch $epoch) opens it", cause)

    /** A key chain this device refuses to open the space with (Go `personalstate/client` Unroll's refusals). */
    open class KeyChainException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * A history row missing, duplicated, or beyond the epoch being opened (Go `ErrIncompleteHistory`).
     * Opening anyway would leave the content under the unreachable keys silently unreadable.
     */
    class IncompleteHistoryException(message: String) : KeyChainException(message)

    companion object {
        /** A space at epoch 0 (never rotated) with [key] as its only key — also a freshly minted space. */
        fun single(key: ByteArray): SpaceKeyring {
            require(key.size == VoidbindEncryption.SPACE_KEY_SIZE) { "a space key is 32 bytes" }
            return SpaceKeyring(0, listOf(key.copyOf()))
        }

        /**
         * Walk a space's key chain from its [current] key, at [epoch], back to epoch 0 (Go
         * `client.Unroll`). Every row 1..epoch must be present exactly once and none beyond epoch,
         * or [IncompleteHistoryException]; a row the chain's key does not open is a
         * [KeyChainException] (the current key is wrong, or the row was tampered with). [history]
         * may come in any order. [openPrev] is the seam for `VoidbindEncryption.openSpaceKey`.
         */
        fun unroll(
            current: ByteArray,
            epoch: Int,
            history: List<KeyHistoryEntry>,
            openPrev: (sealing: ByteArray, sealed: ByteArray) -> ByteArray = VoidbindEncryption::openSpaceKey,
        ): SpaceKeyring {
            require(current.size == VoidbindEncryption.SPACE_KEY_SIZE) { "a space key is 32 bytes" }
            require(epoch >= 0) { "key epoch $epoch is negative" }
            val rows = HashMap<Int, ByteArray>(history.size)
            for (h in history) {
                if (h.epoch < 1 || h.epoch > epoch) incomplete("a row for epoch ${h.epoch}, opening at epoch $epoch")
                if (rows.put(h.epoch, h.sealedPrev) != null) incomplete("two rows for epoch ${h.epoch}")
            }
            val keys = ArrayList<ByteArray>(epoch + 1)
            keys.add(current.copyOf())
            var k = current
            for (e in epoch downTo 1) {
                val row = rows[e] ?: incomplete("no row for epoch $e (opening at epoch $epoch)")
                k = runCatching { openPrev(k, row) }.getOrElse {
                    refuse("the key of epoch $e does not open its history row", it)
                }
                keys.add(k)
            }
            return SpaceKeyring(epoch, keys)
        }

        /**
         * Open a space from this device's unwrapped copy (heyarr-core `spaceopen.Open`): [current]
         * was unwrapped from the copy at [copyEpoch], the space is at [keyEpoch], and [history] is
         * its key chain (empty at epoch 0). A copy at any other epoch than the space's is a
         * [KeyChainException]: the peer drops superseded copies when a rotation lands, so seeing
         * one means the peer and this device disagree about the space, and opening anyway would
         * read the wrong chain.
         *
         * Rows NEWER than [keyEpoch] are dropped, not refused (Go `spaceopen.History`): the keys
         * and the history are two reads, and a rotation committing between them adds row
         * keyEpoch+1 to a history fetched after keys that still describe keyEpoch. The chain back
         * from keyEpoch is still complete; refusing it would make a live space look unopenable.
         */
        fun open(
            current: ByteArray,
            copyEpoch: Int,
            keyEpoch: Int,
            history: List<KeyHistoryEntry>,
            openPrev: (sealing: ByteArray, sealed: ByteArray) -> ByteArray = VoidbindEncryption::openSpaceKey,
        ): SpaceKeyring {
            if (copyEpoch != keyEpoch) {
                throw KeyChainException(
                    "this device's copy of the key seals epoch $copyEpoch but the space is at epoch $keyEpoch; " +
                        "refusing to open with a superseded key (ADR-0103)",
                )
            }
            return unroll(current, keyEpoch, history.filter { it.epoch <= keyEpoch }, openPrev)
        }

        private fun decryptChange(key: ByteArray, blob: ByteArray): ByteArray =
            VoidbindEncryption.decryptChange(key, blob)

        private fun incomplete(why: String): Nothing =
            throw IncompleteHistoryException("the space's key history is incomplete: $why")

        private fun refuse(why: String, cause: Throwable): Nothing = throw KeyChainException(why, cause)
    }
}
