package one.rarebit.heyarr.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `hv1:` grammar, held to heyarr-core's `vaultref` (and the referring contract's regex). */
class VaultRefTest {
    private val space = "0192f3a4-5b6c-7d8e-9f01-23456789abcd"
    private val obj = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    @Test
    fun parsesACollectionAndAnObjectRef() {
        val c = VaultRef.parse("hv1:$space")
        assertEquals(space, c.space)
        assertFalse(c.isObject)
        assertNull(c.path)
        assertEquals("hv1:$space", c.toString())

        val o = VaultRef.parse("hv1:$space/$obj")
        assertEquals(obj, o.objectId)
        assertEquals(".jumpdrive/objects/$obj.json", o.path) // Go Ref.Path
        assertEquals("hv1:$space/$obj", o.toString())
    }

    @Test
    fun refusesWhatTheContractOrGoRefuses() {
        listOf(
            "", "hv1:", "hv2:$space", "hv1:$space/", "hv1:$space/$obj/x", " hv1:$space",
            "hv1:${space.uppercase()}", // the contract's pattern is lowercase only
            "hv1:------------------------------------", // 36 chars the pattern allows, not a UUID
            "hv1:0192f3a45b6c7d8e9f0123456789abcd0000", // 36 hex, no dashes
            "$space/$obj",
        ).forEach { assertNull(VaultRef.parseOrNull(it), "should refuse \"$it\"") }
        assertFailsWith<VaultRefException.Malformed> { VaultRef.parse("hv1:nope") }
    }

    @Test
    fun parseSpaceTakesABareIdOrACollectionRefButNotAnObject() {
        assertEquals(space, VaultRef.parseSpace(space))
        assertEquals(space, VaultRef.parseSpace("hv1:$space"))
        assertFailsWith<VaultRefException.Malformed> { VaultRef.parseSpace("hv1:$space/$obj") }
    }

    @Test
    fun aNewObjectIsARandomV4UuidThatParsesBack() {
        val a = VaultRef.newObject(space)
        val b = VaultRef.newObject(space)
        assertTrue(a.objectId!![14] == '4', "version 4: ${a.objectId}")
        assertTrue(a != b)
        assertEquals(a, VaultRef.parse(a.toString()))
    }

    @Test
    fun pathSegmentsAreEncodedLikeUrlEncoderForIds() {
        assertEquals(space, encodePathSegment(space))
        assertEquals("a%2Fb%20c%C3%A9", encodePathSegment("a/b cé"))
    }
}
