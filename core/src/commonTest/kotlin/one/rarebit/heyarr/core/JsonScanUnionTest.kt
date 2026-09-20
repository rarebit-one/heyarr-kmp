package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.net.JsonScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Coverage for the JsonScan helpers unioned in from the mobile client (Gate A pt1):
 * [JsonScan.stringMap] and [JsonScan.doubleField]. The desktop client never used
 * these, so this guards them on the JVM/common side after the merge.
 */
class JsonScanUnionTest {

    @Test
    fun stringMapReadsFlatStringEntriesInOrder() {
        val body = """{"external_ids":{"tmdb":"42","imdb":"tt7"}}"""
        val obj = JsonScan.rootObject(body)!!
        assertEquals(linkedMapOf("tmdb" to "42", "imdb" to "tt7"), JsonScan.stringMap(obj, "external_ids"))
    }

    @Test
    fun stringMapSkipsNonStringValuesAndHandlesAbsent() {
        val body = """{"m":{"a":"x","n":5,"b":"y"}}"""
        val obj = JsonScan.rootObject(body)!!
        assertEquals(linkedMapOf("a" to "x", "b" to "y"), JsonScan.stringMap(obj, "m"))
        assertEquals(emptyMap(), JsonScan.stringMap(obj, "missing"))
    }

    @Test
    fun doubleFieldReadsBareNumbersOnly() {
        val body = """{"d":6960.5,"i":12,"n":null,"q":"1.5"}"""
        assertEquals(6960.5, JsonScan.doubleField(body, "d"))
        assertEquals(12.0, JsonScan.doubleField(body, "i"))
        assertNull(JsonScan.doubleField(body, "n"))
        assertNull(JsonScan.doubleField(body, "q"))
        assertNull(JsonScan.doubleField(body, "absent"))
    }

    @Test
    fun stringArrayReadsPlainStringElementsInOrder() {
        val body = """{"content_types":["movie","series"]}"""
        assertEquals(listOf("movie", "series"), JsonScan.stringArray(body, "content_types"))
    }

    @Test
    fun stringArrayIsEmptyForAbsentOrEmptyOrNull() {
        assertEquals(emptyList(), JsonScan.stringArray("""{"content_types":[]}""", "content_types"))
        assertEquals(emptyList(), JsonScan.stringArray("""{"content_types":null}""", "content_types"))
        assertEquals(emptyList(), JsonScan.stringArray("""{}""", "content_types"))
    }
}
