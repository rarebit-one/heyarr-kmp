package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.vault.VaultFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultBlobStoreTest {

    @Test
    fun rangeHeaderIsInclusive() {
        // Half-open [0,77) → inclusive bytes=0-76.
        assertEquals("bytes=0-76", JdkVaultBlobStore.rangeHeader(0, 77))
        assertEquals("bytes=77-153", JdkVaultBlobStore.rangeHeader(77, 154))
    }

    /** In-memory store so a seal→store→openAll round trip proves the codec/store wiring. */
    private class FakeBlobStore : VaultBlobStore {
        val blobs = HashMap<String, ByteArray>()
        override fun putBlob(baseUrl: String, hash: String, bytes: ByteArray, credential: Credential): PutResult {
            blobs[hash] = bytes
            return PutResult.Stored(hash, bytes.size.toLong())
        }
        override fun fetchRange(baseUrl: String, hash: String, start: Long, end: Long, credential: Credential): ByteArray =
            blobs.getValue(hash).copyOfRange(start.toInt(), end.toInt())
        override fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray = blobs.getValue(hash)
    }

    @Test
    fun sealStoreThenOpenAllRoundTrips() {
        val spaceKey = ByteArray(32) { it.toByte() }
        val fileId = ByteArray(16) { (0xA0 + it).toByte() }
        val plaintext = ByteArray(40) { (it % 256).toByte() } // 3 frames at frameSize 16
        val (content, manifest) = VaultFrame.seal(spaceKey, plaintext, fileId, frameSize = 16)

        val store = FakeBlobStore()
        val put = store.putBlob("https://n", manifest.content, content, Credential.Guest)
        assertTrue(put is PutResult.Stored && put.hash == manifest.content)

        val fetch = store.fetchFor("https://n", manifest.content, Credential.Guest)
        val recovered = VaultFrame.openAll(spaceKey, manifest, fetch)
        assertTrue(recovered.contentEquals(plaintext), "seal→store→openAll must recover the plaintext")
    }
}
