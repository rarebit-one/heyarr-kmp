package one.rarebit.heyarr.vault

import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A manifest Go's `encoding/json` (last duplicate wins, case-insensitive keys) and the tolerant
 * scanner (first match, exact keys) could read differently is refused, so two clients never
 * decrypt different blobs from one sealed manifest.
 */
class ManifestKeysTest {
    private val json = VaultFrame.manifestJson(
        VaultFrame.seal(
            VoidbindEncryption.newSpaceKey(),
            "hello".encodeToByteArray(),
            ByteArray(16) {
                it.toByte()
            },
        ).second,
    )
    private val otherContent = "\"content\":\"blake3:" + "b".repeat(64) + "\""

    @Test
    fun aWriterManifestParses() {
        assertEquals(5L, VaultFrame.parseManifest(json).plaintextSize)
    }

    @Test
    fun aRepeatedKeyIsRefused() {
        val doubled = json.dropLast(1) + "," + otherContent + "}"
        assertFailsWith<VaultFrame.FrameException> { VaultFrame.parseManifest(doubled) }
    }

    @Test
    fun aKeyInAnotherCaseIsRefused() {
        val cased = json.dropLast(1) + "," + otherContent.replace("\"content\"", "\"Content\"") + "}"
        assertFailsWith<VaultFrame.FrameException> { VaultFrame.parseManifest(cased) }
    }

    @Test
    fun anythingButOneStrictObjectIsRefused() {
        assertFailsWith<VaultFrame.FrameException> { VaultFrame.parseManifest("$json trailing") }
        assertFailsWith<VaultFrame.FrameException> { VaultFrame.parseManifest("[$json]") }
    }

    @Test
    fun aFractionalOrExponentIntegerFieldIsRefused() {
        for (bad in listOf("5.0", "5e0", "5.5")) {
            val m = json.replace("\"plaintext_size\":5", "\"plaintext_size\":$bad")
            check(m != json) { "fixture did not contain plaintext_size:5" }
            assertFailsWith<VaultFrame.FrameException> { VaultFrame.parseManifest(m) }
        }
    }
}
