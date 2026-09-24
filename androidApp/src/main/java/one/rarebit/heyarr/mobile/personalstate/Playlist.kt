package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.JsonScan

/**
 * The playlist CRDT — an add-wins OR-Set with a Lamport total order, ported
 * byte-for-byte from heyarr-core `internal/personalstate/crdt/playlist.go`. A
 * device decrypts concurrent offline changes and folds them here; the merge is a
 * semilattice join, so the converged order is a function of the change data, never
 * of arrival order (§43). Parity with Go is pinned by [CrdtParityTest] against the
 * committed vectors.
 *
 * The wire change is exactly `json.Marshal(crdt.Change)` — Go's exported field
 * names, no tags — so [PlaylistChange.decode]/[PlaylistChange.encode] use `Op`,
 * `ItemID`, `Tag`, `Order{Counter,Tag}`, `Observed`. `Encode`/`Snapshot` reproduce
 * Go's deterministic serialisations for the parity assertions and the sync base.
 */
internal enum class PlaylistOp(val wire: Int) {
    ADD(0),
    REMOVE(1),
    ;

    companion object {
        fun of(wire: Int): PlaylistOp = if (wire == REMOVE.wire) REMOVE else ADD
    }
}

/** A Lamport counter with the add's tag as a deterministic tie-break. */
internal data class OrderKey(val counter: ULong, val tag: String) {
    fun less(other: OrderKey): Boolean = if (counter != other.counter) counter < other.counter else tag < other.tag
}

internal data class PlaylistChange(
    val op: PlaylistOp,
    val itemId: String,
    val tag: String = "",
    val order: OrderKey = OrderKey(0UL, ""),
    val observed: List<String> = emptyList(),
) {
    /** Serialise exactly as Go's `json.Marshal(crdt.Change)` (all exported fields, in order). */
    fun encode(): String {
        val obs = if (observed.isEmpty()) "null" else observed.joinToString(",", "[", "]") { PsJson.goJsonString(it) }
        return "{\"Op\":${op.wire}" +
            ",\"ItemID\":${PsJson.goJsonString(itemId)}" +
            ",\"Tag\":${PsJson.goJsonString(tag)}" +
            ",\"Order\":{\"Counter\":${order.counter},\"Tag\":${PsJson.goJsonString(order.tag)}}" +
            ",\"Observed\":$obs}"
    }

    companion object {
        fun decode(json: String): PlaylistChange {
            val op = PlaylistOp.of(JsonScan.longField(json, "Op")?.toInt() ?: 0)
            val itemId = JsonScan.stringField(json, "ItemID") ?: ""
            val tag = JsonScan.stringField(json, "Tag") ?: ""
            val orderObj = JsonScan.objectAt(json, "Order")
            val counter = orderObj?.let { PsJson.ulong(it, "Counter") } ?: 0UL
            val orderTag = orderObj?.let { JsonScan.stringField(it, "Tag") } ?: ""
            return PlaylistChange(op, itemId, tag, OrderKey(counter, orderTag), PsJson.stringArray(json, "Observed"))
        }
    }
}

internal class Playlist {
    private data class AddRec(val itemId: String, val order: OrderKey)

    private val adds = HashMap<String, AddRec>()
    private val tombstones = HashSet<String>()
    private var counter: ULong = 0UL

    fun apply(changes: List<PlaylistChange>) = changes.forEach(::apply)

    fun apply(c: PlaylistChange) {
        when (c.op) {
            PlaylistOp.ADD -> {
                val rec = AddRec(c.itemId, c.order)
                adds[c.tag] = adds[c.tag]?.let { lesser(it, rec) } ?: rec
                if (c.order.counter > counter) counter = c.order.counter
            }

            PlaylistOp.REMOVE -> c.observed.forEach { tombstones.add(it) }
        }
    }

    /** Mint a local add of [itemId] under a fresh [tag], apply it, and return the change to ship. */
    fun add(itemId: String, tag: String): PlaylistChange {
        if (counter < ULong.MAX_VALUE) counter += 1UL
        val c = PlaylistChange(PlaylistOp.ADD, itemId, tag, OrderKey(counter, tag))
        apply(c)
        return c
    }

    /** Mint a local remove of [itemId] (observing its live tags), apply it, and return the change to ship. */
    fun remove(itemId: String): PlaylistChange {
        val c = PlaylistChange(PlaylistOp.REMOVE, itemId, observed = liveTags(itemId))
        apply(c)
        return c
    }

    private fun liveTags(itemId: String): List<String> =
        adds.filter { (tag, rec) -> rec.itemId == itemId && tag !in tombstones }.keys.sorted()

    /** The present items in converged total order. */
    fun ids(): List<String> {
        val best = HashMap<String, OrderKey>()
        for ((tag, rec) in adds) {
            if (tag in tombstones) continue
            val cur = best[rec.itemId]
            if (cur == null || rec.order.less(cur)) best[rec.itemId] = rec.order
        }
        return best.entries
            .sortedWith { a, b -> lessCompare(a.value, b.value) }
            .map { it.key }
    }

    fun encode(): String {
        val sb = StringBuilder("adds:\n")
        for (tag in adds.keys.sorted()) {
            val r = adds.getValue(tag)
            sb.append("  ").append(tag).append('=').append(r.itemId).append('@')
                .append(r.order.counter.toString()).append(':').append(r.order.tag).append('\n')
        }
        sb.append("tombstones:\n")
        for (tag in tombstones.sorted()) sb.append("  ").append(tag).append('\n')
        sb.append("counter:").append(counter.toString()).append('\n')
        return sb.toString()
    }

    fun snapshot(): String {
        val sb = StringBuilder("{\"adds\":[")
        adds.keys.sorted().forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            val r = adds.getValue(tag)
            sb.append("{\"tag\":").append(PsJson.goJsonString(tag))
                .append(",\"item\":").append(PsJson.goJsonString(r.itemId))
                .append(",\"counter\":").append(r.order.counter.toString())
                .append(",\"order_tag\":").append(PsJson.goJsonString(r.order.tag)).append('}')
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
        fun fromSnapshot(json: String): Playlist {
            val p = Playlist()
            for (obj in JsonScan.objectsOf(json, listOf("adds"))) {
                val tag = JsonScan.stringField(obj, "tag") ?: continue
                p.adds[tag] = AddRec(
                    JsonScan.stringField(obj, "item") ?: "",
                    OrderKey(PsJson.ulong(obj, "counter") ?: 0UL, JsonScan.stringField(obj, "order_tag") ?: ""),
                )
            }
            p.tombstones.addAll(PsJson.stringArray(json, "tombstones"))
            p.counter = PsJson.ulong(json, "counter") ?: 0UL
            return p
        }

        private fun lesser(a: AddRec, b: AddRec): AddRec = when {
            a.itemId != b.itemId -> if (a.itemId < b.itemId) a else b
            a.order.less(b.order) -> a
            else -> b
        }

        private fun lessCompare(a: OrderKey, b: OrderKey): Int = when {
            a.less(b) -> -1
            b.less(a) -> 1
            else -> 0
        }
    }
}
