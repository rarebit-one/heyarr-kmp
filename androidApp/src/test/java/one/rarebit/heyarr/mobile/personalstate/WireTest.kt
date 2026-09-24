package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.vault.PersonalStateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    @Test
    fun mintComputesTheNodeAcceptedIdAndValidates() {
        val ct = "cipher".encodeToByteArray()
        val ch = EncryptedChange.mint("space-1", listOf("blake3:bb", "", "blake3:aa"), ct)
        assertEquals(PersonalStateId.changeId("space-1", listOf("blake3:aa", "blake3:bb"), ct), ch.changeId)
        assertEquals(listOf("blake3:aa", "blake3:bb"), ch.parents) // canonicalised
        assertTrue(ch.validate())
    }

    @Test
    fun encodeRoundTripsThroughParse() {
        val ch = EncryptedChange.mint("s", listOf("blake3:aa"), byteArrayOf(0, 1, 2, 127, -1))
        val back = EncryptedChange.parse(ch.encode())
        assertEquals(ch.changeId, back.changeId)
        assertEquals(ch.spaceId, back.spaceId)
        assertEquals(ch.parents, back.parents)
        assertTrue(ch.ciphertext.contentEquals(back.ciphertext))
        assertTrue(back.validate())
    }

    @Test
    fun aTamperedIdIsRefused() {
        val ch = EncryptedChange.mint("s", emptyList(), "x".encodeToByteArray()).copy(changeId = "blake3:deadbeef")
        assertFalse(ch.validate())
    }

    @Test
    fun headsAreTheUnreferencedTips() {
        val a = EncryptedChange.mint("s", emptyList(), "a".encodeToByteArray())
        val b = EncryptedChange.mint("s", listOf(a.changeId), "b".encodeToByteArray())
        val c = EncryptedChange.mint("s", listOf(a.changeId), "c".encodeToByteArray()) // a fork off a
        assertEquals(listOf(b.changeId, c.changeId).sorted(), Reconcile.heads(listOf(a, b, c)))
        assertEquals(listOf(b.changeId), Reconcile.heads(listOf(a, b)))
    }

    @Test
    fun anEmptyLogTakesTheSnapshotFrontierAsHeads() {
        assertEquals(listOf("blake3:aa"), Reconcile.heads(emptyList(), listOf("blake3:aa")))
    }
}
