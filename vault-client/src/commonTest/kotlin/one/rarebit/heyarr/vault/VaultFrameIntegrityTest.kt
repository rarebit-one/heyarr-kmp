package one.rarebit.heyarr.vault

import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Content addressing on read: a manifest and a content blob are held to the ids they were fetched by. */
class VaultFrameIntegrityTest {
    private val key = VoidbindEncryption.newSpaceKey()
    private val fileId = ByteArray(VaultFrame.FILE_ID_LEN) { it.toByte() }
    private val plaintext = ByteArray(40) { it.toByte() } // three frames at frame size 16

    private fun fetchOf(blob: ByteArray) =
        VaultFrame.Fetch { start, end -> blob.copyOfRange(start.toInt(), end.toInt()) }

    @Test
    fun theWholeContentBlobIsHashedAcrossItsRangedFrames() {
        val (content, m) = VaultFrame.seal(key, plaintext, fileId, frameSize = 16)
        assertContentEquals(plaintext, VaultFrame.openAllVerified(key, m, fetchOf(content)))

        val (other, _) = VaultFrame.seal(key, plaintext, fileId, frameSize = 16) // same frames, new nonces
        assertFailsWith<VaultFrame.IntegrityException> { VaultFrame.openAllVerified(key, m, fetchOf(other)) }
    }

    @Test
    fun aManifestIsCheckedAgainstItsIdBeforeItIsDecrypted() {
        val (_, m) = VaultFrame.seal(key, plaintext, fileId, frameSize = 16)
        val sealed = VaultFrame.sealManifest(key, m)
        val ring = SpaceKeyring.single(key)
        VaultFrame.openManifestVerified(ring, sealed, VaultFrame.BLAKE3.hash(sealed))
        val another = VaultFrame.sealManifest(key, m) // same manifest, sealed again: a different blob
        assertFailsWith<VaultFrame.IntegrityException> {
            VaultFrame.openManifestVerified(ring, another, VaultFrame.BLAKE3.hash(sealed))
        }
    }
}
