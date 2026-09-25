package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.device.DesktopSecretStore
import one.rarebit.heyarr.desktop.device.KeyTier
import one.rarebit.heyarr.desktop.device.KeychainBackend
import one.rarebit.heyarr.desktop.device.KeychainSecretStore
import one.rarebit.heyarr.desktop.device.LibSecretBackend
import one.rarebit.heyarr.desktop.device.MacKeychainBackend
import one.rarebit.heyarr.desktop.device.SecretStores
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the [one.rarebit.heyarr.desktop.device.SecretStore] abstraction and the platform
 * selection rule with a FAKE keychain backend — the real macOS Keychain / libsecret paths
 * are exercised out of band (Keychain on the build host; libsecret is unverifiable there and
 * kept behind the same interface).
 */
class KeychainSecretStoreTest {

    private val tmp: File = Files.createTempDirectory("heyarr-keychain-test").toFile()

    @AfterTest fun cleanup() {
        tmp.deleteRecursively()
    }

    /** An in-memory [KeychainBackend] standing in for macOS Keychain / libsecret. */
    private class FakeKeychain(private val available: Boolean = true, private val writable: Boolean = true) :
        KeychainBackend {
        val items = HashMap<String, ByteArray>()
        override val label = "fake"
        override fun isAvailable() = available
        override fun store(account: String, secret: ByteArray): Boolean {
            if (!writable) return false
            items[account] = secret.copyOf()
            return true
        }
        override fun retrieve(account: String): ByteArray? = items[account]?.copyOf()
        override fun remove(account: String) {
            items.remove(account)
        }
    }

    private fun sealed() = DesktopSecretStore(File(tmp, "sealed"), File(tmp, "device.key"))

    // ── the store over a keychain backend ───────────────────────────────────────────

    @Test fun keychain_store_round_trips_and_reports_the_keychain_tier() {
        val backend = FakeKeychain()
        val store = KeychainSecretStore(backend)
        assertEquals(KeyTier.KEYCHAIN, store.tier)
        assertFalse(store.exists("sign"))

        val secret = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
        store.seal("sign", secret)
        assertTrue(store.exists("sign"))
        assertContentEquals(secret, store.unseal("sign"))

        // The bytes are namespaced under the desktop service, not stored under the bare name.
        assertTrue(backend.items.keys.single().endsWith(".sign"))

        store.delete("sign")
        assertFalse(store.exists("sign"))
        assertNull(store.unseal("sign"))
    }

    @Test fun seal_leaves_no_sealed_file_behind() {
        val legacy = sealed()
        val store = KeychainSecretStore(FakeKeychain(), legacy = legacy)
        store.seal("sign", byteArrayOf(1, 2, 3))
        assertFalse(legacy.exists("sign"), "no plaintext/sealed copy is left on disk")
    }

    // ── migration from a pre-feature sealed file ────────────────────────────────────

    @Test fun unseal_migrates_a_legacy_sealed_secret_into_the_keychain_once() {
        val legacy = sealed()
        val seed = "legacy-device-seed".encodeToByteArray()
        legacy.seal("sign", seed) // enrolled under #35, sealed file only
        assertTrue(legacy.exists("sign"))

        val backend = FakeKeychain()
        val store = KeychainSecretStore(backend, legacy = legacy)

        // First read finds nothing in the keychain, pulls from the sealed file, and migrates.
        assertContentEquals(seed, store.unseal("sign"))
        assertTrue(backend.items.values.any { it.contentEquals(seed) }, "seed is now in the keychain")
        assertFalse(legacy.exists("sign"), "the sealed copy is removed after migration")

        // Second read is served straight from the keychain.
        assertContentEquals(seed, store.unseal("sign"))
    }

    // ── platform selection rule ─────────────────────────────────────────────────────

    @Test fun selection_prefers_the_keychain_when_available() {
        val store = SecretStores.selectWith(FakeKeychain(available = true), sealed())
        assertEquals(KeyTier.KEYCHAIN, store.tier)
    }

    @Test fun selection_falls_back_to_the_sealed_file_when_the_keychain_is_unavailable() {
        val store = SecretStores.selectWith(FakeKeychain(available = false), sealed())
        assertEquals(KeyTier.SOFTWARE, store.tier)
    }

    @Test fun selection_falls_back_when_there_is_no_keychain_backend_for_this_os() {
        val store = SecretStores.selectWith(null, sealed())
        assertEquals(KeyTier.SOFTWARE, store.tier)
    }

