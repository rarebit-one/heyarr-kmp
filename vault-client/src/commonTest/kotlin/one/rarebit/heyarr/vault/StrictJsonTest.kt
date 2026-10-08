package one.rarebit.heyarr.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The envelope check, held to what heyarr-core's put-ref (`json.Unmarshal`) accepts and refuses. */
class StrictJsonTest {
    @Test
    fun refusesWhatIsNotExactlyOneJsonValue() {
        listOf(
            """{"v":1 "type":"answer"}""", // missing comma
            """{"v":1,"type":"answer"} x""", // trailing garbage
            """{"v":1,"type":"answer"}{}""",
            """{"v":1,"type":"answer",}""", // trailing comma
            """{"v":01,"type":"a"}""", // leading zero
            """{'v':1}""",
            """{"v":1,"type":"a\x"}""", // bad escape
            "{\"type\":\"a\tb\"}", // raw control character
            """{"v":1,"type":"a"""",
            """{"v":tru}""",
            "",
            "[".repeat(StrictJson.MAX_DEPTH + 2),
        ).forEach { assertFalse(StrictJson.isValid(it), "should refuse: $it") }
        assertTrue(StrictJson.isValid(""" { "v" : 1 , "type" : "aé\n" , "x" : [1, -2.5e3, true, null, {}] } """))
    }

    @Test
    fun readsTheEnvelopeAsGoUnmarshalsIt() {
        // A duplicate key is not an error; the last occurrence wins.
        assertEquals("b", StrictJson.envelope("""{"type":"a","type":"b"}""")!!.type)
        assertNull(StrictJson.envelope("""{"v":1,"v":null}""")!!.v)
        // encoding/json matches field names case-insensitively.
        val folded = StrictJson.envelope("""{"V":1,"TYPE":"answer"}""")!!
        assertEquals(1L, folded.v)
        assertEquals("answer", folded.type)
        // v must be an integer literal and type a string, or Unmarshal fails.
        listOf(
            """{"v":1.0,"type":"a"}""",
            """{"v":1e0,"type":"a"}""",
            """{"v":"1","type":"a"}""",
            """{"v":1,"type":5}""",
        ).forEach { assertTrue(StrictJson.envelope(it)!!.wrongType, it) }
        // Only a top-level object has an envelope; nested v/type are not read.
        assertNull(StrictJson.envelope("[1]"))
        assertNotNull(StrictJson.envelope("""{"x":{"v":2}}""")).also { assertNull(it.v) }
    }
}
