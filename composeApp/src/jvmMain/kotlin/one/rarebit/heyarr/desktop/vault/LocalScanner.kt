package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.vault.LocalFile
import one.rarebit.heyarr.core.vault.SyncIndexEntry
import one.rarebit.heyarr.core.vault.normalisePath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * Scans the designated vault folder into the `path → LocalFile` map the reconcile core
 * consumes. Paths are keyed by the CRDT's [normalisePath] (NFC, forward-slash, cleaned)
 * so two devices agree on identity. HYBRID change detection (the decided semantics): a
 * file's plaintext BLAKE3 is recomputed ONLY when its (size, mtime) differs from the
 * per-device index — an unchanged file reuses the index's hash, so a large stable media
 * folder is cheap. When a hash IS needed it STREAMS the file (a multi-GB file is never
 * read whole).
 */
object LocalScanner {

    /** 1 MiB read buffer for the streaming hash. */
    private const val CHUNK = 1 shl 20

    fun scan(
        root: Path,
        index: Map<String, SyncIndexEntry> = emptyMap(),
        ignore: (String) -> Boolean = ::defaultIgnore,
    ): Map<String, LocalFile> {
        if (!Files.isDirectory(root)) return emptyMap()
        val out = HashMap<String, LocalFile>()
        Files.walk(root).use { stream ->
            for (p in stream.asSequence()) {
                if (!p.isRegularFile()) continue
                val path = normalisePath(root.relativize(p).toString())
                if (path.isEmpty() || ignore(path)) continue
                val size = Files.size(p)
                val mtime = Files.getLastModifiedTime(p).toInstant().epochSecond
                val prior = index[path]
                val hash = if (prior != null && prior.size == size && prior.mtime == mtime) {
                    prior.plaintextHash // unchanged by the cheap gate — reuse, no re-hash
                } else {
                    hashFile(p)
                }
                out[path] = LocalFile(size, mtime, hash)
            }
        }
        return out
    }

    private fun hashFile(p: Path): String {
        val h = Blake3.streaming()
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) h.update(buf, n)
            }
        }
        return h.hashHex()
    }

    /** Skip the daemon's own conflicted-copy trash + typical OS/editor cruft. */
    private fun defaultIgnore(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == ".DS_Store" ||
            name == "Thumbs.db" ||
            name.endsWith(".swp") ||
            name.endsWith("~") ||
            path.startsWith(".sync-tmp/") // where in-flight downloads land before atomic rename
    }
}
