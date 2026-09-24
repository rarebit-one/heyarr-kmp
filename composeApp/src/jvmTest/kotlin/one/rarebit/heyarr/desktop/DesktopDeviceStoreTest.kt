package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.device.DesktopDeviceKeyring
import one.rarebit.heyarr.desktop.device.DesktopEd25519
import one.rarebit.heyarr.desktop.device.DesktopSecretStore
import one.rarebit.heyarr.desktop.device.KeyTier
import one.rarebit.heyarr.desktop.device.KeychainSecretStore
import one.rarebit.voidbind.Ed25519Verifier
import one.rarebit.voidbind.auth.DeviceCredential
import one.rarebit.voidbind.auth.PossessionProof
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDeviceStoreTest {

    private val tmp: File = Files.createTempDirectory("heyarr-device-test").toFile()

    @AfterTest fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun keyFile() = File(tmp, "device.key")
    private fun dataDir() = File(tmp, "device")

    /** A hermetic sealed-file store on the same paths the keyring's default would use — so
     *  these tests never touch a real OS keychain on a dev/build machine that has one. */
    private fun sealedStore(dataDir: File = dataDir()) = DesktopSecretStore(File(dataDir, "sealed"), keyFile())

    // ── DesktopSecretStore: seal/unseal round-trips, survives a fresh instance ──────

    @Test fun seal_unseal_round_trips() {
        val store = DesktopSecretStore(File(tmp, "sealed"), keyFile())
        val secret = byteArrayOf(1, 2, 3, 4, 5, 42, -7, 99)
        assertTrue(!store.exists("k"))
        store.seal("k", secret)
        assertTrue(store.exists("k"))
        assertContentEquals(secret, store.unseal("k"))
    }

    @Test fun sealed_secret_survives_a_new_store_instance_over_the_same_key_file() {
        val sealedDir = File(tmp, "sealed")
        val secret = "the-seed-bytes".encodeToByteArray()
        DesktopSecretStore(sealedDir, keyFile()).seal("s", secret)
        // A brand-new store (a relaunch) with the same paths reloads the wrap key and decrypts.
        val reopened = DesktopSecretStore(sealedDir, keyFile())
        assertContentEquals(secret, reopened.unseal("s"))
    }

    @Test fun unseal_missing_secret_is_null() {
        val store = DesktopSecretStore(File(tmp, "sealed"), keyFile())
        assertNull(store.unseal("absent"))
    }

    // ── DesktopDeviceKeyring: provisioning + persistence, no admission → no credential ──

    @Test fun keyring_provisions_once_and_reloads_the_same_device_key() {
        val ring = DesktopDeviceKeyring(dataDir(), keyFile(), secrets = sealedStore())
        assertTrue(!ring.isProvisioned())
        assertNull(ring.peek())

        val info = ring.info() // provisions
        assertTrue(ring.isProvisioned())
        assertTrue(info.deviceKey.startsWith("ed25519:"), "device key is an ed25519 ref")
        assertTrue(info.deviceEncKey.startsWith("x25519:"), "enc key is an x25519 ref")
        assertNull(info.certToken)
        assertTrue(!info.isEnrolled)
        assertEquals(KeyTier.SOFTWARE, info.tier, "sealed-file store reports the SOFTWARE tier")

        // A relaunch (fresh keyring, same paths) loads the SAME key — the enrolment survives.
        val reloaded = DesktopDeviceKeyring(dataDir(), keyFile(), secrets = sealedStore())
        assertEquals(info.deviceKey, reloaded.info().deviceKey)
        assertEquals(info.deviceEncKey, reloaded.info().deviceEncKey)
    }

    @Test fun no_admission_means_no_device_credential() {
        val ring = DesktopDeviceKeyring(dataDir(), keyFile(), secrets = sealedStore())
        ring.info() // provisioned but never paired
        assertNull(ring.deviceCredential())
    }

    @Test fun keyring_over_a_keychain_store_reports_the_keychain_tier() {
        val backend = object : one.rarebit.heyarr.desktop.device.KeychainBackend {
            val items = HashMap<String, ByteArray>()
            override val label = "fake"
            override fun isAvailable() = true
            override fun store(account: String, secret: ByteArray): Boolean {
                items[account] = secret.copyOf()
                return true
            }
            override fun retrieve(account: String): ByteArray? = items[account]?.copyOf()
            override fun remove(account: String) {
                items.remove(account)
            }
        }
        val ring = DesktopDeviceKeyring(dataDir(), keyFile(), secrets = KeychainSecretStore(backend))
        assertEquals(KeyTier.KEYCHAIN, ring.info().tier)
    }

    // ── DesktopEd25519 is wire-compatible with voidbind's verifier ──────────────────

    @Test fun a_possession_proof_signed_by_the_desktop_key_verifies_against_voidbinds_checker() {
        val generated = DesktopEd25519.generate()
        val signer = DesktopEd25519.signer(generated.seed)
        val cert = "opbody.opsig" // a stand-in cert token; the proof binds its hash
        val now = 1_700_000_000L

        val credential = DeviceCredential(cert, signer, clock = { now })
        val presentation = credential.current()
        assertEquals(cert, presentation.cert)

        // Verify EXACTLY as heyarr-core does, through voidbind's PossessionProof.verify,
        // with the desktop public key + a verifier backed by the desktop engine. If the
        // RAW seed/pubkey/signature bytes did not match voidbind's own engine, this fails.
        val verifier = Ed25519Verifier { pub, msg, sig -> DesktopEd25519.verify(pub, msg, sig) }
        val payload = PossessionProof.verify(presentation.proof, generated.publicKey, cert, now, verifier)
        assertNotNull(payload)
        assertEquals(PossessionProof.VERSION, payload.version)
    }
}
