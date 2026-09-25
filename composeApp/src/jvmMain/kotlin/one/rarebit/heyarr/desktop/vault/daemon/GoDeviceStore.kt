package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.core.net.JsonScan
import java.io.File

/**
 * A reader for the **voidbind-go** device store the `voidbind pair-join` CLI wrote at
 * `~/.config/voidbind/device/` — NOT the Kotlin [one.rarebit.heyarr.desktop.device.DesktopDeviceKeyring]
 * format.
 *
 * This is the deliberate custody coupling for the headless daemon (OPTION 1, no phone gate): the
 * target box was enrolled with the Go CLI, and the CLI-created vault space is wrapped to THIS
 * store's X25519 enc key. So rather than mint a second Kotlin identity, the daemon reuses the Go
 * store directly — it reads the plaintext-hex enc seed here (to unwrap the space key) and shells out
 * to the same `voidbind` CLI for the controller credential ([VoidbindCliCredential]). This shim
 * therefore ties the daemon to the Go voidbind store + CLI on the host; that is by design.
 *
 * Store layout (as `pair-join` writes it, all files 0600):
 * ```
 * device.json           {"id":…, "public_key":"ed25519:<hex>", "encryption_key":"x25519:<hex>", …}
 * device_ed25519.key    voidbind-device-ed25519-seed:<64 hex>   (the signing seed)
 * device_x25519.key     voidbind-device-x25519-seed:<64 hex>    (the enc seed — unwraps the space key)
 * enrolment.cert        the admitting op (base64url)
 * ops.jsonl             the membership log
 * ```
 *
 * The seed is a secret: it is read into memory to unwrap the space key and never logged, echoed, or
 * written anywhere.
 */
class GoDeviceStore(private val dir: File) {

    /** The X25519 enc PRIVATE seed (32 bytes) — the key that unwraps this space's wrapped copy. */
    fun encSeed(): ByteArray = readSeed(File(dir, ENC_KEY_FILE), ENC_SEED_PREFIX)

    /** This device's enc key ref `x25519:<hex>` — the recipient to match in the space's wrapped-key list. */
    fun encKeyRef(): String = metaField("encryption_key")

    /** This device's signing key ref `ed25519:<hex>` — the status file's `device` field. */
    fun deviceKeyRef(): String = metaField("public_key")

    /** This device's id (a UUIDv7 the CLI assigned). */
    fun deviceId(): String = metaField("id")

    private fun metaField(key: String): String {
        val meta = File(dir, META_FILE)
        require(meta.exists()) {
            "voidbind device store not found at ${meta.path} — is this box enrolled via `voidbind pair-join`?"
        }
        return JsonScan.stringField(meta.readText(), key)?.takeIf { it.isNotBlank() }
            ?: error("voidbind $META_FILE is missing '$key'")
    }

    private fun readSeed(file: File, prefix: String): ByteArray {
        require(file.exists()) { "voidbind seed file not found at ${file.path}" }
        val line = file.readText().trim()
        val marker = "$prefix:"
        require(line.startsWith(marker)) { "unexpected format in ${file.name}: missing '$marker' prefix" }
        val hex = line.removePrefix(marker).trim()
        require(hex.length == 64) { "expected a 32-byte (64 hex) seed in ${file.name}, got ${hex.length} chars" }
        return hexToBytes(hex)
    }

    companion object {
        private const val META_FILE = "device.json"
        private const val ENC_KEY_FILE = "device_x25519.key"
        private const val ENC_SEED_PREFIX = "voidbind-device-x25519-seed"

        /** Hex → bytes. Rejects odd length / non-hex, so a truncated seed fails loudly, not silently. */
        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "hex string has odd length" }
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                require(hi >= 0 && lo >= 0) { "non-hex character in seed" }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
