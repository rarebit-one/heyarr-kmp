package one.rarebit.heyarr.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A drive change with an op this client does not know is skipped, as Go's
 * `DriveChange.Validate` rejects it (`unknown op`) and `ApplyReport` skips it (#111). Before,
 * [Drive.parseChange] read any op other than DELETE as a PUT, so an unknown op carrying a
 * well-formed blob entered the Kotlin drive while every Go replica dropped it (§43).
 */
class DriveUnknownOpTest {
    private val blob = "blake3:" + "ab".repeat(32)

    @Test
    fun unknownOpWithAValidBlobDoesNotParseToAPut() {
        val future = """{"op":7,"path":"notes.txt","blob":"$blob","size":3,""" +
            """"at":9,"writer":"w1","base":{"At":0,"Writer":""}}"""
        assertNull(Drive.parseChange(future), "an unknown drive op must not parse to a change")
    }

    @Test
    fun knownOpsStillParse() {
        val put = """{"op":0,"path":"notes.txt","blob":"$blob","at":1,"writer":"w1","base":{"At":0,"Writer":""}}"""
        val del = """{"op":1,"path":"notes.txt","at":2,"writer":"w1","base":{"At":0,"Writer":""}}"""
        assertEquals(DriveOp.PUT, Drive.parseChange(put)?.op)
        assertEquals(DriveOp.DELETE, Drive.parseChange(del)?.op)
    }
}
