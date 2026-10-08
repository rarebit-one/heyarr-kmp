package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.vault.KeyHistoryEntry
import one.rarebit.heyarr.desktop.device.DesktopDeviceKeyring
import one.rarebit.heyarr.desktop.device.DesktopSecretStore
import one.rarebit.voidwhichbinds.DeviceIdentity
import one.rarebit.voidwhichbinds.KeyRef
import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Custody bootstrap (W4.3), software tier, with real device keys (hermetic temp keyring) and the
 * real X25519/XChaCha wrap — only the space lifecycle ([VaultKeys]) is faked, so the crypto that
 * makes the vault admin-blind runs for real. Proves: a mint wraps for this device (and, when
 * provisioned, the recovery key) and round-trips; open finds and unwraps this device's copy; a
 * device that is not a recipient cannot open; and the openOrBootstrap policy never mints over a
 * configured space or when the server refuses.
 */
class VaultCustodyTest {

    private val tmp: File = Files.createTempDirectory("vault-custody-test").toFile()

    @AfterTest fun cleanup() {
        tmp.deleteRecursively()
    }

    /** A hermetic sealed-file keyring under a per-device temp subdir (no OS keychain, no network). */
    private fun keyring(sub: String = "d"): DesktopDeviceKeyring {
        val dir = File(tmp, sub)
        val keyFile = File(dir, "device.key")
        return DesktopDeviceKeyring(
            File(dir, "device"),
            keyFile,
            secrets = DesktopSecretStore(File(dir, "sealed"), keyFile),
        )
    }

    /**
     * Records created spaces + serves their wrapped keys, key epoch and key history; can simulate
     * the enrol-before-wrap 403.
     */
    private class FakeKeys(private val failCreate: Boolean = false) : VaultKeys {
        val spaces = LinkedHashMap<String, MutableList<WrappedKey>>()
        val epochs = HashMap<String, Int>()
        val history = HashMap<String, MutableList<KeyHistoryEntry>>()
        var created = 0
        override fun listKeys(spaceId: String): List<WrappedKey> = spaces[spaceId] ?: emptyList()
        override fun spaceKeys(spaceId: String) = SpaceKeyList(epochs[spaceId] ?: 0, listKeys(spaceId))
        override fun keyHistory(spaceId: String): List<KeyHistoryEntry> = history[spaceId] ?: emptyList()
        override fun createSpace(id: String, kind: String, wrapped: List<WrappedKey>): String {
            if (failCreate) throw IllegalStateException("vault: POST /spaces failed: HTTP 403")
            created++
            spaces[id] = wrapped.toMutableList()
            return id
        }
    }

    @Test
    fun bootstrapWrapsForThisDeviceAndRoundTrips() {
        val ring = keyring()
        val keys = FakeKeys()
        val opened = VaultCustody(ring, keys, newSpaceId = { "space-1" }).bootstrap()

        assertEquals("space-1", opened.spaceId)
        assertEquals(1, keys.created)
        val id = ring.identity()
        val mine = keys.spaces.getValue("space-1").single { it.recipient == id.deviceEncId.render() }
        assertContentEquals(opened.spaceKey, VoidbindEncryption.unwrap(mine.wrapped, id.encPrivateKey))
        // No recovery recipient provisioned → wrapped for this device only (the honest default).
        assertEquals(1, keys.spaces.getValue("space-1").size)
        assertEquals("personal", VaultCustody.DEFAULT_KIND)
    }

    @Test
    fun bootstrapAlsoWrapsForTheRecoveryRecipient() {
        val ring = keyring()
        val recovery = DeviceIdentity.generateEncryptionKey()
        val recoveryRef = KeyRef.x25519(recovery.publicKey).render()
        ring.saveRecoveryRecipient(recoveryRef)

        val keys = FakeKeys()
        val opened = VaultCustody(ring, keys, newSpaceId = { "s" }).bootstrap()

        val copies = keys.spaces.getValue("s")
        assertEquals(2, copies.size)
        val recCopy = copies.single { it.recipient == recoveryRef }
        assertContentEquals(opened.spaceKey, VoidbindEncryption.unwrap(recCopy.wrapped, recovery.privateKey))
    }

    @Test
    fun openFindsAndUnwrapsThisDevicesCopy() {
        val ring = keyring()
        val keys = FakeKeys()
        val custody = VaultCustody(ring, keys, newSpaceId = { "s" })
        val minted = custody.bootstrap()

        val opened = custody.open("s")
        assertNotNull(opened)
        assertContentEquals(minted.spaceKey, opened.spaceKey)
    }

