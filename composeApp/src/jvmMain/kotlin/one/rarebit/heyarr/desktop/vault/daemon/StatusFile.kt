package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.core.mcp.JsonWrite
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** One completed pass, as the status file reports it. Mirrors the contract's `last_pass` object. */
data class PassStats(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val deleted: Int = 0,
    val ms: Long = 0,
)

/**
 * The daemon's status object — the EXACT contract already consumed by the merged homelab-ops MCP +
 * Omarchy plugin. Written atomically to `~/.cache/vault-sync.json` each pass and on every state
 * change. Do not add, drop or rename fields without updating that consumer; `schema` is the version
 * gate for exactly that.
 *
 * ```
 * {"schema":1,"ts":"<RFC3339>","phase":"off|preparing|running|paused|error","folder":"…",
 *  "space_id":"…","controller":"…","device":"ed25519:…","watching":true,
 *  "last_sync_at":"<RFC3339|null>",
 *  "last_pass":{"pushed":0,"pulled":0,"conflicts":0,"deleted":0,"ms":0},
 *  "conflicts":[{"path":"…"}],"last_error":"<string|null>"}
 * ```
 *
 * The top level is hand-assembled rather than handed to [JsonWrite.obj]: [JsonWrite] DROPS
 * null-valued keys (its deliberate "absence, not null" stance), but this contract requires
 * `last_sync_at` and `last_error` to be PRESENT keys carrying an explicit JSON `null`. The nested
 * `last_pass` object and `conflicts` array still go through [JsonWrite] for their escaping.
 */
data class StatusSnapshot(
    val phase: String,
    val folder: String,
    val spaceId: String,
    val controller: String,
    val device: String,
    val watching: Boolean,
    val lastSyncAtMs: Long?,
    val lastPass: PassStats?,
    val conflicts: List<String>,
    val lastError: String?,
    val nowMs: Long,
) {
    fun toJson(): String {
        val pass = lastPass ?: PassStats()
        val sb = StringBuilder()
        sb.append('{')
        key(sb, "schema"); sb.append(SCHEMA); sb.append(',')
        strKey(sb, "ts", rfc3339(nowMs)); sb.append(',')
        strKey(sb, "phase", phase); sb.append(',')
        strKey(sb, "folder", folder); sb.append(',')
        strKey(sb, "space_id", spaceId); sb.append(',')
        strKey(sb, "controller", controller); sb.append(',')
        strKey(sb, "device", device); sb.append(',')
        key(sb, "watching"); sb.append(if (watching) "true" else "false"); sb.append(',')
        key(sb, "last_sync_at"); if (lastSyncAtMs != null) JsonWrite.writeString(sb, rfc3339(lastSyncAtMs)) else sb.append("null"); sb.append(',')
        key(sb, "last_pass"); sb.append(
            JsonWrite.obj(
                linkedMapOf(
                    "pushed" to pass.pushed,
                    "pulled" to pass.pulled,
                    "conflicts" to pass.conflicts,
                    "deleted" to pass.deleted,
                    "ms" to pass.ms,
                ),
            ),
        ); sb.append(',')
        key(sb, "conflicts"); sb.append(JsonWrite.value(conflicts.map { linkedMapOf("path" to it) })); sb.append(',')
        key(sb, "last_error"); if (lastError != null) JsonWrite.writeString(sb, lastError) else sb.append("null")
        sb.append('}')
        return sb.toString()
    }

    private fun key(sb: StringBuilder, k: String) { JsonWrite.writeString(sb, k); sb.append(':') }
    private fun strKey(sb: StringBuilder, k: String, v: String) { key(sb, k); JsonWrite.writeString(sb, v) }

    companion object {
        const val SCHEMA = 1

        /** RFC3339 / ISO-8601 instant in UTC, e.g. `2026-09-17T10:00:00.123Z`. */
        fun rfc3339(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

        /**
         * Write [json] to [path] atomically: a sibling temp file, then an atomic rename over the
         * target, so a reader (the plugin) never sees a half-written file. Creates the parent dir.
         */
        fun writeAtomically(path: Path, json: String) {
            val parent = path.parent?.also { Files.createDirectories(it) } ?: path.toAbsolutePath().parent
            // A unique temp (not a fixed `<name>.tmp`) so two concurrent writers — the status poller
            // and a command handler — never trample each other's in-flight file.
            val tmp = Files.createTempFile(parent, path.fileName.toString(), ".tmp")
            try {
                Files.write(tmp, (json + "\n").toByteArray(Charsets.UTF_8))
                runCatching { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    .onFailure { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
            } finally {
                runCatching { Files.deleteIfExists(tmp) }
            }
        }
    }
}
