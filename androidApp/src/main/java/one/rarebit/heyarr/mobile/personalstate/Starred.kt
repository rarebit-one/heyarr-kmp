package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.JsonScan

/**
 * The starred CRDT — the playlist's add-wins OR-Set minus positional order, ported
 * from heyarr-core `internal/personalstate/crdt/starred.go`. A star carries a
 * Lamport `At` counter used only to order most-recently-starred-first; membership
 * is add-wins (a concurrent star survives an unstar that never saw it). Parity with
 * Go is pinned by [CrdtParityTest].
 *
 * Wire change = `json.Marshal(crdt.StarChange)`: `Op`, `ItemID`, `Tag`, `At`, `Observed`.
 */
internal enum class StarOp(val wire: Int) {
    STAR(0),
    UNSTAR(1),
    ;

    companion object {
        fun of(wire: Int): StarOp = if (wire == UNSTAR.wire) UNSTAR else STAR
    }
}

internal data class StarChange(
    val op: StarOp,
    val itemId: String,
    val tag: String = "",
    val at: ULong = 0UL,
    val observed: List<String> = emptyList(),
) {
    fun encode(): String {
        val obs = if (observed.isEmpty()) "null" else observed.joinToString(",", "[", "]") { PsJson.goJsonString(it) }
        return "{\"Op\":${op.wire}" +
            ",\"ItemID\":${PsJson.goJsonString(itemId)}" +
            ",\"Tag\":${PsJson.goJsonString(tag)}" +
            ",\"At\":$at" +
            ",\"Observed\":$obs}"
    }

    companion object {
        fun decode(json: String): StarChange = StarChange(
            StarOp.of(JsonScan.longField(json, "Op")?.toInt() ?: 0),
            JsonScan.stringField(json, "ItemID") ?: "",
            JsonScan.stringField(json, "Tag") ?: "",
            PsJson.ulong(json, "At") ?: 0UL,
            PsJson.stringArray(json, "Observed"),
        )
    }
}

internal class StarSet {
    private data class StarRec(val itemId: String, val at: ULong)

    private val adds = HashMap<String, StarRec>()
    private val tombstones = HashSet<String>()
    private var counter: ULong = 0UL

    fun apply(changes: List<StarChange>) = changes.forEach(::apply)

    fun apply(c: StarChange) {
        when (c.op) {
            StarOp.STAR -> {
                val rec = StarRec(c.itemId, c.at)
                adds[c.tag] = adds[c.tag]?.let { lesser(it, rec) } ?: rec
                if (c.at > counter) counter = c.at
            }

            StarOp.UNSTAR -> c.observed.forEach { tombstones.add(it) }
        }
    }

    fun star(itemId: String, tag: String): StarChange {
        if (counter < ULong.MAX_VALUE) counter += 1UL
        val c = StarChange(StarOp.STAR, itemId, tag, counter)
        apply(c)
        return c
    }

    fun unstar(itemId: String): StarChange {
        val c = StarChange(StarOp.UNSTAR, itemId, observed = liveTags(itemId))
        apply(c)
        return c
    }

    private fun liveTags(itemId: String): List<String> =
        adds.filter { (tag, rec) -> rec.itemId == itemId && tag !in tombstones }.keys.sorted()

    fun isStarred(itemId: String): Boolean = adds.any { (tag, rec) -> rec.itemId == itemId && tag !in tombstones }

    /** Starred item ids, most-recently-starred first. */
    fun ids(): List<String> {
        val best = HashMap<String, ULong>()
        for ((tag, rec) in adds) {
            if (tag in tombstones) continue
            val cur = best[rec.itemId]
            if (cur == null || rec.at > cur) best[rec.itemId] = rec.at
        }
        return best.entries
            .sortedWith(compareByDescending<Map.Entry<String, ULong>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    fun encode(): String {
        val sb = StringBuilder("stars:\n")
        for (tag in adds.keys.sorted()) {
            val r = adds.getValue(tag)
            sb.append("  ").append(tag).append('=').append(r.itemId).append('@').append(r.at.toString()).append('\n')
        }
        sb.append("tombstones:\n")
        for (tag in tombstones.sorted()) sb.append("  ").append(tag).append('\n')
        sb.append("counter:").append(counter.toString()).append('\n')
        return sb.toString()
    }

    fun snapshot(): String {
        val sb = StringBuilder("{\"stars\":[")
        adds.keys.sorted().forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            val r = adds.getValue(tag)
            sb.append("{\"tag\":").append(PsJson.goJsonString(tag))
                .append(",\"item\":").append(PsJson.goJsonString(r.itemId))
                .append(",\"at\":").append(r.at.toString()).append('}')
        }
        sb.append("],\"tombstones\":[")
        tombstones.sorted().forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            sb.append(PsJson.goJsonString(tag))
        }
        sb.append("],\"counter\":").append(counter.toString()).append('}')
        return sb.toString()
    }

    companion object {
        fun fromSnapshot(json: String): StarSet {
            val s = StarSet()
            for (obj in JsonScan.objectsOf(json, listOf("stars"))) {
                val tag = JsonScan.stringField(obj, "tag") ?: continue
                s.adds[tag] = StarRec(JsonScan.stringField(obj, "item") ?: "", PsJson.ulong(obj, "at") ?: 0UL)
            }
            s.tombstones.addAll(PsJson.stringArray(json, "tombstones"))
            s.counter = PsJson.ulong(json, "counter") ?: 0UL
            return s
        }

        private fun lesser(a: StarRec, b: StarRec): StarRec = when {
            a.itemId != b.itemId -> if (a.itemId < b.itemId) a else b
            a.at != b.at -> if (a.at < b.at) a else b
            else -> a
        }
    }
}
