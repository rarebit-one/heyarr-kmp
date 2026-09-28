package one.rarebit.heyarr.mobile.personalstate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An op this client does not know is IGNORED, never coerced to an add (#111, heyarr-core
 * ADR-0100 step 1). Go's `applyOne` switches on the op and does nothing for an unknown one,
 * so a Kotlin replica that folds it as an ADD/STAR grows a phantom entry, and bumps its
 * counter, where every Go replica does not (§43). The parity vectors pin the fold; these
 * pin the decoder directly, including the shape ADR-0100's playlist-name op takes (op 2,
 * no item).
 */
class UnknownOpTest {
    @Test
    fun playlistNameShapedOpDecodesToNothing() {
        val nameOp = """{"Op":2,"ItemID":"","Tag":"t8","Order":{"Counter":40,"Tag":"t8"},"Observed":null}"""
        assertNull("an unknown playlist op must not decode to a change", PlaylistChange.decode(nameOp))
    }

    @Test
    fun unknownPlaylistOpCarryingAnItemDecodesToNothing() {
        val future = """{"Op":200,"ItemID":"phantom","Tag":"t9",""" +
            """"Order":{"Counter":50,"Tag":"t9"},"Observed":["t1"]}"""
        assertNull("an unknown playlist op must not decode to a change", PlaylistChange.decode(future))
    }

    @Test
    fun unknownStarOpDecodesToNothing() {
        val future = """{"Op":200,"ItemID":"phantom","Tag":"s9","At":50,"Observed":["s1"]}"""
        assertNull("an unknown star op must not decode to a change", StarChange.decode(future))
    }

    @Test
    fun knownOpsStillDecode() {
        assertEquals(
            PlaylistOp.ADD,
            PlaylistChange.decode(
                """{"Op":0,"ItemID":"a","Tag":"t1","Order":{"Counter":1,"Tag":"t1"},"Observed":null}""",
            )?.op,
        )
        assertEquals(PlaylistOp.REMOVE, PlaylistChange.decode("""{"Op":1,"ItemID":"a","Observed":["t1"]}""")?.op)
        assertEquals(StarOp.UNSTAR, StarChange.decode("""{"Op":1,"ItemID":"a","Observed":["s1"]}""")?.op)
        // A change with no Op field is op 0 — Go's zero value — exactly as before.
        assertEquals(StarOp.STAR, StarChange.decode("""{"ItemID":"a","Tag":"s1","At":1}""")?.op)
    }
}
