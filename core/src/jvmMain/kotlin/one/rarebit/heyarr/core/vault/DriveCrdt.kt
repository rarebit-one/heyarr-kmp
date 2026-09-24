package one.rarebit.heyarr.core.vault

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import java.text.Normalizer

/**
 * The client-side, plaintext merge logic for a vault DRIVE — a person's files as a
 * mutable, path-addressed map (ADR-0095). The Kotlin twin of heyarr-core's
 * `internal/personalstate/crdt` drive: a path accumulates the set of every write it has
 * seen (keyed by the write's total order), a delete raises a tombstone, and the live
 * heads + version history are a PURE FUNCTION of that set — so [Drive.apply] is an
 * order-independent CRDT join and two devices converge (§43).
 *
 * The exported JSON of [DriveChange] and of the snapshot ARE the cross-language wire
 * contract; this parses and re-emits them byte-compatibly with Go (proven by the golden
 * vectors in jvmTest). Lives in jvmMain for now because [normalisePath]'s NFC step uses
 * java.text.Normalizer; it promotes to commonMain with an expect/actual when mobile (W5)
 * needs it.
 *
 * Deferred, exactly as in Go: conflicted-copy relocation (Resolved), retention (Retain),
 * and a dotted version vector for multi-way put-vs-delete.
 */

/** A write's total-order key: a Lamport counter with a unique tie-break tag. */
data class PosKey(val at: Long, val writer: String) {
    /** True when this sorts AFTER [other] under (at, writer) — the max-register join. */
    fun greater(other: PosKey): Boolean = if (at != other.at) at > other.at else writer > other.writer

    val isZero: Boolean get() = at == 0L && writer == ""

    companion object {
        val ZERO = PosKey(0, "")
    }
}

enum class DriveOp(val wire: Int) { PUT(0), DELETE(1) }

/** One write to the drive. Its JSON fields are the wire contract (§7). */
data class DriveChange(
    val op: DriveOp,
    val path: String,
    val blob: String = "", // PUT only
    val size: Long = 0, // PUT only
    val mtime: Long = 0, // PUT only
    val at: Long,
    val writer: String,
    val base: PosKey = PosKey.ZERO,
) {
    /** Well-formed enough to enter the CRDT? A pure function of the bytes (every replica agrees). */
    fun valid(): Boolean {
        if (writer.isEmpty()) return false
        return when (op) {
            DriveOp.PUT -> blob.isNotEmpty() && BLOB_RE.matches(blob)
            DriveOp.DELETE -> blob.isEmpty()
        }
    }

    companion object {
        private val BLOB_RE = Regex("^blake3:[0-9a-f]{64}$")
    }
}

/** One live file's current state for a caller. */
data class DriveEntry(
    val path: String,
    val blob: String,
    val size: Long,
    val mtime: Long,
    val conflicted: Boolean,
)

/**
 * Retention knobs (ADR-0095). [maxVersionsPerPath]: prior versions a live path keeps,
 * newest first (negative = all, 0 = none). [trashTtlSeconds]: how long a deleted path's
 * trashed blobs are kept, measured against their newest content mtime (negative =
 * forever, 0 = none).
 */
data class RetentionPolicy(val maxVersionsPerPath: Int, val trashTtlSeconds: Long)

private data class DriveValue(val blob: String, val size: Long, val mtime: Long, val key: PosKey, val base: PosKey)

private class DriveRecord {
    val values = HashMap<PosKey, DriveValue>()
    var delKey: PosKey = PosKey.ZERO

    /** Live blobs: the writes nobody built on, minus those the tombstone covers. */
    fun liveHeads(): List<DriveValue> {
        val built = HashSet<PosKey>()
        for (v in values.values) if (!v.base.isZero) built.add(v.base)
        val live = ArrayList<DriveValue>()
        for ((k, v) in values) {
            if (k in built) continue
            if (!delKey.isZero && delKey.greater(k)) continue
            live.add(v)
        }
        live.sortWith(compareBy({ it.key.at }, { it.key.writer }))
        return live
    }

    fun currentHead(): Triple<DriveValue?, Boolean, Boolean> {
        val live = liveHeads()
        if (live.isEmpty()) return Triple(null, false, false)
        return Triple(live.last(), true, live.size > 1) // sorted ascending → last is greatest
    }
}

/** The materialised path→record map plus the Lamport clock. A semilattice under [apply]. */
class Drive {
    private val entries = HashMap<String, DriveRecord>()
    private var counter: Long = 0

    /** Fold changes into the drive. Idempotent, commutative, associative; malformed changes skipped. */
    fun apply(vararg changes: DriveChange) = apply(changes.asList())

    fun apply(changes: List<DriveChange>) {
        for (c in changes) if (c.valid()) applyOne(c)
    }

