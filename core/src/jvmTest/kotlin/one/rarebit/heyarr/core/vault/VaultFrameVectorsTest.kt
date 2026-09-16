package one.rarebit.heyarr.core.vault

import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.net.JsonScan
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-language golden-vector test for the vault frame codec. The vectors in
 * `resources/vault/frame_vectors.json` were sealed by live voidbind-go with the same
 * frame layout as heyarr-core's `vaultframe`; this asserts the Kotlin [VaultFrame]
 * OPENS them byte-for-byte (the XChaCha20 nonce is random, so the compat proof is
 * decrypt, not re-encrypt).
 *
 * NOTE on the JDK ChaCha20 guard: cryptography-kotlin 0.6.0's JDK provider pools the
 * underlying `javax.crypto.Cipher`, and SunJCE refuses to re-init it with the SAME
 * (key, nonce) as the immediately preceding op ("Matching key and nonce from previous
 * initialization"). So decrypting the SAME frame twice in a row throws. W4's full-file
 * sync path decrypts each frame exactly once (openAll), which is unaffected; repeated
 * same-frame reads (the deferred VFS/random-access path) are not yet supported on
 * desktop JVM. This test therefore never re-decrypts one frame back-to-back. Tracked
 * in docs/vault-sync.md.
 */
class VaultFrameVectorsTest {

    private fun resource(path: String): String =
        VaultFrameVectorsTest::class.java.getResourceAsStream(path)?.readBytes()?.decodeToString()
            ?: fail("missing test resource $path")

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    @Test
    fun opensGoSealedFrames() {
        val body = resource("/vault/frame_vectors.json")
        val cases = JsonScan.objectsOf(body, emptyList())
        assertTrue(cases.isNotEmpty(), "no frame vectors loaded")

        for (obj in cases) {
            val name = JsonScan.stringField(obj, "name") ?: "?"
            val spaceKey = hex(JsonScan.stringField(obj, "space_key_hex")!!)
            val plaintext = b64(JsonScan.stringField(obj, "plaintext_b64")!!)
            val content = b64(JsonScan.stringField(obj, "content_b64")!!)
            val manifestBlob = b64(JsonScan.stringField(obj, "manifest_b64")!!)
            val manifest = VaultFrame.parseManifest(JsonScan.objectAt(obj, "manifest")!!)
            val fetch = VaultFrame.Fetch { start, end -> content.copyOfRange(start.toInt(), end.toInt()) }

            // The full-file path: decrypts every frame exactly once. THE wire-compat proof.
            assertTrue(
                VaultFrame.openAll(spaceKey, manifest, fetch).contentEquals(plaintext),
                "[$name] openAll did not recover the plaintext",
            )
            // The sealed manifest round-trips to the same geometry (a distinct nonce, so
            // it also separates the openAll above from the range read below).
            assertEquals(manifest, VaultFrame.openManifest(spaceKey, manifestBlob), "[$name] manifest")

            // One partial range per multi-frame case: exercises the offset math on a frame
            // whose nonce differs from the manifest just decrypted (no back-to-back repeat).
            if (manifest.frameCount >= 2) {
                val fs = manifest.frameSize
                val start = fs.toLong()            // first byte of frame 1
                val n = minOf(fs.toLong(), manifest.plaintextSize - start)
                assertTrue(
                    VaultFrame.openRange(spaceKey, manifest, start, n, fetch)
                        .contentEquals(plaintext.copyOfRange(start.toInt(), (start + n).toInt())),
                    "[$name] range over frame 1",
                )
            }
        }
    }

    @Test
    fun sealNamesContentWithBlake3() {
        // seal only encrypts (distinct nonces per frame) — no decrypt, so the JDK guard
        // is not in play. Proves the upload path: frames + BLAKE3-named content blob.
        val key = ByteArray(32) { it.toByte() }
        val fileId = ByteArray(16) { (0xA0 + it).toByte() }
        val plaintext = ByteArray(40) { (it % 256).toByte() }
        val (content, m) = VaultFrame.seal(key, plaintext, fileId, frameSize = 16)
        assertEquals(3, m.frameCount, "40 bytes over 16-byte frames = 3 frames")
        assertEquals(40L, m.plaintextSize)
        assertEquals("blake3:", m.content.substring(0, 7))
        assertEquals(Blake3.hashHex(content), m.content, "content blob is named by its BLAKE3")
    }

    @Test
    fun rejectsAReorderedFrame() {
        val body = resource("/vault/frame_vectors.json")
        val multi = JsonScan.objectsOf(body, emptyList())
            .first { JsonScan.stringField(it, "name") == "multi_three_frames" }
        val spaceKey = hex(JsonScan.stringField(multi, "space_key_hex")!!)
        val content = b64(JsonScan.stringField(multi, "content_b64")!!)
        val manifest = VaultFrame.parseManifest(JsonScan.objectAt(multi, "manifest")!!)

        // Serve frame 0's ciphertext but ask openFrame to treat it as frame 1: the
        // in-plaintext header index no longer matches → FrameException.
        val r0 = manifest.frameByteRange(0)
        val frame0 = content.copyOfRange(r0.first.toInt(), r0.last.toInt() + 1)
        try {
            VaultFrame.openFrame(spaceKey, manifest, 1, frame0)
            fail("a frame served at the wrong index must be rejected")
        } catch (_: VaultFrame.FrameException) {
            // expected
        }
    }
}
