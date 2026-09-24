package one.rarebit.heyarr.mobile.device

import android.content.Context
import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.DeviceKeyStore
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.VoidbindAndroid
import one.rarebit.voidbind.crypto.MiniJson
import one.rarebit.voidbind.net.Admission
import java.io.File

/** The honest hardware tier of the device signing key's wrapping key (never over-stated). */
enum class KeyTier { STRONGBOX, TEE, SOFTWARE }

/** What this device holds, for the enrol screen and the read-only banner. */
data class DeviceKeyInfo(
    /** `ed25519:<hex>` — the key an operator names to authorise this device. */
    val deviceKey: String,
    /** `x25519:<hex>` — the encryption key a pairing add op binds as `denc`. */
    val deviceEncKey: String,
    val tier: KeyTier,
    /**
     * This device's **admitting op** — the membership op (voidbind-kmp ADR-0005) that
     * added it, presented as the credential token in the `Device` scheme. A v1/v2
     * cert from an earlier pairing IS a genesis add and is kept as-is.
     */
    val certToken: String?,
    /** The identity (`ed25519:<hex>`, the genesis key) the admitting op belongs to. */
    val userId: String? = null,
    /** The membership ops this device knows — its replica (the admission's `ops`, plus what the node served). */
    val knownOps: List<String> = emptyList(),
) {
    val isEnrolled: Boolean get() = certToken != null
}

/**
 * This phone's Voidbind device keys, persisted **sealed** (voidbind-kmp ADR-0001):
 *
 *  - the **Ed25519 signing key** lives in voidbind-client's hardware [DeviceKeyStore]
 *    — a software seed sealed by a non-extractable, user-auth-gated AES-GCM key in
 *    the AndroidKeyStore (StrongBox where present, TEE otherwise). It is generated on
 *    first run and never exported; every possession proof is signed through it.
 *  - the **X25519 encryption key** (which no secure element can hold) is sealed at
 *    rest by [SealedSecretStore]; its public half is stored plain. It is what unseals
 *    an admission delivered over the pairing relay.
 *  - the **admission** (ADR-0005): the public, member-signed **add op** that admitted
 *    this device (the credential token) and the **ops** that authorise it (this
 *    device's replica of the identity's membership log), both stored plain once
 *    pairing completes. The replica grows as the node serves more ops
 *    (`GET /membership/{usr}`), which is how the device learns it was removed.
 *
 * Keystore calls that need a fresh user authentication (first provisioning, and
 * every signature) go through the [BiometricGate]; run this off the main thread.
 */