    /**
     * Record a local PUT of [blob] at [path] and return the [DriveChange] to ship (applied
     * locally first). Base is the current head so a receiver can tell a clean successor
     * from a concurrent divergence. Writer is a fresh per-write tie-break tag.
     */
    fun put(path: String, blob: String, size: Long, mtime: Long): DriveChange {
        val np = normalisePath(path)
        val base = entries[np]?.currentHead()?.first?.key ?: PosKey.ZERO
        val c = DriveChange(DriveOp.PUT, np, blob, size, mtime, nextAt(), newWriter(), base)
        require(c.valid()) { "put: blob must be a canonical blake3 id, got $blob" }
        applyOne(c)
        return c
    }

    /** Record a local DELETE (tombstone) at [path] and return the change to ship. */
    fun delete(path: String): DriveChange {
        val c = DriveChange(DriveOp.DELETE, normalisePath(path), at = nextAt(), writer = newWriter())
        applyOne(c)
        return c
    }

    private fun nextAt(): Long {
        if (counter < Long.MAX_VALUE) counter++ // saturating, like readingpos.Set
        return counter
    }

    private fun newWriter(): String = "u:" + java.util.UUID.randomUUID().toString()

    private fun applyOne(c: DriveChange) {
        if (c.at > counter) counter = c.at
        val np = normalisePath(c.path)
        val rec = entries.getOrPut(np) { DriveRecord() }
        val key = PosKey(c.at, c.writer)
        when (c.op) {
            DriveOp.DELETE -> if (key.greater(rec.delKey)) rec.delKey = key
            DriveOp.PUT -> rec.values[key] = DriveValue(c.blob, c.size, c.mtime, key, c.base)
        }
    }

    /** The current blob at [path], if the path is live. */
    fun get(path: String): DriveEntry? {
        val np = normalisePath(path)
        val rec = entries[np] ?: return null
        val (head, live, conflicted) = rec.currentHead()
        if (!live || head == null || head.blob.isEmpty()) return null
        return DriveEntry(np, head.blob, head.size, head.mtime, conflicted)
    }

    /** Every live file, sorted by path for a deterministic read. */
    fun list(): List<DriveEntry> {
        val out = ArrayList<DriveEntry>(entries.size)
        for ((p, rec) in entries) {
            val (head, live, conflicted) = rec.currentHead()
            if (!live || head == null || head.blob.isEmpty()) continue
            out.add(DriveEntry(p, head.blob, head.size, head.mtime, conflicted))
        }
        out.sortBy { it.path }
        return out
    }

    /** Superseded blob ids retained at [path], newest-precedence first (version history + trash). */
    fun versions(path: String): List<String> {
        val rec = entries[normalisePath(path)] ?: return emptyList()
        val liveKeys = rec.liveHeads().map { it.key }.toHashSet()
        val prior = rec.values.values.filter { it.key !in liveKeys && it.blob.isNotEmpty() }
            .sortedWith(
                Comparator { a, b ->
                    if (b.key.greater(a.key)) {
                        1
                    } else if (a.key.greater(b.key)) {
                        -1
                    } else {
                        0
                    }
                },
            )
        return prior.map { it.blob }
    }

    /**
     * The client-side retention / GC reference view (ADR-0095, ADR-0018): under [policy]
     * at wall-clock [nowUnix] seconds, the blob ids that NO policy-retained entry
     * references anywhere in the drive — safe for the control plane to reclaim (which is
     * out of scope here). Pure and read-only; the live head of every path is always kept.
     * Sorted, deduplicated.
     */
    fun retain(policy: RetentionPolicy, nowUnix: Long): List<String> {
        val kept = HashSet<String>()
        val all = HashSet<String>()
        for (rec in entries.values) {
            for (v in rec.values.values) if (v.blob.isNotEmpty()) all.add(v.blob)
            val live = rec.liveHeads()
            for (h in live) if (h.blob.isNotEmpty()) kept.add(h.blob)

            if (live.isNotEmpty()) {
                // Live path: keep the newest maxVersionsPerPath of its prior versions.
                val liveKeys = live.map { it.key }.toHashSet()
                val prior = rec.values.values.filter { it.key !in liveKeys && it.blob.isNotEmpty() }
                    .sortedWith(
                        Comparator { a, b ->
                            if (b.key.greater(a.key)) {
                                1
                            } else if (a.key.greater(b.key)) {
                                -1
                            } else {
                                0
                            }
                        },
                    )
                for ((i, v) in prior.withIndex()) {
                    if (policy.maxVersionsPerPath >= 0 && i >= policy.maxVersionsPerPath) break
                    kept.add(v.blob)
                }
                continue
            }
            // Fully-deleted path: trash, kept while younger than the TTL (or forever if <0).
            if (policy.trashTtlSeconds < 0) {
                for (v in rec.values.values) if (v.blob.isNotEmpty()) kept.add(v.blob)
                continue
            }
            var newest = 0L
            for (v in rec.values.values) if (v.mtime > newest) newest = v.mtime
            if (nowUnix - newest <= policy.trashTtlSeconds) {
                for (v in rec.values.values) if (v.blob.isNotEmpty()) kept.add(v.blob)
            }
        }
        return (all - kept).sorted()
    }

