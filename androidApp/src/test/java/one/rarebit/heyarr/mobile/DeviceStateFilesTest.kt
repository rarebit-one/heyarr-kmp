package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.DeviceStateFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The gen2 on-disk namespace (void-which-binds-go ADR-0022): a phone upgraded over a gen1
 * build still has the gen1 key, admission and replica on disk, and none of it may be read
 * back against the new gen2 key — no migration, no detect-and-clear, just a namespace the
 * gen1 build never wrote.
 */
class DeviceStateFilesTest {
    private val alias = "device.authorising"

    private fun withFilesDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("heyarr-files").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** What a gen1 build left behind: library key under `voidbind/`, app state under `heyarr-device/`. */
    private fun plantGen1State(filesDir: File) {
        File(filesDir, "voidbind").apply { mkdirs() }.resolve("$alias.key").writeText("gen1-sealed-seed")
        val old = File(filesDir, "heyarr-device").apply { mkdirs() }
        old.resolve("cert.$alias.token").writeText("gen1.admitting.op")
        old.resolve("ops.$alias.json").writeText("""{"ops":["gen1.admitting.op","gen1.other.op"]}""")
        old.resolve("recovery.$alias.pub").writeText("x25519:" + "00".repeat(32))
        old.resolve("secret.enc").writeBytes(ByteArray(48))
    }

    @Test
    fun gen1StateIsIgnored_thePhoneIsUnprovisionedAndUnenrolled() = withFilesDir { filesDir ->
        plantGen1State(filesDir)
        val files = DeviceStateFiles(filesDir, alias)

        assertFalse("a gen1 key under voidbind/ is not a gen2 key", files.isProvisioned())
        assertNull("no gen1 admission is read", files.certToken())
        assertTrue("no gen1 replica is read", files.knownOps().isEmpty())
        assertFalse(files.recoveryFile().exists())
        assertFalse(files.secretFile("enc").exists())
        assertFalse(files.encPubFile().exists())
    }

    @Test
    fun aGen2KeyWithGen1AdmissionLeftoverIsReadyNotEnrolled() = withFilesDir { filesDir ->
        plantGen1State(filesDir)
        File(filesDir, DeviceStateFiles.LIBRARY_KEY_DIR).apply { mkdirs() }.resolve("$alias.key").writeText("gen2")
        val files = DeviceStateFiles(filesDir, alias)

        // Provisioned (the gen2 key exists) but with no admission: the enrol screen's Ready state.
        assertTrue(files.isProvisioned())
        assertNull(files.certToken())
        assertTrue(files.knownOps().isEmpty())
    }

    @Test
    fun theGen2NamespaceIsWhereStateIsWrittenAndRead() = withFilesDir { filesDir ->
        val files = DeviceStateFiles(filesDir, alias)
        assertEquals(File(filesDir, "heyarr-device.vwb"), files.dir())
        files.certFile().writeText("gen2.admitting.op\n")
        assertEquals("gen2.admitting.op", files.certToken())
        assertEquals(listOf("gen2.admitting.op"), files.knownOps())
        // The gen1 dir is never created by a gen2 build.
        assertFalse(File(filesDir, "heyarr-device").exists())
    }
}