class DeviceKeyring(
    context: Context,
    private val gate: BiometricGate,
    private val alias: String = DEFAULT_ALIAS,
) {
    private val app = context.applicationContext
    private val secrets = SealedSecretStore(app)

    init {
        VoidbindAndroid.init(app)
    }

    private fun dir(): File = File(app.filesDir, "heyarr-device").apply { mkdirs() }
    private fun encPubFile() = File(dir(), "enc.$alias.pub")
    private fun certFile() = File(dir(), "cert.$alias.token")
    private fun opsFile() = File(dir(), "ops.$alias.json")
    private fun recoveryFile() = File(dir(), "recovery.$alias.pub")

    /** True once the sealed signing key exists on this phone (no prompt to check). */
    fun isProvisioned(): Boolean = File(File(app.filesDir, "voidbind"), "$alias.key").exists()

    /**
     * The device info WITHOUT provisioning: null on a fresh install. Loading existing
     * sealed keys needs no user presence (only signing / first creation do), so this is
     * safe to call at start-up without surfacing a biometric prompt.
     */
    fun peek(): DeviceKeyInfo? = if (isProvisioned()) info() else null

    /**
     * Open (or on first run, provision) the signing key. May prompt for user presence.
     *
     * Provisioned with a **1-hour** user-authentication window
     * ([USER_AUTH_VALIDITY_SECONDS], voidbind-client 0.6.0's
     * `setUserAuthenticationParameters` timeout): one biometric authorises this key to
     * sign silently for the next hour, so the app mints short (default 2 min) possession
     * proofs and re-mints per request/batch without a fingerprint every couple of
     * minutes — the cadence heyarr-core#444 (restoring ADR-0070's 10 min ceiling)
     * intended. The window is baked in at creation and cannot be changed on an existing
     * key, so this is a **new alias** ([DEFAULT_ALIAS]): the seed is namespaced per alias
     * (voidbind-client `DeviceKeyStore`), so this key's **public key is new** and the
     * phone **re-enrols** through the existing pairing flow (Path A / heyarr-core#444 —
     * the previous 30 s-window key is left on disk unused, and its old member is removed
     * operationally in Cruciform). The app never signs an *authorising* act (it is only
     * ever the joining device in a pairing, mints possession proofs, and enrols), so no
     * separate strict-window alias is needed.
     */
    fun keyStore(): DeviceKeyStore = gate.gated("Set up this device", "Confirm it's you to create the device key") {
        DeviceKeyStore.getOrCreate(alias, userAuthValiditySeconds = USER_AUTH_VALIDITY_SECONDS)
    }

    /** The sealed X25519 keypair, generated once on first use. */
    fun encryptionKey(): DeviceIdentity.EncryptionKey {
        val priv = secrets.unseal(ENC_SECRET)
        val pubFile = encPubFile()
        if (priv != null && pubFile.exists()) {
            return DeviceIdentity.EncryptionKey(priv, pubFile.readBytes())
        }
        val fresh = DeviceIdentity.generateEncryptionKey()
        secrets.seal(ENC_SECRET, fresh.privateKey)
        pubFile.writeBytes(fresh.publicKey)
        return fresh
    }

    /** The full device identity: hardware signer + enc keypair. */
    fun identity(): DeviceIdentity {
        val ks = keyStore()
        val enc = encryptionKey()
        return DeviceIdentity(ks.publicKey().bytes, enc.publicKey, enc.privateKey) { message ->
            gate.gated("Sign with device key", "Confirm it's you to prove possession") { ks.sign(message) }
        }
    }

    /** The honest tier of the wrapping key backing the signing key. */
    fun tier(ks: DeviceKeyStore): KeyTier = when (ks.securityLevel()) {
        DeviceKeyStore.SecurityLevel.STRONGBOX -> KeyTier.STRONGBOX
        DeviceKeyStore.SecurityLevel.TEE -> KeyTier.TEE
        DeviceKeyStore.SecurityLevel.SOFTWARE -> KeyTier.SOFTWARE
    }

    /** This device's admitting op (the credential token), once paired in. */
    fun certToken(): String? = certFile().takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** The identity the admitting op claims (`ed25519:<hex>`), or null before enrolment. */
    fun userId(): String? = certToken()?.let { runCatching { MembershipOp.user(it) }.getOrNull() }

    /**
     * The membership ops this device knows, in hash order. Always includes the admitting
     * op itself, so a replica written by an older build (cert only) still presents.
     */
    fun knownOps(): List<String> {
        val stored = opsFile().takeIf { it.exists() }?.let { f ->
            runCatching {
                (MiniJson.parseObject(f.readText())[OPS_KEY] as? List<*>)?.map { it as String }
            }.getOrNull()
        } ?: emptyList()
        val own = certToken()?.let { listOf(it) } ?: emptyList()
        return Membership.merge(stored, own)
    }

    /** Replace the replica (merged with the admitting op so it can never be dropped). */
    fun saveOps(ops: List<String>) {
        val own = certToken()?.let { listOf(it) } ?: emptyList()
        opsFile().writeText(MiniJson.encodeObject(listOf(OPS_KEY to Membership.merge(ops, own))))
    }

    /**
     * Persist a delivered [Admission] after checking its op names THIS device's keys —
     * an op for another device would be refused by the server anyway, but storing it
     * would wedge the app into a Device credential that never works. Both halves are
     * kept: [Admission.op] is the credential token, [Admission.ops] the replica.
     */
    fun saveAdmission(admission: Admission) {
        val parsed = MembershipOp.verify(admission.op)
        val info = info()
        require(parsed.kind == MembershipOp.Kind.ADD) { "admission is a ${parsed.kind.wire}, not an add" }
        require(parsed.device == info.deviceKey) { "admission binds a different device key" }
        require(parsed.deviceEnc == info.deviceEncKey) { "admission binds a different encryption key" }
        certFile().writeText(admission.op)
        saveOps(admission.ops)
    }

    /**
     * The identity's recovery encryption **public** key (`x25519:<hex>`), persisted PLAIN
     * from the enrolment response (issue #41 part 2), or null if none was provisioned. It
     * is a recipient a new space is also wrapped for, so state survives losing every
     * device; it is a public key only — no secret is ever stored here.
     */
    fun recoveryRecipient(): String? = recoveryFile().takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Persist the recovery encryption public key delivered by `/enrol` (a blank value clears it). */
    fun saveRecoveryRecipient(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) recoveryFile().delete() else recoveryFile().writeText(trimmed)
    }

    /** Forget the admission (the keys stay — re-pairing re-uses them). */
    fun clearCert() {
        certFile().delete()
        opsFile().delete()
        recoveryFile().delete()
    }

    /** Snapshot for the UI. Provisions the keys on first call. */
    fun info(): DeviceKeyInfo {
        val ks = keyStore()
        val enc = encryptionKey()
        val cert = certToken()
        return DeviceKeyInfo(
            deviceKey = ks.publicKey().render(),
            deviceEncKey = KeyRef.x25519(enc.publicKey).render(),
            tier = tier(ks),
            certToken = cert,
            userId = cert?.let { runCatching { MembershipOp.user(it) }.getOrNull() },
            knownOps = if (cert != null) knownOps() else emptyList(),
        )
    }

    companion object {
        /**
         * The signing-key alias. The `.authorising` suffix (voidbind-client's documented
         * convention for a long-window key) marks this as the 1-hour-window key introduced
         * for heyarr-core#444: a *distinct* alias from the original `heyarr-device`, so it
         * is a new sealed seed with a new public key and the phone re-enrols (Path A).
         */
        const val DEFAULT_ALIAS = "heyarr-device.authorising"

        /**
         * The user-authentication validity window for the signing key's wrapping key
         * (seconds). 1 hour: one biometric covers an hour of short possession-proof
         * minting. Strict per-signature biometric is reserved for authorising acts, which
         * this app does not perform — see [keyStore].
         */
        const val USER_AUTH_VALIDITY_SECONDS = 60 * 60

        private const val ENC_SECRET = "enc"
        private const val OPS_KEY = "ops"
    }
}
