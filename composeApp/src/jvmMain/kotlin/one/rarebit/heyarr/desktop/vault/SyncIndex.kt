package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.core.vault.SyncIndexEntry
import java.io.File

/**
 * The per-device record of what this machine has SYNCED for a vault: `path → (plaintext
 * hash, remote blob, size, mtime)`. It is what makes deletes SAFE — a remote delete only
 * fires for a path this index proves was synced and is now gone, so a first run (empty
 * index) or an unmounted folder never wipes the remote (the decided semantics). It also
 * backs the hybrid change gate (reuse a hash when size+mtime are unchanged) and bridges
 * the plaintext-hash ↔ ciphertext-blob identity spaces.
 *
 * Stored as a small hand-rolled JSON file (the org's no-serialization stance), mirroring
 * `settings/SettingsStore`. It is device-local state, never a wire fact.
 */
interface SyncIndexStore {
    fun load(): Map<String, SyncIndexEntry>
    fun save(index: Map<String, SyncIndexEntry>)
}

/** For tests. */
class InMemorySyncIndexStore(private var state: Map<String, SyncIndexEntry> = emptyMap()) : SyncIndexStore {
    override fun load(): Map<String, SyncIndexEntry> = state
    override fun save(index: Map<String, SyncIndexEntry>) {
        state = index
    }
}

class FileSyncIndexStore(private val file: File = defaultIndexFile()) : SyncIndexStore {

    override fun load(): Map<String, SyncIndexEntry> {
        if (!file.exists()) return emptyMap()
        val body = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        val array = JsonScan.arrayOf(body, listOf("entries")) ?: return emptyMap()
        val out = LinkedHashMap<String, SyncIndexEntry>()
        for (obj in JsonScan.objectsOf(array, emptyList())) {
            val path = JsonScan.stringField(obj, "path") ?: continue
            out[path] = SyncIndexEntry(
                plaintextHash = JsonScan.stringField(obj, "plaintext_hash") ?: continue,
                remoteBlob = JsonScan.stringField(obj, "remote_blob") ?: continue,
                size = JsonScan.longField(obj, "size") ?: 0,
                mtime = JsonScan.longField(obj, "mtime") ?: 0,
            )
        }
        return out
    }

    override fun save(index: Map<String, SyncIndexEntry>) {
        val entries = index.entries.sortedBy { it.key }.map { (path, e) ->
            linkedMapOf<String, Any?>(
                "path" to path,
                "plaintext_hash" to e.plaintextHash,
                "remote_blob" to e.remoteBlob,
                "size" to e.size,
                "mtime" to e.mtime,
            )
        }
        val json = JsonWrite.obj(linkedMapOf("entries" to entries))
        file.parentFile?.mkdirs()
        file.writeText(json)
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
        } // best-effort 0600-ish
        runCatching {
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    companion object {
        fun defaultIndexFile(): File {
            val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.config")
            return File("$base/heyarr-desktop/vault-index.json")
        }
    }
}