    /**
     * The conflict-RESOLVED live tree the client renders: each path's winner at its path,
     * plus every losing head of a conflicted path relocated to a derived "conflicted copy"
     * path — so the merge discards no bytes (ADR-0095). A pure, order-independent function
     * of the converged drive: two converged replicas produce byte-identical trees. All
     * entries have conflicted=false (relocation already turned the conflict into two files).
     */
    fun resolved(): List<DriveEntry> {
        val occupied = HashSet<String>()
        for ((p, rec) in entries) if (rec.liveHeads().isNotEmpty()) occupied.add(p)

        val out = ArrayList<DriveEntry>()
        val losers = ArrayList<Pair<String, DriveValue>>()
        for ((p, rec) in entries) {
            val live = rec.liveHeads()
            if (live.isEmpty()) continue
            val winner = live.last()
            out.add(DriveEntry(p, winner.blob, winner.size, winner.mtime, false))
            for (l in live.subList(0, live.size - 1)) losers.add(p to l)
        }
        // Global sort so the numeric collision suffix is deterministic across replicas.
        losers.sortWith(
            Comparator { a, b ->
                if (a.first != b.first) {
                    a.first.compareTo(b.first)
                } else if (b.second.key.greater(a.second.key)) {
                    -1
                } else if (a.second.key.greater(b.second.key)) {
                    1
                } else {
                    0
                }
            },
        )
        val placed = HashSet<String>()
        for ((orig, v) in losers) {
            val dp = uniqueConflictPath(orig, v.key, occupied, placed)
            placed.add(dp)
            out.add(DriveEntry(dp, v.blob, v.size, v.mtime, false))
        }
        out.sortBy { it.path }
        return out
    }

    private fun uniqueConflictPath(orig: String, key: PosKey, occupied: Set<String>, placed: Set<String>): String {
        var n = 1
        while (true) {
            val cand = conflictPath(orig, key, n)
            if (cand !in occupied && cand !in placed) return cand
            n++
        }
    }

    private fun conflictPath(orig: String, key: PosKey, n: Int): String {
        val slash = orig.lastIndexOf('/')
        val dir = if (slash < 0) "" else orig.substring(0, slash + 1)
        val file = if (slash < 0) orig else orig.substring(slash + 1)
        // splitExt: a leading dot (dotfile) is NOT an extension boundary.
        val dot = file.lastIndexOf('.')
        val stem = if (dot <= 0) file else file.substring(0, dot)
        val ext = if (dot <= 0) "" else file.substring(dot)
        val suffix = if (n > 1) " ($n)" else ""
        return "$dir$stem (conflicted copy — ${key.writer} — ${key.at})$suffix$ext"
    }

    /**
     * Deterministic snapshot: entries sorted by path, writes by (at, writer). Two converged
     * drives produce byte-identical output (safe to content-address), matching Go's
     * json.Marshal of the drive snapshot exactly.
     */
    fun snapshot(): String {
        val sb = StringBuilder()
        sb.append("{\"entries\":[")
        val paths = entries.keys.sorted()
        for ((pi, p) in paths.withIndex()) {
            if (pi > 0) sb.append(',')
            val rec = entries.getValue(p)
            sb.append("{\"path\":")
            jsonString(sb, p)
            sb.append(",\"writes\":[")
            val writes = rec.values.values.sortedWith(compareBy({ it.key.at }, { it.key.writer }))
            for ((wi, w) in writes.withIndex()) {
                if (wi > 0) sb.append(',')
                sb.append("{\"blob\":")
                jsonString(sb, w.blob)
                sb.append(",\"size\":").append(w.size)
                sb.append(",\"mtime\":").append(w.mtime)
                sb.append(",\"at\":").append(w.key.at)
                sb.append(",\"writer\":")
                jsonString(sb, w.key.writer)
                if (w.base.at != 0L) sb.append(",\"baseAt\":").append(w.base.at)
                if (w.base.writer != "") {
                    sb.append(",\"baseBy\":")
                    jsonString(sb, w.base.writer)
                }
                sb.append('}')
            }
            sb.append(']')
            if (rec.delKey.at != 0L) sb.append(",\"delAt\":").append(rec.delKey.at)
            if (rec.delKey.writer != "") {
                sb.append(",\"delBy\":")
                jsonString(sb, rec.delKey.writer)
            }
            sb.append('}')
        }
        sb.append("],\"counter\":").append(counter).append('}')
        return sb.toString()
    }

