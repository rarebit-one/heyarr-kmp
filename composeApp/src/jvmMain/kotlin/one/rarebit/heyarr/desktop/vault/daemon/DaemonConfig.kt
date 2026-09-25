package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.core.net.JsonScan
import java.io.File
import java.nio.file.Path

/**
 * The headless vault-sync daemon's configuration (W4, HEADLESS target).
 *
 * The daemon is a **Dropbox-style background sync process** — no Compose GUI — that runs the
 * already-merged W4 sync engine ([one.rarebit.heyarr.desktop.vault.VaultSyncEngine]) on a JVM box
 * so a Linux laptop gets true bidirectional live sync. This value object is everything it needs to
 * know before it can start: WHICH folder mirrors WHICH vault space on WHICH controller, WHERE the
 * device custody lives, and WHERE it exposes its status file + control socket.
 *
 * Resolution precedence (last wins): built-in defaults → the JSON config file → environment
 * variables → command-line args. Every field has a sensible default except [folder], which has no
 * safe default (there is no "obvious" folder to sync), so a daemon started without one stays in the
 * `preparing` phase and says so, rather than guessing.
 *
 * The status-file and control-socket paths default to the EXACT contract locations consumed by the
 * already-merged homelab-ops MCP + Omarchy plugin (`~/.cache/vault-sync.json`,
 * `~/.cache/vault-sync.sock`) — do not change these defaults without updating that consumer.
 */
