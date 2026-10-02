package one.rarebit.heyarr.mobile.device

import one.rarebit.voidwhichbinds.Membership
import one.rarebit.voidwhichbinds.crypto.MiniJson
import java.io.File

/**
 * Where this phone's device state lives on disk, under the app's `filesDir`. Pure
 * `java.io` (no Android imports), so the layout is unit-tested on the JVM.
 *
 * **Gen2 namespace (void-which-binds-go ADR-0022).** The app-owned state — the sealed
 * X25519 enc key, the admission (`cert.<alias>.token`, the admitting op), the replica
 * (`ops.<alias>.json`) and the recovery recipient — lives under [STATE_DIR]
 * (`heyarr-device.vwb/`), and the library's sealed signing key under
 * [LIBRARY_KEY_DIR] (`void-which-binds/`, void-which-binds-client 0.11.0). The gen1
 * dirs (`heyarr-device/`, `voidbind/`) are simply never read: there is no migration and
 * no detect-and-clear. A phone upgraded over a gen1 build therefore starts unprovisioned
 * and pairs in fresh — and at the cutover (ADR-0022 C2 step 8) the phones are reset
 * anyway. The gen1 files left behind are inert.
 */
class DeviceStateFiles(private val filesDir: File, private val alias: String) {

    /** The app-owned state dir, created on first use. */
    fun dir(): File = File(filesDir, STATE_DIR).apply { mkdirs() }

    fun encPubFile() = File(dir(), "enc.$alias.pub")
    fun certFile() = File(dir(), "cert.$alias.token")
    fun opsFile() = File(dir(), "ops.$alias.json")
    fun recoveryFile() = File(dir(), "recovery.$alias.pub")

    /** A sealed secret's file ([SealedSecretStore]); same gen2 dir. */
    fun secretFile(name: String) = File(dir(), "secret.$name")

    /** True once the library's sealed signing key exists (no prompt to check). */
    fun isProvisioned(): Boolean = File(File(filesDir, LIBRARY_KEY_DIR), "$alias.key").exists()

    /** This device's admitting op (the credential token), once paired in. */
    fun certToken(): String? = certFile().takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /**
     * The membership ops this device knows, in hash order. Always includes the admitting
     * op itself, so a replica written with the cert only still presents.
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

    companion object {
        /** The app-owned gen2 state dir. Gen1's was `heyarr-device`, never read. */
        const val STATE_DIR = "heyarr-device.vwb"

        /** void-which-binds-client 0.11.0's sealed-key dir (`DeviceKeyStore`). Gen1's was `voidbind`. */
        const val LIBRARY_KEY_DIR = "void-which-binds"

        private const val OPS_KEY = "ops"
    }
}
