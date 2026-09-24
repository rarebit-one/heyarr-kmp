package one.rarebit.heyarr.core.vault

import one.rarebit.heyarr.core.net.JsonScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-language KAT: [PersonalStateId] must produce the SAME change/snapshot ids as heyarr-core's
 * `internal/personalstate/protocol` (NewChange/NewSnapshot). The vectors were generated from that Go
 * package. This is the interop the W4 fake-transport tests missed — they echoed the client's id
 * back, so a wrong (blake3-of-ciphertext) id passed; the real node re-derives the framed id and 400s
 * on a mismatch.
 */
class PersonalStateIdTest {

    private fun resource(path: String): String =
        PersonalStateIdTest::class.java.getResourceAsStream(path)?.readBytes()?.decodeToString()
            ?: fail("missing test resource $path")

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun refsOf(v: String): List<String> {
        val arr = JsonScan.arrayOf(v, listOf("refs")) ?: return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < arr.length) {
            if (arr[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < arr.length && arr[i] != '"') {
                    sb.append(arr[i])
                    i++
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }

    @Test
    fun matchesGoGeneratedVectors() {
        val root = JsonScan.rootObject(resource("/vault/personalstate_id_kat.json"))!!
        val vectors = JsonScan.objectsOf(JsonScan.arrayOf(root, listOf("vectors"))!!, emptyList())
        assertTrue(vectors.size >= 6, "expected the full vector set, got ${vectors.size}")
        var changes = 0
        var snapshots = 0
        for (v in vectors) {
            val kind = JsonScan.stringField(v, "kind")!!
            val space = JsonScan.stringField(v, "space_id")!!
            val refs = refsOf(v)
            val cipher = hexToBytes(JsonScan.stringField(v, "cipher_hex")!!)
            val expected = JsonScan.stringField(v, "id")!!
            val got = when (kind) {
                "change" -> {
                    changes++
                    PersonalStateId.changeId(space, refs, cipher)
                }

                "snapshot" -> {
                    snapshots++
                    PersonalStateId.snapshotId(space, refs, cipher)
                }

                else -> fail("unknown vector kind $kind")
            }
            assertEquals(expected, got, "$kind id for space=$space refs=$refs")
        }
        assertTrue(changes > 0 && snapshots > 0, "vectors must cover both changes and snapshots")
    }

    @Test
    fun parentsAreCanonicalisedBeforeHashing() {
        // Unsorted + duplicate + empty parents must hash the same as their canonical form — the id
        // must not depend on the order/dupes a caller passed (matches Go canonicalParents).
        val space = "s1"
        val cipher = byteArrayOf(1, 2, 3, 4, 5)
        val messy = listOf(
            "blake3:bb00000000000000000000000000000000000000000000000000000000000000",
            "",
            "blake3:aa00000000000000000000000000000000000000000000000000000000000000",
            "blake3:bb00000000000000000000000000000000000000000000000000000000000000",
        )
        val canonical = listOf(
            "blake3:aa00000000000000000000000000000000000000000000000000000000000000",
            "blake3:bb00000000000000000000000000000000000000000000000000000000000000",
        )
        assertEquals(PersonalStateId.changeId(space, canonical, cipher), PersonalStateId.changeId(space, messy, cipher))
        assertEquals(canonical, PersonalStateId.canonical(messy))
    }

    @Test
    fun changeAndSnapshotIdsDifferForSameBytes() {
        val space = "s1"
        val cipher = byteArrayOf(9, 9, 9)
        // Different domain separators → different ids over identical (space, refs, ciphertext).
        assertTrue(PersonalStateId.changeId(space, emptyList(), cipher) != PersonalStateId.snapshotId(space, emptyList(), cipher))
    }
}
