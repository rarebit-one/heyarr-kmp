package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.crypto.Blake3
import java.io.ByteArrayOutputStream

/**
 * Ports `internal/personalstate/protocol/change.go` `computeID` /
 * `snapshot.go` `computeSnapshotID`: the content-addressed id is a length-framed
 * BLAKE3 digest, "blake3:<hex>", over a domain label, the space id, the
 * canonicalised parents (count + each), and the ciphertext (Invariant 1,
 * ADR-0005). Every field is length-framed so no byte can migrate across a
 * boundary and leave the digest unchanged. The node re-derives this and refuses a
 * mismatch, so a change this phone mints is only accepted if the id is
 * byte-identical to Go's — proven by [ChangeIdTest] against the committed vectors.
 */
internal object ChangeId {
    private const val CHANGE_DOMAIN = "heyarr/personalstate/change/v1"
    private const val SNAPSHOT_DOMAIN = "heyarr/personalstate/snapshot/v1"

    /** The change id for a space, its parents (in any order), and the ciphertext. */
    fun computeChange(spaceId: String, parents: List<String>, ciphertext: ByteArray): String =
        compute(CHANGE_DOMAIN, spaceId, parents, ciphertext)

    /** The snapshot id — same framing, a distinct domain so a change and a snapshot over one payload never collide. */
    fun computeSnapshot(spaceId: String, frontier: List<String>, ciphertext: ByteArray): String =
        compute(SNAPSHOT_DOMAIN, spaceId, frontier, ciphertext)

    /**
     * Canonicalise parents exactly as Go's `canonicalParents`: drop empties,
     * de-duplicate, sort ascending. The id and the wire `parents` array both use
     * this, so the order or repetition a caller passed never changes the id.
     */
    fun canonicalParents(parents: List<String>): List<String> =
        parents.filter { it.isNotEmpty() }.distinct().sorted()

    private fun compute(domain: String, spaceId: String, parents: List<String>, ciphertext: ByteArray): String {
        val canonical = canonicalParents(parents)
        val buf = ByteArrayOutputStream()
        writeField(buf, domain.encodeToByteArray())
        writeField(buf, spaceId.encodeToByteArray())
        writeUvarint(buf, canonical.size.toLong())
        for (p in canonical) writeField(buf, p.encodeToByteArray())
        writeField(buf, ciphertext)
        return "blake3:" + Hex.encode(Blake3.hash(buf.toByteArray()))
    }

    private fun writeField(buf: ByteArrayOutputStream, b: ByteArray) {
        writeUvarint(buf, b.size.toLong())
        buf.write(b)
    }

    /** LEB128 unsigned varint — the encoding Go's `binary.PutUvarint` writes. */
    private fun writeUvarint(buf: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                buf.write(b or 0x80)
            } else {
                buf.write(b)
                return
            }
        }
    }
}

/** Lowercase hex, matching Go's `hex.EncodeToString`. */
internal object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /** Decode lowercase hex, matching Go's `hex.DecodeString`; null on a malformed string. */
    fun decodeOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
