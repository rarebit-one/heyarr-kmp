package one.rarebit.heyarr.desktop.device

import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.auth.DeviceCredential
import one.rarebit.voidbind.crypto.MiniJson
import one.rarebit.voidbind.net.Admission
import java.io.File

/**
 * The honest protection tier of the desktop device key.
 *
 *  - [STRONGBOX] / [TEE] — hardware-backed (phones). Never reported on a plain JVM.
 *  - [KEYCHAIN] — the seed lives in the OS keychain (macOS Keychain / libsecret Secret
 *    Service), not on disk: OS-owned storage + access control ([KeychainSecretStore]).
 *  - [SOFTWARE] — an AES-256-GCM file sealed by a locally-held key ([DesktopSecretStore]):
 *    at-rest protection only. The fallback when no keychain is reachable.
 */
enum class KeyTier { STRONGBOX, TEE, KEYCHAIN, SOFTWARE }

/** What this desktop holds, for the enrol UI. Mirrors heyarr-mobile's `DeviceKeyInfo`. */
data class DeviceKeyInfo(
    /** `ed25519:<hex>` — the key an operator names in Cruciform to authorise this device. */
    val deviceKey: String,
    /** `x25519:<hex>` — the encryption key a pairing add op binds as `denc`. */
    val deviceEncKey: String,
    val tier: KeyTier,
    /** This device's admitting op (the credential token), or null before enrolment. */
    val certToken: String?,
    /** The identity (`ed25519:<hex>`) the admitting op belongs to. */
    val userId: String? = null,
    /** The membership ops this device knows — the admission's `ops` plus its own admitting op. */
    val knownOps: List<String> = emptyList(),
) {
    val isEnrolled: Boolean get() = certToken != null
}

/**
 * This desktop's Voidbind device keys and admission, persisted under
 * `$XDG_DATA_HOME/heyarr-desktop/device/` — the desktop analog of heyarr-mobile's
 * `DeviceKeyring`, minus the biometric gate and the hardware keystore:
 *
 *  - the **Ed25519 signing key** — seed sealed at rest by [DesktopSecretStore], its
 *    public half stored plain. Generated once on first use; reconstructed into an
 *    [one.rarebit.voidbind.Ed25519Signer] via [DesktopEd25519] each launch. (No secure
 *    element on desktop — see [DesktopSecretStore] for the threat model.)
 *  - the **X25519 encryption key** — private half sealed, public half plain. Unseals the
 *    admission delivered over the pairing relay.
 *  - the **admission** ([Admission]): the member-signed add op that admitted this device
 *    (the credential token) plus the `ops` that authorise it (this device's replica of
 *    the identity's membership log), both stored plain once pairing completes.
 *
 * Construction does NO I/O — it only computes paths — so building one in a preview / test
 * that never enrols touches no disk and reports [peek] == null.
 */
