package one.rarebit.heyarr.core.vault

import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.voidbind.crypto.VoidbindEncryption
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
    fun voidbindPrimitiveRoundTrips() {
        // Isolates "is a crypto provider registered on :core's test runtime?" from any
        // vector-parsing issue. If this throws, the provider is missing on the classpath.
        val key = ByteArray(32) { it.toByte() }
        val msg = "hello vault".encodeToByteArray()
        val ct = VoidbindEncryption.encryptChange(key, msg)
        val back = VoidbindEncryption.decryptChange(key, ct)
        assertTrue(back.contentEquals(msg), "voidbind encrypt/decrypt round-trip failed")
    }

    @Test
    fun opensGoSealedFrames() {
        val body = resource("/vault/frame_vectors.json")
        val cases = JsonScan.objectsOf(body, emptyList())
        assertTrue(cases.isNotEmpty(), "no frame vectors loaded")

        val problems = StringBuilder()
        for (obj in cases) {
            val name = JsonScan.stringField(obj, "name") ?: "?"
            try {
                val spaceKey = hex(JsonScan.stringField(obj, "space_key_hex")!!)
                val plaintext = b64(JsonScan.stringField(obj, "plaintext_b64")!!)
                val content = b64(JsonScan.stringField(obj, "content_b64")!!)
                val manifestBlob = b64(JsonScan.stringField(obj, "manifest_b64")!!)
                val manifest = VaultFrame.parseManifest(JsonScan.objectAt(obj, "manifest")!!)
                val fetch = VaultFrame.Fetch { start, end -> content.copyOfRange(start.toInt(), end.toInt()) }

                val all = VaultFrame.openAll(spaceKey, manifest, fetch)
                if (!all.contentEquals(plaintext)) {
                    problems.append("[$name] openAll mismatch: got ${all.size}B want ${plaintext.size}B; manifest=$manifest\n")
                }
                val size = manifest.plaintextSize
                var off = 0L
                while (off < size) {
                    val got = VaultFrame.openRange(spaceKey, manifest, off, 1, fetch)
                    if (got.single() != plaintext[off.toInt()]) {
                        problems.append("[$name] byte $off mismatch\n")
                    }
                    off++
                }
                assertEquals(manifest, VaultFrame.openManifest(spaceKey, manifestBlob), "[$name] manifest")
            } catch (t: Throwable) {
                val m = runCatching { VaultFrame.parseManifest(JsonScan.objectAt(obj, "manifest")!!) }.getOrNull()
                problems.append("[$name] ${t::class.simpleName}: ${t.message}; parsed manifest=$m\n")
            }
        }
        if (problems.isNotEmpty()) fail("vault frame vectors failed:\n$problems")
    }

    @Test
    fun rejectsAReorderedFrame() {
        val body = resource("/vault/frame_vectors.json")
        val multi = JsonScan.objectsOf(body, emptyList())
            .first { JsonScan.stringField(it, "name") == "multi_three_frames" }
        val spaceKey = hex(JsonScan.stringField(multi, "space_key_hex")!!)
        val content = b64(JsonScan.stringField(multi, "content_b64")!!)
        val manifest = VaultFrame.parseManifest(JsonScan.objectAt(multi, "manifest")!!)

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
