package one.rarebit.heyarr.core.vault

/**
 * The authenticated plaintext inside a personal-state snapshot's ciphertext — byte-identical to
 * heyarr-core's `internal/personalstate/protocol/snapshot_envelope.go` (heyarr-core#681).
 *
 * A snapshot's `frontier` and `space_id` travel beside the ciphertext, and its id is a public
 * BLAKE3 digest, so a principal with a `write` token and no space key could relabel a valid
 * snapshot to a different causal point and it would still decrypt. The content cipher takes no
 * associated data, so a producer binds (record type, space, frontier) INSIDE the plaintext it
 * encrypts, and a reader checks that binding against the outer fields after decrypting:
 *
 * ```
 * field("heyarr/personalstate/snapshot-envelope/v2") ‖ field(space) ‖ uvarint(n) ‖ field(head)×n ‖ field(state)
 * ```
 *
 * with `field(b) = uvarint(len b) ‖ b` (LEB128, as [PersonalStateId]) and the heads canonical.
 * Nothing may follow. A snapshot sealed before the envelope (v1) is the bare JSON state; it can
 * never start with the envelope's first field, so [open] returns it as [Opened.Legacy] — its state
 * is genuine (it decrypted under the space key) but its frontier is only a claim. Pinned against
 * the Go-generated `snapshot_envelope.json` by `SnapshotEnvelopeTest`.
 */
object SnapshotEnvelope {
    const val DOMAIN = "heyarr/personalstate/snapshot-envelope/v2"

    private val magic: ByteArray = field(DOMAIN.encodeToByteArray())

    /** What [open] proved about a decrypted snapshot plaintext. */
    sealed class Opened {
        /** Sealed with exactly the presented space and frontier: the frontier may be trusted. */
        class Authenticated(val state: ByteArray) : Opened()

        /** Pre-envelope bare state: genuine, but its frontier is unauthenticated. */
        class Legacy(val state: ByteArray) : Opened()

        /** A relabelled or malformed envelope. Never fold it. */
        class Refused(val reason: String) : Opened()
    }

    /** The plaintext a producer encrypts for a snapshot of [state] at [frontier] in [spaceId]. */
    fun seal(spaceId: String, frontier: List<String>, state: ByteArray): ByteArray {
        val heads = PersonalStateId.canonical(frontier)
        var out = magic + field(spaceId.encodeToByteArray()) + uvarint(heads.size.toLong())
        for (h in heads) out += field(h.encodeToByteArray())
        return out + field(state)
    }

    /** Check a decrypted [plaintext] against the snapshot's outer [spaceId] and [frontier]. */
    fun open(spaceId: String, frontier: List<String>, plaintext: ByteArray): Opened =
        if (startsWith(plaintext, magic)) verify(spaceId, frontier, plaintext) else Opened.Legacy(plaintext)

    private fun verify(spaceId: String, frontier: List<String>, plaintext: ByteArray): Opened = try {
        val sealed = parse(plaintext)
        if (sealed.space == spaceId && sealed.heads == PersonalStateId.canonical(frontier)) {
            Opened.Authenticated(sealed.state)
        } else {
            Opened.Refused("frontier or space does not match what the snapshot was sealed with")
        }
    } catch (e: MalformedEnvelope) {
        Opened.Refused(e.message ?: "malformed envelope")
    }

    private class Sealed(val space: String, val heads: List<String>, val state: ByteArray)

    private class MalformedEnvelope(reason: String) : Exception(reason)

    private fun parse(plaintext: ByteArray): Sealed {
        val r = Reader(plaintext, magic.size)
        val space = r.field().decodeToString()
        val n = r.uvarint()
        if (n > plaintext.size) throw MalformedEnvelope("frontier count exceeds the envelope")
        val heads = List(n) { r.field().decodeToString() }
        val state = r.field()
        if (!r.atEnd()) throw MalformedEnvelope("trailing bytes after the state")
        return Sealed(space, heads, state)
    }

    private fun startsWith(b: ByteArray, prefix: ByteArray): Boolean =
        b.size >= prefix.size && prefix.indices.all { b[it] == prefix[it] }

    private fun uvarint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (v ushr SHIFT != 0L) {
            out.add(((v and LOW_BITS) or CONTINUATION).toByte())
            v = v ushr SHIFT
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    private fun field(b: ByteArray): ByteArray = uvarint(b.size.toLong()) + b

    /** A bounds-checked cursor: a read past the end throws [MalformedEnvelope], never overruns. */
    private class Reader(private val b: ByteArray, private var pos: Int) {
        fun atEnd(): Boolean = pos == b.size

        /** A LEB128 uvarint small enough to be a length inside this envelope. */
        fun uvarint(): Int {
            var result = 0L
            var shift = 0
            while (pos < b.size && shift < MAX_SHIFT) {
                val byte = b[pos++].toLong() and BYTE_MASK
                result = result or ((byte and LOW_BITS) shl shift)
                if (byte and CONTINUATION == 0L) {
                    if (result > Int.MAX_VALUE) break
                    return result.toInt()
                }
                shift += SHIFT
            }
            throw MalformedEnvelope("truncated or oversized length")
        }

        fun field(): ByteArray {
            val n = uvarint()
            if (n > b.size - pos) throw MalformedEnvelope("truncated field")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
    }

    private const val SHIFT = 7
    private const val MAX_SHIFT = 35
    private const val LOW_BITS = 0x7FL
    private const val CONTINUATION = 0x80L
    private const val BYTE_MASK = 0xFFL
}
