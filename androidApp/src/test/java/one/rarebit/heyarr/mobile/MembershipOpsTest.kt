package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.MembershipOps
import one.rarebit.voidbind.Ed25519Signer
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.auth.DeviceCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * Which ops the device presents beside its credential. The ops are REAL — minted
 * with the library's `MembershipOp.sign` over library-generated keys and evaluated
 * with `Membership.evaluate`, so the "justifying closure" this app picks is what an
 * RP's evaluation actually needs, not a re-derived notion of it.
 */
class MembershipOpsTest {

    /** A JDK-generated Ed25519 key; the raw seed / public key are the last 32 bytes of the PKCS#8 / SPKI encodings. */
    private class Key {
        private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val seed: ByteArray = pair.private.encoded.takeLast(32).toByteArray()
        val pub: ByteArray = pair.public.encoded.takeLast(32).toByteArray()
        val id: String = KeyRef.ed25519(pub).render()
        val signer = Ed25519Signer { JdkEd25519.sign(seed, it) }
    }

    private val genesis = Key()
    private val a = Key()
    private val b = Key()
    private val c = Key()
    private val usr = genesis.id
    private val now = 1_700_000_000L
    private fun enc(fill: Int) = KeyRef.x25519(ByteArray(32) { fill.toByte() }).render()

    /** genesis adds A; A adds B (citing genesis's add as its head); genesis adds C independently. */
    private val addA = MembershipOp.sign(
        genesis.signer,
        genesis.pub,
        usr,
        MembershipOp.Kind.ADD,
        a.id,
        enc(0x12),
        emptyList(),
        now,
    )
    private val headsAfterA = Membership.evaluate(usr, listOf(addA), now + 1).heads
    private val addB = MembershipOp.sign(
        a.signer,
        a.pub,
        usr,
        MembershipOp.Kind.ADD,
        b.id,
        enc(0x13),
        headsAfterA,
        now + 10,
    )
    private val addC = MembershipOp.sign(
        genesis.signer,
        genesis.pub,
        usr,
        MembershipOp.Kind.ADD,
        c.id,
        enc(0x14),
        emptyList(),
        now + 20,
    )

    @Test fun theOpsAreRealAndEvaluate() {
        val view = Membership.evaluate(usr, listOf(addA, addB, addC), now + 30)
        assertTrue(view.isMember(a.id))
        assertTrue(view.isMember(b.id))
        assertTrue(view.isMember(c.id))
        assertTrue(view.rejected.isEmpty())
    }

    @Test fun closureIsTheAdmittingOpAndItsAncestry() {
        assertEquals(Membership.merge(listOf(addA, addB)), MembershipOps.closure(listOf(addA, addB, addC), addB))
        assertEquals(listOf(addA), MembershipOps.closure(listOf(addA, addC), addA)) // a genesis add cites nothing
        // The admitting op is included even when the replica does not list it yet.
        assertEquals(Membership.merge(listOf(addA, addB)), MembershipOps.closure(listOf(addA), addB))
    }

    @Test fun everythingIsPresentedWhileItFits() {
        assertEquals(
            Membership.merge(listOf(addA, addB, addC)),
            MembershipOps.presentable(listOf(addC, addA, addB), addB),
        )
        assertEquals(Membership.merge(listOf(addA, addB, addC)), MembershipOps.presentable(listOf(addC, addA), addB)) // own op always rides
        assertEquals(emptyList<String>(), MembershipOps.presentable(emptyList(), null))
    }

    @Test fun overTheCapTheJustifyingClosureComesFirst() {
        val out = MembershipOps.presentable(listOf(addA, addB, addC), addB, max = 2)
        assertEquals(Membership.merge(listOf(addA, addB)), out.sorted().let { Membership.merge(it) })
        assertEquals(2, out.size)
        // And what is presented still finds B a member on its own.
        assertTrue(Membership.evaluate(usr, out, now + 30).isMember(b.id))
    }

    @Test fun aJunkTokenInTheReplicaIsSkippedNotFatal() {
        val out = MembershipOps.closure(listOf("junk", addA, addB), addB)
        assertEquals(Membership.merge(listOf(addA, addB)), out)
        assertTrue(MembershipOps.presentable(listOf("junk", addA), addB, max = 2).containsAll(listOf(addA, addB)))
    }

    @Test fun headerValueIsCommaJoinedAndEmptyWhenNothing() {
        assertEquals("", MembershipOps.headerValue(emptyList(), null))
        assertEquals(
            Membership.merge(listOf(addA, addB)).joinToString(","),
            MembershipOps.headerValue(listOf(addB), addA),
        )
        assertEquals(DeviceCredential.MAX_PRESENTED_OPS, MembershipOps.MAX)
        // The library's own header formatter agrees while the replica fits.
        assertEquals(
            DeviceCredential.membershipHeaderValue(listOf(addA, addB, addC)),
            MembershipOps.headerValue(listOf(addA, addB, addC), addB),
        )
    }
}
