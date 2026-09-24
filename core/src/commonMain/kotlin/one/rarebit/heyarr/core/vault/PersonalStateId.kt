package one.rarebit.heyarr.core.vault

import one.rarebit.heyarr.core.crypto.Blake3

/**
 * Content-addressed ids for the encrypted personal-state protocol — byte-identical to heyarr-core's
 * `internal/personalstate/protocol` (`computeID` / `computeSnapshotID`).
 *
 * The id is **NOT** `blake3(ciphertext)`. It is BLAKE3 over a length-framed, domain-separated tuple
 * of `(domain, space, sorted refs, ciphertext)`, so a change cannot be re-pointed at another space
 * or re-parented without changing its id. The peer re-derives it on receipt and rejects a mismatch
 * with `ErrIDMismatch` (HTTP 400) — so the client MUST compute it the same way, not just hash the
 * ciphertext. (The W4 client shipped `blake3(ciphertext)`; the fake-transport tests echoed the id
 * back, so the mismatch only surfaced against the real Go node. KAT-locked here — see
 * `PersonalStateIdTest` against Go-generated vectors.)
 *
 * Framing mirrors Go exactly: `field(b) = uvarint(len(b)) ‖ b`, uvarint is LEB128
 * (`encoding/binary.PutUvarint`), and the ref count is a bare uvarint.
 */
object PersonalStateId {
    private const val CHANGE_DOMAIN = "heyarr/personalstate/change/v1"
    private const val SNAPSHOT_DOMAIN = "heyarr/personalstate/snapshot/v1"

    /** The change id `"blake3:<hex>"` over (domain, space, canonical parents, ciphertext). */
    fun changeId(spaceId: String, parents: List<String>, ciphertext: ByteArray): String =
        id(CHANGE_DOMAIN, spaceId, canonical(parents), ciphertext)

    /** The snapshot id `"blake3:<hex>"` over (domain, space, canonical frontier, ciphertext). */
    fun snapshotId(spaceId: String, frontier: List<String>, ciphertext: ByteArray): String =
        id(SNAPSHOT_DOMAIN, spaceId, canonical(frontier), ciphertext)

    /**
     * Sorted, de-duplicated, empty-free — matches Go `canonicalParents`. The client must send the
     * SAME canonical list it hashed, or the peer's re-derivation (which canonicalises again) won't
     * agree.
     */
    fun canonical(refs: List<String>): List<String> = refs.filter { it.isNotEmpty() }.distinct().sorted()

    private fun id(domain: String, spaceId: String, refs: List<String>, ciphertext: ByteArray): String {
        val buf = ByteBuf()
        buf.field(domain.encodeToByteArray())
        buf.field(spaceId.encodeToByteArray())
        buf.uvarint(refs.size.toLong())
        for (r in refs) buf.field(r.encodeToByteArray())
        buf.field(ciphertext)
        return Blake3.hashHex(buf.toByteArray())
    }

    /** A tiny growable byte writer — pure Kotlin (commonMain), no JVM streams. */
    private class ByteBuf {
        private var buf = ByteArray(64)
        private var len = 0

        private fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var n = buf.size * 2
            while (n < len + extra) n *= 2
            buf = buf.copyOf(n)
        }

        fun byte(b: Int) {
            ensure(1)
            buf[len++] = b.toByte()
        }

        fun bytes(b: ByteArray) {
            ensure(b.size)
            b.copyInto(buf, len)
            len += b.size
        }

        /** LEB128 unsigned varint, byte-identical to Go's `binary.PutUvarint`. */
        fun uvarint(value: Long) {
            var v = value
            while (true) {
                val low = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) {
                    byte(low or 0x80)
                } else {
                    byte(low)
                    return
                }
            }
        }

        fun field(b: ByteArray) {
            uvarint(b.size.toLong())
            bytes(b)
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }
}
