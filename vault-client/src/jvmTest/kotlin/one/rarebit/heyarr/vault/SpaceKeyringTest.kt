package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * The space keyring (heyarr ADR-0103), replayed against void-which-binds-go's key-chain golden
 * vector. `resources/vault/key_chain_three_epochs.json` is a verbatim copy of void-which-binds-go
 * v0.26.0 `testvectors/vectors/key-chain/three-epochs.json` (commit 9358330): keys[i] is the key
 * of epoch i and links[n] is history row n, keys[n-1] sealed under keys[n]. Never edit it here;
 * re-copy it from Go.
 */
class SpaceKeyringTest {

    private val vector: String =
        SpaceKeyringTest::class.java.getResourceAsStream("/vault/key_chain_three_epochs.json")
            ?.readBytes()?.decodeToString() ?: fail("missing /vault/key_chain_three_epochs.json")

    private val keys: List<ByteArray> = JsonScan.stringArray(vector, "keys").map(::hex)

    private val history: List<KeyHistoryEntry> = JsonScan.objectsOf(vector, listOf("links")).map {
        KeyHistoryEntry(JsonScan.intField(it, "epoch")!!, hex(JsonScan.stringField(it, "sealed_prev")!!))
    }

    @Test
    fun theVectorUnrollsNewestFirstToEpochZero() {
        assertEquals(3, keys.size)
        assertEquals(2, history.size)
        val ring = SpaceKeyring.unroll(keys[2], 2, history)
        assertEquals(2, ring.epoch)
        assertEquals(3, ring.size)
        ring.keys().forEachIndexed { i, k ->
            assertContentEquals(keys[2 - i], k, "ring[$i] is the key of epoch ${2 - i}")
        }
        assertContentEquals(keys[2], ring.current)
    }

    @Test
    fun historyMayArriveInAnyOrder() {
        val ring = SpaceKeyring.unroll(keys[2], 2, history.reversed())
        assertContentEquals(keys[0], ring.keys().last())
    }

    @Test
    fun aMidChainOpenStopsAtItsEpoch() {
        // A device that fetched the space at epoch 1 holds key 1 and row 1 only.
        val ring = SpaceKeyring.unroll(keys[1], 1, history.filter { it.epoch == 1 })
        assertEquals(listOf(1, 0).map { keys[it].toList() }, ring.keys().map { it.toList() })
    }

    @Test
    fun anIncompleteHistoryIsRefused() {
        // Missing row 1.
        assertFailsWith<SpaceKeyring.IncompleteHistoryException> {
            SpaceKeyring.unroll(keys[2], 2, history.filter { it.epoch == 2 })
        }
        // Row 2 duplicated.
        assertFailsWith<SpaceKeyring.IncompleteHistoryException> {
            SpaceKeyring.unroll(keys[2], 2, history + history.last())
        }
        // A row beyond the epoch being opened.
        assertFailsWith<SpaceKeyring.IncompleteHistoryException> { SpaceKeyring.unroll(keys[1], 1, history) }
        // Epoch 0 takes no rows at all.
        assertFailsWith<SpaceKeyring.IncompleteHistoryException> { SpaceKeyring.unroll(keys[0], 0, history) }
    }

    @Test
    fun openIgnoresAHistoryRowNewerThanTheKeysItWasFetchedWith() {
        // GET /keys saw epoch 1; a rotation to epoch 2 landed before GET /key-history.
        val ring = SpaceKeyring.open(keys[1], copyEpoch = 1, keyEpoch = 1, history = history)
        assertEquals(1, ring.epoch)
        assertEquals(listOf(1, 0).map { keys[it].toList() }, ring.keys().map { it.toList() })
        // The strict unroll still refuses it: only the open path knows the two reads can race.
        assertFailsWith<SpaceKeyring.IncompleteHistoryException> { SpaceKeyring.unroll(keys[1], 1, history) }
    }

    @Test
    fun aKeyThatIsNotTheCurrentOneIsRefused() {
        // Key 1 claiming to be epoch 2 cannot open row 2.
        assertFailsWith<SpaceKeyring.KeyChainException> { SpaceKeyring.unroll(keys[1], 2, history) }
        // A tampered row.
        val bad = history.map { h ->
            if (h.epoch != 1) h else KeyHistoryEntry(1, h.sealedPrev.copyOf().also { it[30] = (it[30] + 1).toByte() })
        }
        assertFailsWith<SpaceKeyring.KeyChainException> { SpaceKeyring.unroll(keys[2], 2, bad) }
    }

    @Test
    fun decryptTriesEveryKeyNewestFirstAndWritesUseTheCurrentKey() {
        val ring = SpaceKeyring.unroll(keys[2], 2, history)
        for (e in 0..2) {
            val blob = VoidbindEncryption.encryptChange(keys[e], "epoch $e".encodeToByteArray())
            val opened = ring.open(blob)
            assertEquals("epoch $e", opened.plaintext.decodeToString())
            assertContentEquals(keys[e], opened.key, "the key that opened it is epoch $e's")
        }
        val written = ring.encryptChange("new".encodeToByteArray())
        assertEquals("new", VoidbindEncryption.decryptChange(keys[2], written).decodeToString())
    }

    @Test
    fun aBlobUnderNoHeldKeyIsNoKeyOpens() {
        val ring = SpaceKeyring.unroll(keys[1], 1, history.filter { it.epoch == 1 })
        val newer = VoidbindEncryption.encryptChange(keys[2], "after a rotation".encodeToByteArray())
        val e = assertFailsWith<SpaceKeyring.NoKeyOpensException> { ring.open(newer) }
        assertEquals(1, e.epoch)
    }

    @Test
    fun aManifestSealedUnderAnOlderKeyOpensThroughTheRingAndNamesItsKey() {
        val ring = SpaceKeyring.unroll(keys[2], 2, history)
        val fileId = ByteArray(VaultFrame.FILE_ID_LEN) { it.toByte() }
        val plaintext = ByteArray(40) { (it * 3).toByte() }
        // A file pushed at epoch 0, before both rotations: content and manifest under key 0.
        val (content, manifest) = VaultFrame.seal(keys[0], plaintext, fileId, frameSize = 16)
        val sealedManifest = VaultFrame.sealManifest(keys[0], manifest)

        val opened = VaultFrame.openManifest(ring, sealedManifest)
        assertEquals(manifest, opened.manifest)
        assertContentEquals(keys[0], opened.key)
        val fetch = VaultFrame.Fetch { s, e -> content.copyOfRange(s.toInt(), e.toInt()) }
        assertContentEquals(plaintext, VaultFrame.openAll(opened.key, opened.manifest, fetch))
    }

    @Test
    fun aManifestThatDecryptsButDoesNotParseFailsAtOnce() {
        val ring = SpaceKeyring.unroll(keys[2], 2, history)
        val junk = VoidbindEncryption.encryptChange(keys[1], "not a manifest".encodeToByteArray())
        assertFailsWith<VaultFrame.FrameException> { VaultFrame.openManifest(ring, junk) }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