class DesktopDeviceKeyring(
    private val dataDir: File = defaultDataDir(),
    keyFile: File = defaultKeyFile(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Where the signing seed / encryption key are persisted. Defaults to the platform pick
     * (OS keychain where reachable, else the sealed file) — the availability probe is deferred
     * to first use, so constructing a keyring stays I/O-free. Tests inject a
     * [DesktopSecretStore] for a hermetic sealed-file path.
     */
    private val secrets: SecretStore = SecretStores.forDevice(File(dataDir, "sealed"), keyFile),
) {

    private fun signPubFile() = File(dataDir, "sign.pub")
    private fun encPubFile() = File(dataDir, "enc.pub")
    private fun certFile() = File(dataDir, "cert.token")
    private fun opsFile() = File(dataDir, "ops.json")
    private fun recoveryFile() = File(dataDir, "recovery.pub")

    // ── provisioning (generate-once, load-thereafter) ────────────────────────────

    /** True once the sealed signing seed + its public half exist. */
    fun isProvisioned(): Boolean = signPubFile().exists() && secrets.exists(SIGN_SEED)

    /** The device info WITHOUT provisioning — null on a fresh desktop. No side effects. */
    fun peek(): DeviceKeyInfo? = if (isProvisioned()) info() else null

    /** The raw signing seed, generating + sealing it on first use. */
    private fun signSeed(): ByteArray {
        val sealed = secrets.unseal(SIGN_SEED)
        val pubFile = signPubFile()
        if (sealed != null && pubFile.exists()) return sealed
        val fresh = DesktopEd25519.generate()
        secrets.seal(SIGN_SEED, fresh.seed)
        dataDir.mkdirs()
        pubFile.writeBytes(fresh.publicKey)
        return fresh.seed
    }

    private fun signPublicKey(): ByteArray {
        signSeed() // ensures provisioned
        return signPubFile().readBytes()
    }

    /** The sealed X25519 encryption keypair, generated once on first use. */
    private fun encryptionKey(): DeviceIdentity.EncryptionKey {
        val priv = secrets.unseal(ENC_SECRET)
        val pubFile = encPubFile()
        if (priv != null && pubFile.exists()) return DeviceIdentity.EncryptionKey(priv, pubFile.readBytes())
        val fresh = DeviceIdentity.generateEncryptionKey()
        secrets.seal(ENC_SECRET, fresh.privateKey)
        dataDir.mkdirs()
        pubFile.writeBytes(fresh.publicKey)
        return fresh
    }

    /** The full device identity: software signer over the sealed seed + the enc keypair. */
    fun identity(): DeviceIdentity {
        val seed = signSeed()
        val pub = signPubFile().readBytes()
        val enc = encryptionKey()
        return DeviceIdentity(pub, enc.publicKey, enc.privateKey) { message -> DesktopEd25519.sign(seed, message) }
    }

    // ── admission (cert + ops) ───────────────────────────────────────────────────

    /** This device's admitting op (the credential token), once paired in. */
    fun certToken(): String? = certFile().takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** The identity the admitting op claims (`ed25519:<hex>`), or null before enrolment. */
    fun userId(): String? = certToken()?.let { runCatching { MembershipOp.user(it) }.getOrNull() }

    /** The membership ops this device knows, in hash order (always includes its own admitting op). */
    fun knownOps(): List<String> {
        val stored = opsFile().takeIf { it.exists() }?.let { f ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (MiniJson.parseObject(f.readText())[OPS_KEY] as? List<*>)?.map { it as String }
            }.getOrNull()
        } ?: emptyList()
        val own = certToken()?.let { listOf(it) } ?: emptyList()
        return Membership.merge(stored, own)
    }

    /** Replace the replica (merged with the admitting op so it can never be dropped). */
    fun saveOps(ops: List<String>) {
        val own = certToken()?.let { listOf(it) } ?: emptyList()
        dataDir.mkdirs()
        opsFile().writeText(MiniJson.encodeObject(listOf(OPS_KEY to Membership.merge(ops, own))))
    }

    /**
     * Persist a delivered [Admission] after checking its op names THIS device's keys —
     * an op for another device would only wedge the app into a Device credential the
     * node never accepts. Both halves are kept: the op is the credential token, the ops
     * the replica.
     */
    fun saveAdmission(admission: Admission) {
        val parsed = MembershipOp.verify(admission.op)
        val self = info()
        require(parsed.kind == MembershipOp.Kind.ADD) { "admission is a ${parsed.kind.wire}, not an add" }
        require(parsed.device == self.deviceKey) { "admission binds a different device key" }
        require(parsed.deviceEnc == self.deviceEncKey) { "admission binds a different encryption key" }
        dataDir.mkdirs()
        certFile().writeText(admission.op)
        saveOps(admission.ops)
    }

    /** Forget the admission (the keys stay — re-pairing re-uses them). */
    fun clearCert() {
        certFile().delete()
        opsFile().delete()
    }

    // ── recovery recipient ─────────────────────────────────────────────────────────

    /**
     * The identity's recovery encryption PUBLIC key (`x25519:<hex>`), persisted plain by
     * [saveRecoveryRecipient] when enrolment delivered one, or null. A new vault space is
     * wrapped for it too (ADR-0022/0049) so the paper recovery secret can open the space —
     * the desktop analog of heyarr-mobile's `DeviceKeyring.recoveryRecipient()`. Absent →
     * the honest degraded default: the space is wrapped for THIS device only.
     */
    fun recoveryRecipient(): String? =
        recoveryFile().takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Persist the recovery encryption public key delivered by `/enrol` (a blank value clears it). */
    fun saveRecoveryRecipient(key: String) {
        val trimmed = key.trim()
        dataDir.mkdirs()
        if (trimmed.isEmpty()) recoveryFile().delete() else recoveryFile().writeText(trimmed)
    }

    // ── the live credential ──────────────────────────────────────────────────────

    /**
     * A [DeviceCredential] over the persisted admitting op, or null when this desktop is
     * not (yet) enrolled. Each call builds a fresh instance bound to the current signer;
     * `current()` on it mints + reuses a short possession proof (software-signed, no
     * prompt on desktop).
     */
    fun deviceCredential(): DeviceCredential? {
        val cert = certToken() ?: return null
        val signer = identity().asSigner()
        return DeviceCredential(cert, signer, clock)
    }

    // ── UI snapshot ──────────────────────────────────────────────────────────────

    /** Snapshot for the UI. Provisions the keys on first call. */
    fun info(): DeviceKeyInfo {
        val signPub = signPublicKey()
        val enc = encryptionKey()
        val cert = certToken()
        return DeviceKeyInfo(
            deviceKey = KeyRef.ed25519(signPub).render(),
            deviceEncKey = KeyRef.x25519(enc.publicKey).render(),
            tier = secrets.tier,
            certToken = cert,
            userId = cert?.let { runCatching { MembershipOp.user(it) }.getOrNull() },
            knownOps = if (cert != null) knownOps() else emptyList(),
        )
    }

    companion object {
        private const val SIGN_SEED = "sign"
        private const val ENC_SECRET = "enc"
        private const val OPS_KEY = "ops"

        /** `$XDG_DATA_HOME/heyarr-desktop/device`, else `~/.local/share/heyarr-desktop/device`. */
        fun defaultDataDir(): File {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".local/share")
            return File(File(base, "heyarr-desktop"), "device")
        }

        /**
         * The wrap key lives apart from the sealed material, under the CONFIG dir:
         * `$XDG_CONFIG_HOME/heyarr-desktop/device.key`, else `~/.config/heyarr-desktop/device.key`.
         */
        fun defaultKeyFile(): File {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".config")
            return File(File(base, "heyarr-desktop"), "device.key")
        }
    }
}
