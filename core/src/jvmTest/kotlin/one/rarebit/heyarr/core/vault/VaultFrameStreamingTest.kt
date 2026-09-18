package one.rarebit.heyarr.core.vault

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [VaultFrame.sealStreaming] is the memory-flat twin of [VaultFrame.seal]: it must produce a
 * content blob + manifest that decrypt exactly like the whole-buffer seal and share the same
 * geometry, so a file sealed by streaming is wire-compatible. (The ciphertext bytes themselves
 * differ run to run — each frame's XChaCha nonce is random — so we assert DECRYPTABILITY and
 * geometry, not byte-equality.)
 */
class VaultFrameStreamingTest {

    private val key = ByteArray(32) { it.toByte() }
    private val fileId = ByteArray(VaultFrame.FILE_ID_LEN) { (it + 1).toByte() }

    private fun source(data: ByteArray): VaultFrame.PlaintextSource {
        var pos = 0
        return VaultFrame.PlaintextSource { buf, off, len ->
            if (pos >= data.size) {
                -1
            } else {
                val n = minOf(len, data.size - pos)
                data.copyInto(buf, off, pos, pos + n)
                pos += n
                n
            }
        }
    }

    private fun sealStreamed(data: ByteArray, frameSize: Int): Pair<ByteArray, VaultFrame.Manifest> {
        val out = ByteArrayOutputStream()
        val manifest = VaultFrame.sealStreaming(key, fileId, source(data), { out.write(it) }, frameSize)
        return out.toByteArray() to manifest
    }

    private fun openAll(content: ByteArray, m: VaultFrame.Manifest): ByteArray =
        VaultFrame.openAll(key, m) { s, e -> content.copyOfRange(s.toInt(), e.toInt()) }

    @Test
    fun multiFrameRoundTrips() {
        val plaintext = ByteArray(40) { it.toByte() } // 16 + 16 + 8 across a 16-byte frame
        val (content, m) = sealStreamed(plaintext, frameSize = 16)
        assertEquals(3, m.frameCount)
        assertEquals(40L, m.plaintextSize)
        assertEquals(16, m.frameSize)
        assertEquals(VaultFrame.VERSION, m.version)
        // content id is self-consistent, and the whole file decrypts byte-for-byte
        assertEquals(VaultFrame.BLAKE3.hash(content), m.content)
        assertTrue(plaintext.contentEquals(openAll(content, m)))
    }

    @Test
    fun exactMultipleOfFrameSizeHasNoTrailingFrame() {
        val plaintext = ByteArray(32) { (it * 7).toByte() } // exactly 2 × 16
        val (content, m) = sealStreamed(plaintext, frameSize = 16)
        assertEquals(2, m.frameCount)
        assertEquals(32L, m.plaintextSize)
        assertTrue(plaintext.contentEquals(openAll(content, m)))
    }

    @Test
    fun emptyFileIsZeroFrames() {
        val (content, m) = sealStreamed(ByteArray(0), frameSize = 16)
        assertEquals(0, m.frameCount)
        assertEquals(0L, m.plaintextSize)
        assertEquals(0, content.size)
        assertTrue(openAll(content, m).isEmpty())
    }

    @Test
    fun matchesWholeBufferSealGeometryAndDecrypts() {
        val plaintext = ByteArray(50) { (it * 3 + 1).toByte() }
        val whole = VaultFrame.seal(key, plaintext, fileId, frameSize = 16)
        val (content, streamed) = sealStreamed(plaintext, frameSize = 16)
        // same geometry as the whole-buffer seal
        assertEquals(whole.second.frameCount, streamed.frameCount)
        assertEquals(whole.second.plaintextSize, streamed.plaintextSize)
        assertEquals(whole.second.fileId, streamed.fileId)
        // different ciphertext (random nonces) but both decrypt to the same plaintext
        assertNotEquals(whole.second.content, streamed.content)
        assertTrue(plaintext.contentEquals(openAll(content, streamed)))
    }
}
