package one.rarebit.heyarr.core.vault

import one.rarebit.heyarr.core.net.JsonScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden-vector test for the drive retention view. Vectors in
 * `resources/vault/retain_vectors.json` are Go DriveChanges plus a policy, a clock, and
 * the blob ids Go's Retain reports unreferenced. Asserts the Kotlin [Drive.retain] agrees.
 */
class DriveRetainTest {

    private fun resource(path: String): String =
        DriveRetainTest::class.java.getResourceAsStream(path)?.readBytes()?.decodeToString()
            ?: fail("missing test resource $path")

    @Test
    fun matchesGoRetention() {
        val root = JsonScan.rootObject(resource("/vault/retain_vectors.json"))!!
        val cases = JsonScan.objectsOf(JsonScan.arrayOf(root, listOf("cases"))!!, emptyList())
        assertTrue(cases.isNotEmpty(), "no retain vectors")

        for (c in cases) {
            val name = JsonScan.stringField(c, "name") ?: "?"
            val changes = JsonScan.objectsOf(JsonScan.arrayOf(c, listOf("changes")) ?: "[]", emptyList())
                .map { Drive.parseChange(it) }
            val policy = RetentionPolicy(
                maxVersionsPerPath = JsonScan.intField(c, "maxVersionsPerPath")!!,
                trashTtlSeconds = JsonScan.longField(c, "trashTtlSeconds")!!,
            )
            val nowUnix = JsonScan.longField(c, "nowUnix")!!
            // unreferenced is an array of strings, not objects; read it directly.
            val expectedBlobs = parseStringArray(JsonScan.arrayOf(c, listOf("unreferenced")) ?: "[]")

            val d = Drive()
            d.apply(changes)
            assertEquals(expectedBlobs, d.retain(policy, nowUnix), "[$name] unreferenced")
        }
    }

    /** Read a JSON array of strings (the readers here are object-oriented; this is scalar). */
    private fun parseStringArray(array: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < array.length) {
            if (array[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < array.length && array[i] != '"') {
                    if (array[i] == '\\' && i + 1 < array.length) { sb.append(array[i + 1]); i += 2 } else { sb.append(array[i]); i++ }
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }
}