data class DaemonConfig(
    /** The local folder this machine mirrors, or null when none is configured yet. */
    val folder: String? = null,
    /** The vault space this folder is bound to (a CLI-created space, wrapped to the Go device store). */
    val spaceId: String = DEFAULT_SPACE_ID,
    /** The heyarr controller (node) base URL the encrypted state-sync pipe talks to. */
    val controller: String = DEFAULT_CONTROLLER,
    /**
     * The voidbind-go device store this daemon reuses for custody (OPTION 1, no phone gate): the
     * dir `voidbind pair-join` wrote (plaintext-hex X25519 seed + `device.json` + the enrolment
     * cert). The daemon deliberately couples to this Go store + the `voidbind` CLI on the host.
     */
    val deviceDir: String = DEFAULT_DEVICE_DIR,
    /**
     * A WRITE-scoped API bearer token (literal). A headless writer NEEDS one: an enrolled device
     * credential authenticates only at the READ FLOOR (ADR-0067), so it cannot POST personal-state
     * (the vault's encrypted changes) — the first write 403s. When a token is resolved, it is the
     * API-write credential for the vault clients; the Go store ([deviceDir]) stays the CUSTODY key
     * (it unwraps the space key, independent of API auth). Null → fall back to the device credential.
     */
    val token: String? = null,
    /**
     * A file holding the write token (read + trimmed), for when it lives on disk rather than inline.
     * Overridden by [token]; when neither is set, `~/.config/heyarr/cli.token` is used IF it exists.
     */
    val tokenFile: String? = null,
    /** The periodic safety-net cadence: run a pass at least this often even with no watch event. */
    val pollMs: Long = 30_000,
    /** How long to wait before retrying custody resolution when the node/device isn't ready yet. */
    val retryMs: Long = 15_000,
    /** The atomically-written status file the plugin/MCP polls. */
    val statusFile: Path = defaultStatusFile(),
    /** The unix control socket (mode 0600) the plugin/MCP drives with newline-delimited JSON. */
    val socketPath: Path = defaultSocketPath(),
    /**
     * The device-local sync-index file (`path → synced hash/blob/size/mtime`), or null to use
     * [FileSyncIndexStore]'s own default. The index is what makes deletes safe and backs the
     * change gate, so it is PER-VAULT state: two daemon instances syncing different folders/spaces
     * MUST NOT share one index (a shared index would treat the other vault's files as remote
     * deletes). [FileSyncIndexStore]'s default keys only off `XDG_CONFIG_HOME`, so before this
     * override the only way to separate two instances was the `XDG_CONFIG_HOME` hack; set this
     * (per-vault template unit) instead.
     */
    val indexFile: String? = null,
) {
    /**
     * The effective API write token, or null when none is configured (→ fall back to the device
     * credential). Precedence: the inline [token] → the [tokenFile] → the default
     * `~/.config/heyarr/cli.token` IF it exists. [readFile] is injectable for tests.
     */
    fun resolveApiToken(readFile: (String) -> String? = { readTextOrNull(it) }): String? {
        token?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val path = tokenFile?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_FILE
        return readFile(path)?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** The CLI-created vault space this box is enrolled for (wrapped to the Go store's enc key). */
        const val DEFAULT_SPACE_ID = "01a0ae3b-ca68-7165-9dbb-527425e2f380"
        const val DEFAULT_CONTROLLER = "https://heyarr.br.thesim.family:7777"

        private fun home() = System.getProperty("user.home") ?: "."

        /** `~/.config/voidbind/device` — where the Go `voidbind pair-join` CLI writes its store. */
        val DEFAULT_DEVICE_DIR: String get() = File(File(home(), ".config"), "voidbind/device").path

        /** `~/.config/heyarr/cli.token` — the heyarr CLI's write token; used only if it exists. */
        val DEFAULT_TOKEN_FILE: String get() = File(File(home(), ".config"), "heyarr/cli.token").path

        /** `~/.cache/vault-sync.json` — the literal contract path (not XDG-relocated, so the plugin agrees). */
        fun defaultStatusFile(): Path = File(File(home(), ".cache"), "vault-sync.json").toPath()

        /** `~/.cache/vault-sync.sock` — the literal contract path. */
        fun defaultSocketPath(): Path = File(File(home(), ".cache"), "vault-sync.sock").toPath()

        /** `~/.config/heyarr-vault-sync/config.json` — the default JSON config file. */
        fun defaultConfigFile(): File = File(File(File(home(), ".config"), "heyarr-vault-sync"), "config.json")

        /** Read a file's text, or null if it does not exist / cannot be read (never throws). */
        private fun readTextOrNull(path: String): String? =
            runCatching { File(path).takeIf { it.exists() }?.readText() }.getOrNull()

        /**
         * Resolve a config from (defaults → JSON file → env → args). [args] are `--key value` /
         * `--key=value` pairs; [env] is the environment lookup (injectable for tests).
         */
        fun resolve(args: Array<String>, env: (String) -> String? = System::getenv): DaemonConfig {
            val cli = parseArgs(args)
            val configPath = cli["config"] ?: env("HEYARR_VAULT_SYNC_CONFIG")
            val file = configPath?.let { File(it) } ?: defaultConfigFile()
            var cfg = fromFile(file)

            // Environment overrides.
            env("HEYARR_VAULT_FOLDER")?.let { cfg = cfg.copy(folder = it.ifBlank { null }) }
            env("HEYARR_VAULT_SPACE_ID")?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(spaceId = it) }
            env("HEYARR_VAULT_CONTROLLER")?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(controller = it) }
            env("HEYARR_VOIDBIND_DEVICE_DIR")?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(deviceDir = it) }
            env("HEYARR_VAULT_POLL_MS")?.toLongOrNull()?.let { cfg = cfg.copy(pollMs = it) }
            env("HEYARR_VAULT_RETRY_MS")?.toLongOrNull()?.let { cfg = cfg.copy(retryMs = it) }
            env("HEYARR_VAULT_STATUS_FILE")?.takeIf { it.isNotBlank() }?.let {
                cfg =
                    cfg.copy(statusFile = File(it).toPath())
            }
            env("HEYARR_VAULT_SOCKET")?.takeIf {
                it.isNotBlank()
            }?.let { cfg = cfg.copy(socketPath = File(it).toPath()) }
            env("HEYARR_VAULT_INDEX")?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(indexFile = it) }
            env("HEYARR_VAULT_TOKEN")?.let { cfg = cfg.copy(token = it.ifBlank { null }) }
            env("HEYARR_VAULT_TOKEN_FILE")?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(tokenFile = it) }

            // Command-line overrides (highest precedence).
            cli["folder"]?.let { cfg = cfg.copy(folder = it.ifBlank { null }) }
            cli["space-id"]?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(spaceId = it) }
            cli["controller"]?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(controller = it) }
            cli["device-dir"]?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(deviceDir = it) }
            cli["poll-ms"]?.toLongOrNull()?.let { cfg = cfg.copy(pollMs = it) }
            cli["retry-ms"]?.toLongOrNull()?.let { cfg = cfg.copy(retryMs = it) }
            cli["status-file"]?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(statusFile = File(it).toPath()) }
            cli["socket"]?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(socketPath = File(it).toPath()) }
            cli["index-file"]?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(indexFile = it) }
            cli["token"]?.let { cfg = cfg.copy(token = it.ifBlank { null }) }
            cli["token-file"]?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(tokenFile = it) }
            return cfg
        }

        /** Read a JSON config file tolerantly (missing/corrupt → the defaults, never a crash). */
        fun fromFile(file: File): DaemonConfig {
            val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull() ?: return DaemonConfig()
            val obj = JsonScan.rootObject(text) ?: return DaemonConfig()
            val base = DaemonConfig()
            return DaemonConfig(
                folder = JsonScan.stringField(obj, "folder")?.takeIf { it.isNotBlank() },
                spaceId = JsonScan.stringField(obj, "space_id")?.takeIf { it.isNotBlank() } ?: base.spaceId,
                controller = JsonScan.stringField(obj, "controller")?.takeIf { it.isNotBlank() } ?: base.controller,
                deviceDir = JsonScan.stringField(obj, "device_dir")?.takeIf { it.isNotBlank() } ?: base.deviceDir,
                token = JsonScan.stringField(obj, "token")?.takeIf { it.isNotBlank() },
                tokenFile = JsonScan.stringField(obj, "token_file")?.takeIf { it.isNotBlank() },
                pollMs = JsonScan.longField(obj, "poll_ms") ?: base.pollMs,
                retryMs = JsonScan.longField(obj, "retry_ms") ?: base.retryMs,
                statusFile =
                JsonScan.stringField(obj, "status_file")?.takeIf { it.isNotBlank() }?.let { File(it).toPath() }
                    ?: base.statusFile,
                socketPath = JsonScan.stringField(obj, "socket")?.takeIf { it.isNotBlank() }?.let { File(it).toPath() }
                    ?: base.socketPath,
                indexFile = JsonScan.stringField(obj, "index_file")?.takeIf { it.isNotBlank() },
            )
        }

        /** `--key value` and `--key=value` → a map keyed by the flag name without dashes. */
        private fun parseArgs(args: Array<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a.startsWith("--")) {
                    val body = a.removePrefix("--")
                    val eq = body.indexOf('=')
                    if (eq >= 0) {
                        out[body.substring(0, eq)] = body.substring(eq + 1)
                    } else if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        out[body] = args[i + 1]
                        i++
                    } else {
                        out[body] = "true"
                    }
                }
                i++
            }
            return out
        }
    }
}
