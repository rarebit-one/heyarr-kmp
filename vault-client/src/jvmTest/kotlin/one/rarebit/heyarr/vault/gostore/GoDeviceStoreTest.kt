package one.rarebit.heyarr.vault.gostore

import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Go device-store parsing (the one place the daemon touches the void-which-binds-go store's on-disk
 * format). Uses a synthetic store dir — never the real secret seed — so the framing + hex decode are
 * proven without a live enrolment.
 */
class GoDeviceStoreTest {

    // A throwaway, non-secret 32-byte seed (64 hex) purely to exercise the decoder.
    private val fakeSeedHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    private fun store(): Pair<GoDeviceStore, File> {
        val dir = Files.createTempDirectory("vwb-store").toFile()
        File(dir, "device.json").writeText(
            """
            {
              "id": "01a0ae16-afa2-74e6-a8aa-b06fbe5e4d95",
              "public_key": "ed25519:2dbc0bedd3b838dc6e7ab86ba0c65f971863a9bb9a540bc69fa959195f612376",
              "encryption_key": "x25519:dc8932fcfb51665b8363accfb66f41ab5013528d9847c567e13dac782443e728"
            }
            """.trimIndent(),
        )
        File(dir, "device_x25519.key").writeText("void-which-binds-device-x25519-seed:$fakeSeedHex\n")
        File(dir, "device_ed25519.key").writeText("void-which-binds-device-ed25519-seed:$fakeSeedHex\n")
        return GoDeviceStore(dir) to dir
    }

    @Test
    fun readsMetadataRefs() {
        val (s, dir) = store()
        try {
            assertEquals("x25519:dc8932fcfb51665b8363accfb66f41ab5013528d9847c567e13dac782443e728", s.encKeyRef())
            assertEquals("ed25519:2dbc0bedd3b838dc6e7ab86ba0c65f971863a9bb9a540bc69fa959195f612376", s.deviceKeyRef())
            assertEquals("01a0ae16-afa2-74e6-a8aa-b06fbe5e4d95", s.deviceId())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun decodesThe32ByteEncSeed() {
        val (s, dir) = store()
        try {
            val seed = s.encSeed()
            assertEquals(32, seed.size)
            assertEquals(0x00.toByte(), seed[0])
            assertEquals(0xff.toByte(), seed[15])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsAWrongPrefix() {
        val dir = Files.createTempDirectory("vwb-store").toFile()
        try {
            File(dir, "device_x25519.key").writeText("some-other-format:$fakeSeedHex\n")
            assertFailsWith<IllegalArgumentException> { GoDeviceStore(dir).encSeed() }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsAGen1SeedFile() {
        // Go v0.19.0 accepts only the gen2 `void-which-binds-device-` marker (ADR-0022); so does the daemon.
        val dir = Files.createTempDirectory("vwb-store").toFile()
        try {
            File(dir, "device_x25519.key").writeText("voidbind-device-x25519-seed:$fakeSeedHex\n")
            assertFailsWith<IllegalArgumentException> { GoDeviceStore(dir).encSeed() }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsATruncatedSeed() {
        val dir = Files.createTempDirectory("vwb-store").toFile()
        try {
            File(dir, "device_x25519.key").writeText("void-which-binds-device-x25519-seed:00112233\n")
            assertFailsWith<IllegalArgumentException> { GoDeviceStore(dir).encSeed() }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingStoreFailsWithAClearMessage() {
        val dir = Files.createTempDirectory("vwb-store").toFile()
        dir.deleteRecursively() // now absent
        val ex = assertFailsWith<IllegalArgumentException> { GoDeviceStore(dir).encKeyRef() }
        assertTrue(ex.message!!.contains("device store not found"), ex.message)
    }

    @Test
    fun hexToBytesRoundTrips() {
        val bytes = GoDeviceStore.hexToBytes("deadbeef")
        assertEquals(4, bytes.size)
        assertEquals(0xde.toByte(), bytes[0])
        assertEquals(0xef.toByte(), bytes[3])
        assertFailsWith<IllegalArgumentException> { GoDeviceStore.hexToBytes("zz") }
        assertFailsWith<IllegalArgumentException> { GoDeviceStore.hexToBytes("abc") } // odd length
    }

    @Test
    fun recipientUnwrapsACopySealedForItsEncKey() {
        // A real X25519 pair from the JDK: the raw seed is the PKCS#8 encoding's last 32 bytes,
        // the raw public key the X.509 encoding's.
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val seed = pair.private.encoded.takeLast(32).toByteArray()
        val pub = pair.public.encoded.takeLast(32).toByteArray()
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
        val dir = Files.createTempDirectory("vwb-store").toFile()
        try {
            File(
                dir,
                "device.json",
            ).writeText("""{"id":"d","public_key":"ed25519:00","encryption_key":"x25519:${hex(pub)}"}""")
            File(dir, "device_x25519.key").writeText("void-which-binds-device-x25519-seed:${hex(seed)}\n")
            val recipient = GoDeviceStore(dir).recipient()
            assertEquals("x25519:${hex(pub)}", recipient.id)
            val spaceKey = VoidbindEncryption.newSpaceKey()
            assertContentEquals(spaceKey, recipient.unwrap(VoidbindEncryption.seal(spaceKey, pub)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