    companion object {
        /** Reconstruct a drive from [snapshot] output. */
        fun fromSnapshot(json: String): Drive {
            val d = Drive()
            val root = JsonScan.rootObject(json) ?: return d
            d.counter = JsonScan.longField(root, "counter") ?: 0
            for (entryObj in JsonScan.objectsOf(JsonScan.arrayOf(root, listOf("entries")) ?: "[]", emptyList())) {
                val path = JsonScan.stringField(entryObj, "path") ?: continue
                val rec = DriveRecord()
                val delAt = JsonScan.longField(entryObj, "delAt") ?: 0
                val delBy = JsonScan.stringField(entryObj, "delBy") ?: ""
                rec.delKey = PosKey(delAt, delBy)
                for (w in JsonScan.objectsOf(JsonScan.arrayOf(entryObj, listOf("writes")) ?: "[]", emptyList())) {
                    val at = JsonScan.longField(w, "at") ?: 0
                    val writer = JsonScan.stringField(w, "writer") ?: ""
                    val key = PosKey(at, writer)
                    rec.values[key] = DriveValue(
                        blob = JsonScan.stringField(w, "blob") ?: "",
                        size = JsonScan.longField(w, "size") ?: 0,
                        mtime = JsonScan.longField(w, "mtime") ?: 0,
                        key = key,
                        base = PosKey(JsonScan.longField(w, "baseAt") ?: 0, JsonScan.stringField(w, "baseBy") ?: ""),
                    )
                }
                d.entries[path] = rec
            }
            return d
        }

        /** Parse a Go-marshalled [DriveChange] object slice. */
        fun parseChange(obj: String): DriveChange {
            val baseObj = JsonScan.objectAt(obj, "base")
            val base = if (baseObj != null) {
                PosKey(JsonScan.longField(baseObj, "At") ?: 0, JsonScan.stringField(baseObj, "Writer") ?: "")
            } else {
                PosKey.ZERO
            }
            return DriveChange(
                op = if ((JsonScan.intField(obj, "op") ?: 0) == 1) DriveOp.DELETE else DriveOp.PUT,
                path = JsonScan.stringField(obj, "path") ?: "",
                blob = JsonScan.stringField(obj, "blob") ?: "",
                size = JsonScan.longField(obj, "size") ?: 0,
                mtime = JsonScan.longField(obj, "mtime") ?: 0,
                at = JsonScan.longField(obj, "at") ?: 0,
                writer = JsonScan.stringField(obj, "writer") ?: "",
                base = base,
            )
        }
    }
}

/**
 * The wire path rule (ADR-0095) so two devices derive the IDENTICAL key for "the same
 * path": Unicode NFC, forward slashes, cleaned (resolve . and .., collapse //), leading
 * slash trimmed, case-sensitive.
 */

/**
 * Serialise a [DriveChange] to its wire JSON (the inverse of [Drive.parseChange]),
 * matching heyarr-core's `json.Marshal` field names + omitempty so a Kotlin device's
 * change parses on a Go device and vice-versa. This is the plaintext that gets
 * `encryptChange`d and pushed.
 */
fun encodeDriveChange(c: DriveChange): String {
    val m = LinkedHashMap<String, Any?>()
    m["op"] = c.op.wire
    m["path"] = c.path
    if (c.blob.isNotEmpty()) m["blob"] = c.blob
    if (c.size != 0L) m["size"] = c.size
    if (c.mtime != 0L) m["mtime"] = c.mtime
    m["at"] = c.at
    m["writer"] = c.writer
    m["base"] = linkedMapOf<String, Any?>("At" to c.base.at, "Writer" to c.base.writer)
    return JsonWrite.obj(m)
}

fun normalisePath(p: String): String {
    val nfc = Normalizer.normalize(p, Normalizer.Form.NFC).replace('\\', '/')
    return cleanPath("/$nfc").removePrefix("/")
}

/** The subset of Go's path.Clean this needs: lexical resolution of . and .. on a rooted path. */
private fun cleanPath(p: String): String {
    val rooted = p.startsWith("/")
    val out = ArrayDeque<String>()
    for (seg in p.split('/')) {
        when (seg) {
            "", "." -> {}

            ".." -> if (out.isNotEmpty() && out.last() != "..") {
                out.removeLast()
            } else if (!rooted) {
                out.addLast("..")
            }

            else -> out.addLast(seg)
        }
    }
    val body = out.joinToString("/")
    return if (rooted) "/$body" else body.ifEmpty { "." }
}

/** Minimal JSON string escaping matching Go's json.Marshal for these values (", \, controls). */
private fun jsonString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
}
