package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.vault.SyncIndexEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalScannerTest {

    @Test
    fun scansPathsSizesAndHashes() {
        val dir = Files.createTempDirectory("scan")
        try {
            Files.writeString(dir.resolve("a.txt"), "hello")
            Files.createDirectories(dir.resolve("sub"))
            Files.writeString(dir.resolve("sub").resolve("b.txt"), "world!!")
            val scan = LocalScanner.scan(dir)
            assertEquals(setOf("a.txt", "sub/b.txt"), scan.keys)
            assertEquals(5L, scan["a.txt"]!!.size)
            assertEquals(Blake3.hashHex("hello".encodeToByteArray()), scan["a.txt"]!!.plaintextHash)
            assertEquals(Blake3.hashHex("world!!".encodeToByteArray()), scan["sub/b.txt"]!!.plaintextHash)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun hybridReusesHashWhenSizeAndMtimeUnchanged() {
        val dir = Files.createTempDirectory("scan-hybrid")
        try {
            val f = dir.resolve("a.txt")
            Files.writeString(f, "hello")
            val size = Files.size(f)
            val mtime = Files.getLastModifiedTime(f).toInstant().epochSecond
            // A deliberately WRONG hash in the index; same (size,mtime) → the scanner must
            // reuse it without re-hashing, proving the cheap gate short-circuits.
            val index = mapOf("a.txt" to SyncIndexEntry("blake3:sentinel", "blake3:blob", size, mtime))
            val scan = LocalScanner.scan(dir, index)
            assertEquals("blake3:sentinel", scan["a.txt"]!!.plaintextHash)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rehashesWhenSizeChanges() {
        val dir = Files.createTempDirectory("scan-rehash")
        try {
            val f = dir.resolve("a.txt")
            Files.writeString(f, "hello world")
            val mtime = Files.getLastModifiedTime(f).toInstant().epochSecond
            // Index has a stale (size) → gate misses → real hash computed, not the sentinel.
            val index = mapOf("a.txt" to SyncIndexEntry("blake3:sentinel", "blake3:blob", 5, mtime))
            val scan = LocalScanner.scan(dir, index)
            assertEquals(Blake3.hashHex("hello world".encodeToByteArray()), scan["a.txt"]!!.plaintextHash)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun ignoresCruftAndSyncTmp() {
        val dir = Files.createTempDirectory("scan-ignore")
        try {
            Files.writeString(dir.resolve("a.txt"), "keep")
            Files.writeString(dir.resolve(".DS_Store"), "x")
            Files.createDirectories(dir.resolve(".sync-tmp"))
            Files.writeString(dir.resolve(".sync-tmp").resolve("inflight"), "partial")
            val scan = LocalScanner.scan(dir)
            assertEquals(setOf("a.txt"), scan.keys)
            assertFalse(scan.containsKey(".DS_Store"))
            assertTrue(scan.none { it.key.startsWith(".sync-tmp/") })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
