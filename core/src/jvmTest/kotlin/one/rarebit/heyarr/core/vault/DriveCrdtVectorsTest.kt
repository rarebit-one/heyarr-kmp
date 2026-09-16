package one.rarebit.heyarr.core.vault

import one.rarebit.heyarr.core.net.JsonScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-language golden-vector test for the drive CRDT. Vectors in
 * `resources/vault/crdt_vectors.json` are Go-marshalled DriveChanges plus the Go
 * Snapshot() bytes and List() a converged drive yields. Asserts the Kotlin [Drive]
 * parses the wire changes, merges to the same live tree, produces byte-identical
 * snapshots, converges regardless of apply order, and round-trips its own snapshot.
 */
class DriveCrdtVectorsTest {

    private fun resource(path: String): String =
        DriveCrdtVectorsTest::class.java.getResourceAsStream(path)?.readBytes()?.decodeToString()
            ?: fail("missing test resource $path")

    private data class Live(val path: String, val blob: String, val conflicted: Boolean)

    private fun liveOf(d: Drive) = d.list().map { Live(it.path, it.blob, it.conflicted) }

    @Test
    fun mergesGoChanges() {
        val root = JsonScan.rootObject(resource("/vault/crdt_vectors.json"))!!
        val cases = JsonScan.objectsOf(JsonScan.arrayOf(root, listOf("cases"))!!, emptyList())
        assertTrue(cases.isNotEmpty(), "no crdt vectors loaded")

        for (caseObj in cases) {
            val name = JsonScan.stringField(caseObj, "name") ?: "?"
            val changes = JsonScan.objectsOf(JsonScan.arrayOf(caseObj, listOf("changes")) ?: "[]", emptyList())
                .map { Drive.parseChange(it) }
            val expectedSnapshot = JsonScan.stringField(caseObj, "snapshot") ?: fail("[$name] no snapshot")
            val expectedList = JsonScan.objectsOf(JsonScan.arrayOf(caseObj, listOf("list")) ?: "[]", emptyList())
                .map { Live(JsonScan.stringField(it, "path")!!, JsonScan.stringField(it, "blob")!!, JsonScan.boolField(it, "conflicted") ?: false) }

            val expectedResolved = JsonScan.objectsOf(JsonScan.arrayOf(caseObj, listOf("resolved")) ?: "[]", emptyList())
                .map { Live(JsonScan.stringField(it, "path")!!, JsonScan.stringField(it, "blob")!!, JsonScan.boolField(it, "conflicted") ?: false) }

            val d = Drive()
            d.apply(changes)
            assertEquals(expectedList, liveOf(d), "[$name] live tree")
            assertEquals(expectedSnapshot, d.snapshot(), "[$name] snapshot bytes")
            assertEquals(expectedResolved, d.resolved().map { Live(it.path, it.blob, it.conflicted) }, "[$name] resolved tree")

            // Order independence: reversed apply yields the same converged tree + snapshot.
            val rev = Drive()
            rev.apply(changes.reversed())
            assertEquals(liveOf(d), liveOf(rev), "[$name] convergence (reversed order)")
            assertEquals(d.snapshot(), rev.snapshot(), "[$name] snapshot converges (reversed order)")

            // Snapshot round-trips through the Kotlin decoder.
            assertEquals(liveOf(d), liveOf(Drive.fromSnapshot(d.snapshot())), "[$name] snapshot round-trip")
            assertEquals(d.snapshot(), Drive.fromSnapshot(d.snapshot()).snapshot(), "[$name] snapshot idempotent")
        }
    }

    @Test
    fun skipsMalformedChanges() {
        val d = Drive()
        d.apply(
            DriveChange(op = DriveOp.PUT, path = "bad", blob = "not-a-hash", at = 1, writer = "w"),   // bad blob → skipped
            DriveChange(op = DriveOp.DELETE, path = "x", blob = "blake3:" + "0".repeat(64), at = 2, writer = "w"), // delete carrying a blob → skipped
            DriveChange(op = DriveOp.PUT, path = "ok", blob = "blake3:" + "a".repeat(64), size = 1, at = 3, writer = "w"), // valid
        )
        assertEquals(listOf("ok"), d.list().map { it.path }, "only the valid change should land")
    }
}
