package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.vault.SyncIndexEntry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncIndexTest {

    private val sample = mapOf(
        "a.txt" to SyncIndexEntry("blake3:p1", "blake3:c1", 5, 100),
        "dir/b.txt" to SyncIndexEntry("blake3:p2", "blake3:c2", 7, 200),
    )

    @Test
    fun fileStoreRoundTrips() {
        val f = Files.createTempFile("vault-index", ".json").toFile()
        try {
            val store = FileSyncIndexStore(f)
            store.save(sample)
            assertEquals(sample, store.load())
        } finally {
            f.delete()
        }
    }

    @Test
    fun missingFileLoadsEmpty() {
        assertTrue(FileSyncIndexStore(File(Files.createTempDirectory("x").toFile(), "nope.json")).load().isEmpty())
    }

    @Test
    fun inMemoryStore() {
        val store = InMemorySyncIndexStore()
        assertTrue(store.load().isEmpty())
        store.save(sample)
        assertEquals(sample, store.load())
    }
}
