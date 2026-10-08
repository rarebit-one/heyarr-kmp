package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.vault.Drive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveStateTest {
    private val blob = "blake3:" + "ab".repeat(32)

    private fun file(): File = File(Files.createTempDirectory("drive-state").toFile(), "vault-index.drive.json")

    private fun drive(): Drive = Drive().apply {
        put("docs/a.txt", blob, 3, 10)
        put("docs/b.txt", blob, 4, 11)
        delete("docs/b.txt")
    }

    @Test
    fun roundTripsCursorAndDrive() {
        val f = file()
        val d = drive()
        FileDriveStateStore(f).save("http://c", "s1", DriveState(42, d))
        val got = assertNotNull(FileDriveStateStore(f).load("http://c", "s1"))
        assertEquals(42, got.cursor)
        assertEquals(d.snapshot(), got.drive.snapshot())
    }

    @Test
    fun isBoundToItsControllerAndSpace() {
        val f = file()
        FileDriveStateStore(f).save("http://c", "s1", DriveState(7, drive()))
        assertNull(FileDriveStateStore(f).load("http://other", "s1"), "another controller")
        assertNull(FileDriveStateStore(f).load("http://c", "s2"), "another space")
    }

    /** Anything it cannot vouch for is a cold start — never a partial drive with a live cursor. */
    @Test
    fun refusesWhatItCannotVouchFor() {
        val f = file()
        assertNull(FileDriveStateStore(f).load("http://c", "s1"), "missing")

        FileDriveStateStore(f).save("http://c", "s1", DriveState(7, drive()))
        val good = f.readText()

        f.writeText(good.take(good.length / 2))
        assertNull(FileDriveStateStore(f).load("http://c", "s1"), "truncated")

        f.writeText(good.replace("\"version\":1", "\"version\":2"))
        assertNull(FileDriveStateStore(f).load("http://c", "s1"), "unknown version")

        f.writeText(good.replace("\"drive\":", "\"drove\":"))
        assertNull(FileDriveStateStore(f).load("http://c", "s1"), "no drive")

        // Parses, but a write went missing: it no longer re-snapshots to the same bytes.
        f.writeText(good.replace("\"counter\":", "\"extra\":1,\"counter\":"))
        assertNull(FileDriveStateStore(f).load("http://c", "s1"), "does not round-trip")

        f.writeText(good.replace("\"cursor\":7", "\"cursor\":-1"))
        assertNull(FileDriveStateStore(f).load("http://c", "s1"), "negative cursor")
    }

    @Test
    fun isWrittenOwnerOnly() {
        val f = file()
        FileDriveStateStore(f).save("http://c", "s1", DriveState(1, drive()))
        val perms = runCatching { Files.getPosixFilePermissions(f.toPath()) }.getOrNull() ?: return // not POSIX
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
        assertTrue(f.parentFile.listFiles()!!.none { it.name.endsWith(".tmp") }, "no temp file left behind")
    }

    @Test
    fun sitsBesideItsIndex() {
        assertEquals(
            File("/x/vault-index.drive.json"),
            FileDriveStateStore.besideIndex(File("/x/vault-index.json")),
        )
    }
}