    @Test fun selection_falls_back_when_the_availability_probe_throws() {
        val throwing = object : KeychainBackend {
            override val label = "boom"
            override fun isAvailable(): Boolean = throw RuntimeException("keychain exploded")
            override fun store(account: String, secret: ByteArray) = false
            override fun retrieve(account: String): ByteArray? = null
            override fun remove(account: String) {}
        }
        val store = SecretStores.selectWith(throwing, sealed())
        assertEquals(KeyTier.SOFTWARE, store.tier, "a throwing probe degrades to the fallback")
    }

    // ── real macOS Keychain (build host only; a no-op skip elsewhere) ────────────────

    /**
     * Exercises the actual Security-framework JNA binding on a macOS build host with a
     * reachable login keychain: a real store → retrieve → remove round-trip against a
     * throwaway account. Skips (returns) on any non-macOS host and whenever the keychain is
     * not reachable (locked / headless), so it never fails CI's Linux runner and never leaves
     * an item behind.
     */
    @Test fun real_macos_keychain_round_trips_when_reachable() {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!(os.contains("mac") || os.contains("darwin"))) return

        val backend = MacKeychainBackend(service = "one.rarebit.heyarr.desktop.test")
        if (!backend.isAvailable()) return // locked / headless keychain — skip, don't fail

        val account = "roundtrip-" + System.nanoTime()
        try {
            val secret = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            assertNull(backend.retrieve(account), "clean start")
            // A read-only availability probe can't distinguish a locked keychain from a
            // writable one (SecItemCopyMatching answers errSecItemNotFound either way); only a
            // real write reveals errSecInteractionNotAllowed on a locked/headless login
            // keychain (e.g. a non-interactive ssh session). Skip rather than fail when the
            // write is denied — this test asserts the round-trip only when the keychain is
            // genuinely writable (an interactive/unlocked session).
            if (!backend.store(account, secret)) return // locked / write-denied — skip, don't fail
            assertContentEquals(secret, backend.retrieve(account), "retrieve the same bytes")

            // Overwrite must go through the duplicate → update path.
            val secret2 = byteArrayOf(42, 42, 42)
            assertTrue(backend.store(account, secret2), "overwrite (update) an existing item")
            assertContentEquals(secret2, backend.retrieve(account))
        } finally {
            backend.remove(account)
            assertNull(backend.retrieve(account), "removed after the test")
        }
    }

    // ── real Linux libsecret / Secret Service (skips where unreachable) ──────────────

    /**
     * Exercises the actual libsecret / freedesktop Secret Service JNA binding on a Linux host
     * with a reachable Secret Service (gnome-keyring / KWallet on the session D-Bus): it seals a
     * random secret through the REAL backend, reads it back for a full round-trip, and asserts
     * the store reports the OS-backed [KeyTier.KEYCHAIN] tier (not the sealed-file fallback). It
     * also drives the overwrite (store-over-existing) and delete paths against a throwaway
     * account. Skips (returns) on any non-Linux host and whenever no Secret Service is reachable
     * (headless / no session bus / locked), so it never fails CI's runner and never leaves an
     * item behind.
     */
    @Test fun real_linux_libsecret_round_trips_when_reachable() {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!os.contains("linux")) return

        val backend = LibSecretBackend(service = "one.rarebit.heyarr.desktop.test")
        if (!backend.isAvailable()) return // no Secret Service reachable — skip, don't fail

        // The store over the real backend must report the OS-backed keychain tier.
        val store = KeychainSecretStore(backend)
        assertEquals(KeyTier.KEYCHAIN, store.tier)

        val account = "roundtrip-" + System.nanoTime()
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            assertFalse(store.exists(account), "clean start")
            assertNull(store.unseal(account), "nothing stored yet")

            store.seal(account, secret)
            assertTrue(store.exists(account), "present in the Secret Service after seal")
            assertContentEquals(secret, store.unseal(account), "same bytes back out of libsecret")

            // Overwrite must replace the stored secret in place.
            val secret2 = ByteArray(32).also { SecureRandom().nextBytes(it) }
            store.seal(account, secret2)
            assertContentEquals(secret2, store.unseal(account), "overwrite replaces the secret")
        } finally {
            store.delete(account)
            assertFalse(store.exists(account), "removed after the test")
            assertNull(store.unseal(account), "nothing left in the Secret Service")
        }
    }
}
