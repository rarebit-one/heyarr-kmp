package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.JsonScan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four personal-state CRDTs against heyarr-core's parity vectors. For each
 * vector we decode the exact `json.Marshal(change)` wire form, fold it, and assert
 * byte-identical `Encode()` and `Snapshot()` — the same assertion heyarr-core's own
 * parity_test makes, run against the same committed bytes. We fold in the vector's
 * order AND reversed to prove the port is order-free (the semilattice property);
 * we round-trip each vector's snapshot; and we prove the writer's `encode()` is
 * decoded back to an equal change (so a change this device mints is Go-readable).
 */
class CrdtParityTest {
    private fun forEachVector(
        file: String,
        block: (name: String, changes: List<String>, encode: String, snapshot: String) -> Unit,
    ) {
        val body = Vectors.load(file)
        val vectors = JsonScan.objectsOf(body, listOf("vectors"))
        assertEquals("$file has vectors", true, vectors.isNotEmpty())
        for (v in vectors) {
            block(
                JsonScan.stringField(v, "name") ?: "",
                JsonScan.objectsOf(v, listOf("changes")),
                JsonScan.stringField(v, "encode") ?: "",
                JsonScan.stringField(v, "snapshot") ?: "",
            )
        }
    }

    @Test
    fun playlist() = forEachVector("playlist.json") { name, changes, encode, snapshot ->
        val decoded = changes.map { PlaylistChange.decode(it) }
        for (order in listOf(decoded, decoded.reversed())) {
            val p = Playlist()
            p.apply(order)
            assertEquals("playlist/$name encode", encode, p.encode())
            assertEquals("playlist/$name snapshot", snapshot, p.snapshot())
        }
        assertEquals("playlist/$name fromSnapshot", encode, Playlist.fromSnapshot(snapshot).encode())
        for (c in decoded) assertEquals("playlist/$name writer round-trip", c, PlaylistChange.decode(c.encode()))
    }

    @Test
    fun starred() = forEachVector("starred.json") { name, changes, encode, snapshot ->
        val decoded = changes.map { StarChange.decode(it) }
        for (order in listOf(decoded, decoded.reversed())) {
            val s = StarSet()
            s.apply(order)
            assertEquals("starred/$name encode", encode, s.encode())
            assertEquals("starred/$name snapshot", snapshot, s.snapshot())
        }
        assertEquals("starred/$name fromSnapshot", encode, StarSet.fromSnapshot(snapshot).encode())
        for (c in decoded) assertEquals("starred/$name writer round-trip", c, StarChange.decode(c.encode()))
    }

    @Test
    fun readingpos() = forEachVector("readingpos.json") { name, changes, encode, snapshot ->
        val decoded = changes.map { PositionChange.decode(it) }
        for (order in listOf(decoded, decoded.reversed())) {
            val r = ReadingPositions()
            r.apply(order)
            assertEquals("readingpos/$name encode", encode, r.encode())
            assertEquals("readingpos/$name snapshot", snapshot, r.snapshot())
        }
        assertEquals("readingpos/$name fromSnapshot", encode, ReadingPositions.fromSnapshot(snapshot).encode())
        for (c in decoded) assertEquals("readingpos/$name writer round-trip", c, PositionChange.decode(c.encode()))
    }

    @Test
    fun history() = forEachVector("history.json") { name, changes, encode, snapshot ->
        val decoded = changes.map { PlayChange.decode(it) }
        for (order in listOf(decoded, decoded.reversed())) {
            val l = PlayLog()
            l.apply(order)
            assertEquals("history/$name encode", encode, l.encode())
            assertEquals("history/$name snapshot", snapshot, l.snapshot())
        }
        assertEquals("history/$name fromSnapshot", encode, PlayLog.fromSnapshot(snapshot).encode())
        for (c in decoded) assertEquals("history/$name writer round-trip", c, PlayChange.decode(c.encode()))
    }
}