    /**
     * What a rotation (ADR-0103) does on the peer: a fresh key wrapped for [recipient] at the next
     * epoch, the previous key sealed under it as a history row, and the old copies dropped.
     */
    private fun rotate(keys: FakeKeys, spaceId: String, prev: ByteArray, recipient: String, pub: ByteArray): ByteArray {
        val next = VoidbindEncryption.newSpaceKey()
        val epoch = (keys.epochs[spaceId] ?: 0) + 1
        keys.epochs[spaceId] = epoch
        keys.history.getOrPut(spaceId) { mutableListOf() }.add(
            KeyHistoryEntry(epoch, VoidbindEncryption.sealSpaceKey(next, prev)),
        )
        keys.spaces[spaceId] = mutableListOf(WrappedKey(recipient, VoidbindEncryption.seal(next, pub), epoch))
        return next
    }

    @Test
    fun openUnrollsARotatedSpaceToEveryEarlierKey() {
        val ring = keyring()
        val keys = FakeKeys()
        val custody = VaultCustody(ring, keys, newSpaceId = { "s" })
        val k0 = custody.bootstrap().spaceKey
        val id = ring.identity()
        val k1 = rotate(keys, "s", k0, id.deviceEncId.render(), id.encPublicKey)
        val k2 = rotate(keys, "s", k1, id.deviceEncId.render(), id.encPublicKey)

        val opened = assertNotNull(custody.open("s"))
        assertEquals(2, opened.keyring.epoch)
        assertContentEquals(k2, opened.spaceKey, "writes use the current key")
        assertEquals(listOf(k2, k1, k0).map { it.toList() }, opened.keyring.keys().map { it.toList() })
    }

    @Test
    fun openRefusesASupersededCopyOrAnIncompleteHistory() {
        val ring = keyring()
        val keys = FakeKeys()
        val custody = VaultCustody(ring, keys, newSpaceId = { "s" })
        val k0 = custody.bootstrap().spaceKey
        val id = ring.identity()
        rotate(keys, "s", k0, id.deviceEncId.render(), id.encPublicKey)

        // The peer still serving this device's epoch-0 copy at epoch 1: refused, not opened stale.
        val stale = keys.spaces.getValue("s").single().copy(epoch = 0)
        keys.spaces["s"] = mutableListOf(stale)
        assertNull(custody.open("s"))

        // A rotated space with its history row missing: refused rather than silently half-readable.
        keys.spaces["s"] = mutableListOf(stale.copy(epoch = 1))
        keys.history.getValue("s").clear()
        assertNull(custody.open("s"))
    }

    @Test
    fun openReturnsNullWhenThisDeviceIsNotARecipient() {
        val keys = FakeKeys()
        VaultCustody(keyring("a"), keys, newSpaceId = { "s" }).bootstrap() // wrapped for A only
        assertNull(VaultCustody(keyring("b"), keys).open("s")) // B holds no readable copy
    }

    @Test
    fun openOrBootstrapMintsFirstRunThenOpensWithoutMintingAgain() {
        val ring = keyring()
        val keys = FakeKeys()
        val custody = VaultCustody(ring, keys, newSpaceId = { "s" })

        val first = custody.openOrBootstrap(null)
        assertTrue(first.minted)
        assertEquals("s", first.opened!!.spaceId)

        val second = custody.openOrBootstrap("s")
        assertFalse(second.minted)
        assertNotNull(second.opened)
        assertEquals(1, keys.created) // did NOT mint a second space
    }

    @Test
    fun openOrBootstrapIsNotReadyForAConfiguredButUnreadableSpace() {
        val keys = FakeKeys()
        VaultCustody(keyring("a"), keys, newSpaceId = { "s" }).bootstrap()

        val result = VaultCustody(keyring("b"), keys).openOrBootstrap("s")
        assertNull(result.opened)
        assertFalse(result.minted) // must NOT fork a second space for the same configured id
        assertNotNull(result.error)
    }

    @Test
    fun openOrBootstrapReportsNotReadyWhenTheServerRefusesTheMint() {
        val result = VaultCustody(keyring(), FakeKeys(failCreate = true)).openOrBootstrap(null)
        assertNull(result.opened)
        assertFalse(result.minted)
        assertNotNull(result.error) // e.g. not enrolled yet (enrol-before-wrap 403)
    }
}
