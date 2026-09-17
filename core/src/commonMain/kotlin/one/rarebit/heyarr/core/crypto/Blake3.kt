package one.rarebit.heyarr.core.crypto

/**
 * BLAKE3 (unkeyed), the content hash heyarr uses to name every ciphertext blob and to
 * content-address a change/snapshot (`blake3:<64 hex>`). A faithful port of the official
 * BLAKE3 `reference_impl` — vendored rather than pulled as a dependency to keep this
 * module's no-library stance. Pure Kotlin, no provider, so it works on every target.
 *
 * Verified byte-for-byte against vectors generated from heyarr-core's own BLAKE3 (which
 * is the standard) across single-chunk and multi-chunk tree sizes (jvmTest). Only the
 * 32-byte output is implemented — the blob-id length.
 */
object Blake3 {
    const val OUT_LEN = 32
    private const val BLOCK_LEN = 64
    private const val CHUNK_LEN = 1024

    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8

    private val IV = intArrayOf(
        0x6A09E667L.toInt(), 0xBB67AE85L.toInt(), 0x3C6EF372L.toInt(), 0xA54FF53AL.toInt(),
        0x510E527FL.toInt(), 0x9B05688CL.toInt(), 0x1F83D9ABL.toInt(), 0x5BE0CD19L.toInt(),
    )

    private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    /** The `"blake3:<hex>"` id of [input]. */
    fun hashHex(input: ByteArray): String = "blake3:" + toHex(hash(input))

    /** The 32-byte BLAKE3 digest of [input]. */
    fun hash(input: ByteArray): ByteArray {
        val h = Hasher()
        h.update(input)
        return h.finalize()
    }

    /**
     * A stateful hasher for large inputs fed in chunks — so a multi-GB vault file is
     * hashed by streaming, never loaded whole (the file scanner uses this).
     */
    class Streaming internal constructor() {
        private val h = Hasher()

        /** Feed [len] bytes from [chunk] (default its whole length). */
        fun update(chunk: ByteArray, len: Int = chunk.size) {
            h.update(if (len == chunk.size) chunk else chunk.copyOf(len))
        }

        /** The `"blake3:<hex>"` id of everything fed so far. */
        fun hashHex(): String = "blake3:" + toHex(h.finalize())
    }

    fun streaming(): Streaming = Streaming()

