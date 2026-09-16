package one.rarebit.heyarr.core.vault

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
            val name = JsonScan.stringField(obj, "name") ?: fail("case has no name")
            val spaceKey = hex(JsonScan.stringField(obj, "space_key_hex")!!)
            val plaintext = b64(JsonScan.stringField(obj, "plaintext_b64")!!)
            val content = b64(JsonScan.stringField(obj, "content_b64")!!)
            val manifestBlob = b64(JsonScan.stringField(obj, "manifest_b64")!!)
            val manifest = VaultFrame.parseManifest(JsonScan.objectAt(obj, "manifest")!!)

            val fetch = VaultFrame.Fetch { start, end -> content.copyOfRange(start.toInt(), end.toInt()) }

            // Whole file.
            assertTrue(
                VaultFrame.openAll(spaceKey, manifest, fetch).contentEquals(plaintext),
                "[$name] openAll did not recover the plaintext",
            )

            // Every single-byte and a couple of spanning ranges.
            val size = manifest.plaintextSize
            if (size > 0) {
                for (off in 0 until size) {
                    val got = VaultFrame.openRange(spaceKey, manifest, off, 1, fetch)
                    assertEquals(plaintext[off.toInt()], got.single(), "[$name] byte at $off")
                }
                val mid = size / 2
                assertTrue(
                    VaultFrame.openRange(spaceKey, manifest, mid, size - mid, fetch)
                        .contentEquals(plaintext.copyOfRange(mid.toInt(), size.toInt())),
                    "[$name] tail range",
                )
            }

            // The sealed manifest round-trips to the same geometry.
            assertEquals(manifest, VaultFrame.openManifest(spaceKey, manifestBlob), "[$name] manifest")
        }
    }

    @Test
    fun rejectsAReorderedFrame() {
        val body = resource("/vault/frame_vectors.json")
        val multi = JsonScan.objectsOf(body, emptyList())
            .first { JsonScan.stringField(it, "name") == "multi_three_frames" }
        val spaceKey = hex(JsonScan.stringField(multi, "space_key_hex")!!)
        val content = b64(JsonScan.stringField(multi, "content_b64")!!)
        val manifest = VaultFrame.parseManifest(JsonScan.objectAt(multi, "manifest")!!)

        // Fetch frame 0's ciphertext but ask openFrame to treat it as frame 1: the
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
