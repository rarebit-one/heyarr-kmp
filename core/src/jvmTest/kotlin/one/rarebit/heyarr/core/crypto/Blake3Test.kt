package one.rarebit.heyarr.core.crypto

import one.rarebit.heyarr.core.net.JsonScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BLAKE3 known-answer test against vectors generated from heyarr-core's BLAKE3 (the
 * standard) — input[i] = i % 251, over sizes that span single-chunk (<=1024 B) and
 * multi-chunk tree boundaries, so the chunk chaining AND the parent-node tree are both
 * exercised.
 */
class Blake3Test {

    private fun resource(path: String): String =
        Blake3Test::class.java.getResourceAsStream(path)?.readBytes()?.decodeToString()
            ?: fail("missing test resource $path")

    @Test
    fun matchesGoldenVectors() {
        val root = JsonScan.rootObject(resource("/vault/blake3_kat.json"))!!
        val vectors = JsonScan.objectsOf(JsonScan.arrayOf(root, listOf("vectors"))!!, emptyList())
        assertTrue(vectors.size >= 10, "expected many blake3 vectors")
        for (v in vectors) {
            val size = JsonScan.intField(v, "size")!!
            val expected = JsonScan.stringField(v, "hash")!!
            val input = ByteArray(size) { (it % 251).toByte() }
            assertEquals(expected, Blake3.hashHex(input), "blake3 of $size bytes")
        }
    }

    @Test
    fun knownEmptyAndAbc() {
        assertEquals("blake3:af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", Blake3.hashHex(ByteArray(0)))
        assertEquals("blake3:6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85", Blake3.hashHex("abc".encodeToByteArray()))
    }
}
