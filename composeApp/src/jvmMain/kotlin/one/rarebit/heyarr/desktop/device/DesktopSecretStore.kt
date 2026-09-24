package one.rarebit.heyarr.desktop.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals device secrets at rest on a plain desktop JVM — the desktop analog of the
 * Android [SealedSecretStore] (AES-256-GCM framing, `[iv][ct]` length-framed), but
 * wrapped by a locally-held random key instead of an AndroidKeyStore-held one.
 *
 * ## The design fork this resolves (no hardware enclave on desktop)
 *
 * A plain JVM has no Secure Enclave / StrongBox and no OS-user-presence gate, so a
 * device signing seed CANNOT be made non-exportable the way it is on a phone. The three
 * options considered (see the PR body / issue #32):
 *   (a) an OS keychain (macOS Keychain / libsecret / Windows DPAPI) via a native lib;
 *   (b) an encrypted file sealed by a locally-held key;
 *   (c) whatever the repo already does for desktop secrets.
 *
 * The repo's established posture (c) is 0600 files with "OS keyring later" explicitly
 * deferred — the bearer token is persisted plaintext in `config.json` (0600) with a TODO
 * to move it to libsecret/KWallet (see `settings/SettingsStore.kt`,
 * `ui/ConnectionSheet.kt`). This store is the consistent, simplest-secure choice: (b),
 * an AES-256-GCM sealed blob under `$XDG_DATA_HOME/heyarr-desktop/device/`, wrapped by a
 * 32-byte random key kept in a SEPARATE 0600 key file under `$XDG_CONFIG_HOME` — so the
 * two are not captured together by a careless copy/backup of one directory.
 *
 * ## Threat model — read honestly
 *
 * This is **at-rest protection and blast-radius reduction, not non-exportability.** It
 * defends the seed against: casual disk/backup exposure of the sealed blob alone, and
 * other OS users (file mode 0600 + the wrap key held apart from the ciphertext). It does
 * **NOT** defend against an attacker who already has code execution or read access AS
 * THIS OS USER — they can read the key file and re-derive the wrap key, exactly as this
 * process does. On a plain JVM that is unavoidable, so this store honestly reports
 * [KeyTier.SOFTWARE]. Option (a) — the OS-keychain-backed store — is now implemented in
 * [KeychainSecretStore] and preferred where a keychain is reachable; this sealed file is the
 * fallback when it is not.
 *
 * POSIX perms are best-effort (mirrors `FileSettingsStore`); ignored on a non-POSIX FS.
 *
 * This is the [SecretStore] fallback: always available, and the store the tests exercise.
 * When an OS keychain is present, [KeychainSecretStore] wraps this one as its migration
 * source so an enrolment created here is not lost on upgrade.
 */
class DesktopSecretStore(
    private val dir: File,
    private val keyFile: File,
    private val random: SecureRandom = SecureRandom(),
) : SecretStore {

    override val tier: KeyTier get() = KeyTier.SOFTWARE

    override fun exists(name: String): Boolean = file(name).exists()

    override fun seal(name: String, secret: ByteArray) {
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ct = cipher.doFinal(secret)
        writeFramed(file(name), iv, ct)
    }

    override fun unseal(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        val (iv, ct) = readFramed(f) ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ct)
            }
        }.getOrNull()
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    /** The locally-held 32-byte wrap key, loaded from [keyFile] or minted (0600) on first use. */
    private fun wrapKey(): SecretKeySpec {
        val existing = runCatching { if (keyFile.exists()) keyFile.readBytes() else null }.getOrNull()
        val raw = if (existing != null && existing.size == WRAP_KEY_BYTES) {
            existing
        } else {
            val fresh = ByteArray(WRAP_KEY_BYTES).also { random.nextBytes(it) }
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(fresh)
            tighten(keyFile)
            fresh
        }
        return SecretKeySpec(raw, "AES")
    }

    private fun file(name: String): File {
        dir.mkdirs()
        return File(dir, "secret.$name")
    }

    private fun writeFramed(f: File, iv: ByteArray, ct: ByteArray) {
        val out = ByteArrayOutputStream()
        fun put(b: ByteArray) {
            val n = b.size
            out.write(n ushr 24)
            out.write(n ushr 16)
            out.write(n ushr 8)
            out.write(n)
            out.write(b)
        }
        put(iv)
        put(ct)
        f.parentFile?.mkdirs()
        f.writeBytes(out.toByteArray())
        tighten(f)
    }

    private fun readFramed(f: File): Pair<ByteArray, ByteArray>? = runCatching {
        val bytes = f.readBytes()
        var i = 0
        fun take(): ByteArray {
            val n = ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            i += 4
            val slice = bytes.copyOfRange(i, i + n)
            i += n
            return slice
        }
        take() to take()
    }.getOrNull()

    private fun tighten(f: File) {
        runCatching {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val WRAP_KEY_BYTES = 32
    }
}