    private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        s[a] = s[a] + s[b] + mx
        s[d] = (s[d] xor s[a]).rotateRight(16)
        s[c] = s[c] + s[d]
        s[b] = (s[b] xor s[c]).rotateRight(12)
        s[a] = s[a] + s[b] + my
        s[d] = (s[d] xor s[a]).rotateRight(8)
        s[c] = s[c] + s[d]
        s[b] = (s[b] xor s[c]).rotateRight(7)
    }

    private fun round(s: IntArray, m: IntArray) {
        // columns
        g(s, 0, 4, 8, 12, m[0], m[1])
        g(s, 1, 5, 9, 13, m[2], m[3])
        g(s, 2, 6, 10, 14, m[4], m[5])
        g(s, 3, 7, 11, 15, m[6], m[7])
        // diagonals
        g(s, 0, 5, 10, 15, m[8], m[9])
        g(s, 1, 6, 11, 12, m[10], m[11])
        g(s, 2, 7, 8, 13, m[12], m[13])
        g(s, 3, 4, 9, 14, m[14], m[15])
    }

    private fun permute(m: IntArray): IntArray {
        val out = IntArray(16)
        for (i in 0 until 16) out[i] = m[MSG_PERMUTATION[i]]
        return out
    }

    /** Returns the 16-word compression output. */
    private fun compress(cv: IntArray, block: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
        val s = IntArray(16)
        for (i in 0 until 8) s[i] = cv[i]
        s[8] = IV[0]; s[9] = IV[1]; s[10] = IV[2]; s[11] = IV[3]
        s[12] = (counter and 0xFFFFFFFFL).toInt()
        s[13] = (counter ushr 32).toInt()
        s[14] = blockLen
        s[15] = flags

        var m = block
        round(s, m); m = permute(m)
        round(s, m); m = permute(m)
        round(s, m); m = permute(m)
        round(s, m); m = permute(m)
        round(s, m); m = permute(m)
        round(s, m); m = permute(m)
        round(s, m) // 7th round, no permute after

        for (i in 0 until 8) {
            s[i] = s[i] xor s[i + 8]
            s[i + 8] = s[i + 8] xor cv[i]
        }
        return s
    }

    private fun wordsFromLE(b: ByteArray): IntArray {
        val w = IntArray(16)
        for (i in 0 until 16) {
            val o = i * 4
            w[i] = (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)
        }
        return w
    }

    /** A compression the tree can either chain (first 8 words) or finalise (root, XOF). */
    private class Output(
        val inputCv: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int,
    ) {
        fun chainingValue(): IntArray = compress(inputCv, blockWords, counter, blockLen, flags).copyOf(8)

        fun rootBytes(outLen: Int): ByteArray {
            val out = ByteArray(outLen)
            var counterT = 0L
            var written = 0
            while (written < outLen) {
                val words = compress(inputCv, blockWords, counterT, blockLen, flags or ROOT)
                for (word in words) {
                    for (b in 0 until 4) {
                        if (written >= outLen) break
                        out[written++] = ((word ushr (8 * b)) and 0xFF).toByte()
                    }
                }
                counterT++
            }
            return out
        }
    }

    private class ChunkState(val key: IntArray, val chunkCounter: Long, val flags: Int) {
        var cv = key.copyOf()
        val block = ByteArray(BLOCK_LEN)
        var blockLen = 0
        var blocksCompressed = 0

        fun len(): Int = BLOCK_LEN * blocksCompressed + blockLen
        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(input: ByteArray, from: Int, to: Int) {
            var i = from
            while (i < to) {
                if (blockLen == BLOCK_LEN) {
                    cv = compress(cv, wordsFromLE(block), chunkCounter, BLOCK_LEN, flags or startFlag()).copyOf(8)
                    blocksCompressed++
                    block.fill(0)
                    blockLen = 0
                }
                val take = minOf(BLOCK_LEN - blockLen, to - i)
                input.copyInto(block, blockLen, i, i + take)
                blockLen += take
                i += take
            }
        }

        fun output(): Output {
            val padded = block.copyOf() // already 64, zero-padded past blockLen
            return Output(cv, wordsFromLE(padded), chunkCounter, blockLen, flags or startFlag() or CHUNK_END)
        }
    }

    private fun parentOutput(left: IntArray, right: IntArray, key: IntArray, flags: Int): Output {
        val block = IntArray(16)
        for (i in 0 until 8) { block[i] = left[i]; block[i + 8] = right[i] }
        return Output(key, block, 0, BLOCK_LEN, PARENT or flags)
    }

    private class Hasher {
        private val key = IV
        private var chunk = ChunkState(IV, 0, 0)
        private val cvStack = arrayOfNulls<IntArray>(54)
        private var stackLen = 0

        private fun push(cv: IntArray) { cvStack[stackLen++] = cv }
        private fun pop(): IntArray = cvStack[--stackLen]!!

        private fun addChunkCv(first: IntArray, totalChunks: Long) {
            var cv = first
            var t = totalChunks
            while (t and 1L == 0L) {
                cv = parentOutput(pop(), cv, key, 0).chainingValue()
                t = t shr 1
            }
            push(cv)
        }

        fun update(input: ByteArray) {
            var i = 0
            while (i < input.size) {
                if (chunk.len() == CHUNK_LEN) {
                    val cv = chunk.output().chainingValue()
                    val total = chunk.chunkCounter + 1
                    addChunkCv(cv, total)
                    chunk = ChunkState(key, total, 0)
                }
                val want = CHUNK_LEN - chunk.len()
                val take = minOf(want, input.size - i)
                chunk.update(input, i, i + take)
                i += take
            }
        }

        fun finalize(): ByteArray {
            var output = chunk.output()
            var remaining = stackLen
            while (remaining > 0) {
                remaining--
                output = parentOutput(cvStack[remaining]!!, output.chainingValue(), key, 0)
            }
            return output.rootBytes(OUT_LEN)
        }
    }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }
}
