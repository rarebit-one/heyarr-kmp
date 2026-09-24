package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.JsonScan

/**
 * The reading-position CRDT — a per-publication last-writer-wins register, ported
 * from heyarr-core `internal/personalstate/crdt/readingpos.go`. The winner of two
 * concurrent writes is the greater `(At, Writer)` key; a fully-tied key (hostile)
 * is broken by the position string. Parity with Go is pinned by [CrdtParityTest].
 *
 * Wire change = `json.Marshal(crdt.PositionChange)`: `PubID`, `Position`, `At`, `Writer`.
 */
internal data class PositionChange(
    val pubId: String,
    val position: String,
    val at: ULong = 0UL,
    val writer: String = "",
) {
    fun encode(): String = "{\"PubID\":${PsJson.goJsonString(pubId)}" +
        ",\"Position\":${PsJson.goJsonString(position)}" +
        ",\"At\":$at" +
        ",\"Writer\":${PsJson.goJsonString(writer)}}"

    companion object {
        fun decode(json: String): PositionChange = PositionChange(
            JsonScan.stringField(json, "PubID") ?: "",
            JsonScan.stringField(json, "Position") ?: "",
            PsJson.ulong(json, "At") ?: 0UL,
            JsonScan.stringField(json, "Writer") ?: "",
        )
    }
}

internal class ReadingPositions {
    private data class PosKey(val at: ULong, val writer: String) {
        fun greater(other: PosKey): Boolean = if (at != other.at) at > other.at else writer > other.writer
    }

    private data class PosRec(val position: String, val key: PosKey)

    private val positions = HashMap<String, PosRec>()
    private var counter: ULong = 0UL

    fun apply(changes: List<PositionChange>) = changes.forEach(::apply)

    fun apply(c: PositionChange) {
        val rec = PosRec(c.position, PosKey(c.at, c.writer))
        positions[c.pubId] = positions[c.pubId]?.let { later(it, rec) } ?: rec
        if (c.at > counter) counter = c.at
    }

    fun set(pubId: String, position: String, writer: String): PositionChange {
        if (counter < ULong.MAX_VALUE) counter += 1UL
        val c = PositionChange(pubId, position, counter, writer)
        apply(c)
        return c
    }

    fun position(pubId: String): String? = positions[pubId]?.position

    fun encode(): String {
        val sb = StringBuilder("positions:\n")
        for (pub in positions.keys.sorted()) {
            val r = positions.getValue(pub)
            sb.append("  ").append(pub).append('=').append(PsJson.goQuote(r.position))
                .append('@').append(r.key.at.toString()).append(':').append(r.key.writer).append('\n')
        }
        sb.append("counter:").append(counter.toString()).append('\n')
        return sb.toString()
    }

    fun snapshot(): String {
        val sb = StringBuilder("{\"positions\":[")
        positions.keys.sorted().forEachIndexed { i, pub ->
            if (i > 0) sb.append(',')
            val r = positions.getValue(pub)
            sb.append("{\"pub\":").append(PsJson.goJsonString(pub))
                .append(",\"position\":").append(PsJson.goJsonString(r.position))
                .append(",\"at\":").append(r.key.at.toString())
                .append(",\"writer\":").append(PsJson.goJsonString(r.key.writer)).append('}')
        }
        sb.append("],\"counter\":").append(counter.toString()).append('}')
        return sb.toString()
    }

    companion object {
        fun fromSnapshot(json: String): ReadingPositions {
            val r = ReadingPositions()
            for (obj in JsonScan.objectsOf(json, listOf("positions"))) {
                val pub = JsonScan.stringField(obj, "pub") ?: continue
                r.positions[pub] = PosRec(
                    JsonScan.stringField(obj, "position") ?: "",
                    PosKey(PsJson.ulong(obj, "at") ?: 0UL, JsonScan.stringField(obj, "writer") ?: ""),
                )
            }
            r.counter = PsJson.ulong(json, "counter") ?: 0UL
            return r
        }

        private fun later(a: PosRec, b: PosRec): PosRec = when {
            a.key.greater(b.key) -> a
            b.key.greater(a.key) -> b
            a.position >= b.position -> a
            else -> b
        }
    }
}
