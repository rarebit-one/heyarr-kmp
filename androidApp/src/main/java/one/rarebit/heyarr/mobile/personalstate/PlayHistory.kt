package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.JsonScan

/**
 * The play-history CRDT — a grow-only set of play events (a G-Set), ported from
 * heyarr-core `internal/personalstate/crdt/history.go`. A play is an event that no
 * later action un-happens; merge is set union. Recency is a Lamport `At` with the
 * event tag as tie-break. Parity with Go is pinned by [CrdtParityTest].
 *
 * Wire change = `json.Marshal(crdt.PlayChange)`: `ItemID`, `Tag`, `At`.
 */
internal data class PlayChange(
    val itemId: String,
    val tag: String = "",
    val at: ULong = 0UL,
) {
    fun encode(): String =
        "{\"ItemID\":${PsJson.goJsonString(itemId)}" +
            ",\"Tag\":${PsJson.goJsonString(tag)}" +
            ",\"At\":$at}"

    companion object {
        fun decode(json: String): PlayChange = PlayChange(
            JsonScan.stringField(json, "ItemID") ?: "",
            JsonScan.stringField(json, "Tag") ?: "",
            PsJson.ulong(json, "At") ?: 0UL,
        )
    }
}

/** One item in a derived listening view. */
internal data class PlayEntry(val id: String, val count: Int, val lastAt: ULong)

internal class PlayLog {
    private data class PlayRec(val itemId: String, val at: ULong)

    private val events = HashMap<String, PlayRec>()
    private var counter: ULong = 0UL

    fun apply(changes: List<PlayChange>) = changes.forEach(::apply)

    fun apply(c: PlayChange) {
        val rec = PlayRec(c.itemId, c.at)
        events[c.tag] = events[c.tag]?.let { lesser(it, rec) } ?: rec
        if (c.at > counter) counter = c.at
    }

    fun record(itemId: String, tag: String): PlayChange {
        if (counter < ULong.MAX_VALUE) counter += 1UL
        val c = PlayChange(itemId, tag, counter)
        apply(c)
        return c
    }

    fun count(itemId: String): Int = events.values.count { it.itemId == itemId }

    private fun perItem(): Map<String, PlayEntry> {
        val byItem = HashMap<String, PlayEntry>()
        for (rec in events.values) {
            val e = byItem[rec.itemId]
            byItem[rec.itemId] = if (e == null) {
                PlayEntry(rec.itemId, 1, rec.at)
            } else {
                PlayEntry(rec.itemId, e.count + 1, if (rec.at > e.lastAt) rec.at else e.lastAt)
            }
        }
        return byItem
    }

    /** Distinct items most-recently-played first. */
    fun recentIds(): List<String> = perItem().values
        .sortedWith(compareByDescending<PlayEntry> { it.lastAt }.thenBy { it.id })
        .map { it.id }

    /** The item of the single most recent play event, or null when nothing has played. */
    fun nowPlaying(): String? {
        var bestTag: String? = null
        var bestItem: String? = null
        var bestAt: ULong = 0UL
        for ((tag, rec) in events) {
            if (bestTag == null || rec.at > bestAt || (rec.at == bestAt && tag > bestTag!!)) {
                bestTag = tag; bestItem = rec.itemId; bestAt = rec.at
            }
        }
        return bestItem
    }

    fun encode(): String {
        val sb = StringBuilder("events:\n")
        for (tag in events.keys.sorted()) {
            val r = events.getValue(tag)
            sb.append("  ").append(tag).append('=').append(r.itemId).append('@').append(r.at.toString()).append('\n')
        }
        sb.append("counter:").append(counter.toString()).append('\n')
        return sb.toString()
    }

    fun snapshot(): String {
        val sb = StringBuilder("{\"events\":[")
        events.keys.sorted().forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            val r = events.getValue(tag)
            sb.append("{\"tag\":").append(PsJson.goJsonString(tag))
                .append(",\"item\":").append(PsJson.goJsonString(r.itemId))
                .append(",\"at\":").append(r.at.toString()).append('}')
        }
        sb.append("],\"counter\":").append(counter.toString()).append('}')
        return sb.toString()
    }

    companion object {
        fun fromSnapshot(json: String): PlayLog {
            val l = PlayLog()
            for (obj in JsonScan.objectsOf(json, listOf("events"))) {
                val tag = JsonScan.stringField(obj, "tag") ?: continue
                l.events[tag] = PlayRec(JsonScan.stringField(obj, "item") ?: "", PsJson.ulong(obj, "at") ?: 0UL)
            }
            l.counter = PsJson.ulong(json, "counter") ?: 0UL
            return l
        }

        private fun lesser(a: PlayRec, b: PlayRec): PlayRec = when {
            a.itemId != b.itemId -> if (a.itemId < b.itemId) a else b
            a.at != b.at -> if (a.at < b.at) a else b
            else -> a
        }
    }
}
