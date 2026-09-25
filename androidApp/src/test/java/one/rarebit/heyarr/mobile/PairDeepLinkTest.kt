package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.PairDeepLink
import one.rarebit.voidbind.Invite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `heyarr-mobile://pair?invite=…` — Cruciform's reverse same-phone handoff
 * (voidbind-kmp ADR-0006). The invite must come out byte-identical to what the
 * library minted, and everything that is not ours must be ignored, not refused.
 */
class PairDeepLinkTest {
    private val usr = "ed25519:" + "ab".repeat(32)
    private val invite = Invite.encode("http://192.168.16.224:8788", "s3ss10n", ByteArray(32) { it.toByte() }, usr)

    /** Exactly what Cruciform's `RpPairHandoff.percentEncode` produces (RFC 3986, uppercase, no `+`). */
    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' ||
                ch == '~'
            ) {
                append(ch)
            } else {
                append('%').append("0123456789ABCDEF"[c ushr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }

    private val link = "heyarr-mobile://pair?invite=${encode(invite)}"

    @Test fun `the return leg reads a refusal with Cruciform's wording, and is plain done otherwise`() {
        val refused = PairDeepLink.route(
            PairDeepLink.ACTION_VIEW,
            "heyarr-mobile://pair-done?session=abc123&outcome=refused&reason=the%20SAS%20differed%3A%20%3D",
        ) as PairDeepLink.Done
        assertEquals("abc123", refused.session)
        assertTrue(refused.refused)
        assertEquals("the SAS differed: =", refused.reason)
        val done = PairDeepLink.route(
            PairDeepLink.ACTION_VIEW,
            "heyarr-mobile://pair-done?session=abc123",
        ) as PairDeepLink.Done
        assertEquals(PairDeepLink.Done("abc123"), done)
        assertTrue(!done.refused)
    }

    @Test fun `a Cruciform link yields the byte-identical invite`() {
        val r = PairDeepLink.route(PairDeepLink.ACTION_VIEW, link)
        assertEquals(PairDeepLink.Invite(invite), r)
    }

    @Test fun `whitespace around the URI and extra query keys are tolerated`() {
        val r = PairDeepLink.route(PairDeepLink.ACTION_VIEW, "  $link&x=1\n")
        assertEquals(PairDeepLink.Invite(invite), r)
        val first = PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair?x=1&invite=${encode(invite)}")
        assertEquals(PairDeepLink.Invite(invite), first)
    }

    @Test fun `not ours is null, never an error`() {
        assertNull(PairDeepLink.route("android.intent.action.MAIN", link))
        assertNull(PairDeepLink.route(PairDeepLink.ACTION_VIEW, null))
        assertNull(PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://login"))
        assertNull(PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pairing?invite=x"))
        assertNull(PairDeepLink.route(PairDeepLink.ACTION_VIEW, "voidbind:pair?v=3"))
        assertNull(PairDeepLink.route(PairDeepLink.ACTION_VIEW, "https://heyarr-mobile/pair?invite=x"))
    }

    @Test fun `ours but missing or garbled invite is Invalid with a reason`() {
        val none = PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair") as PairDeepLink.Invalid
        assertTrue(none.message, none.message.contains("no invite"))
        val empty = PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair?invite=") as PairDeepLink.Invalid
        assertTrue(empty.message, empty.message.contains("scan", ignoreCase = true))
        val truncated = PairDeepLink.route(
            PairDeepLink.ACTION_VIEW,
            "heyarr-mobile://pair?invite=voidbind%3Apair%3Fv%3D3%2",
        ) as PairDeepLink.Invalid
        assertTrue(truncated.message, truncated.message.contains("garbled"))
    }

    @Test fun `a login tuple or a v2 invite through this door is refused by the library parser`() {
        val login = PairDeepLink.route(
            PairDeepLink.ACTION_VIEW,
            "heyarr-mobile://pair?invite=${encode("voidbind:login?id=a&rp=http%3A%2F%2Fh")}",
        ) as PairDeepLink.Invalid
        assertTrue(login.message, login.message.contains("LOGIN"))
        val v2 = PairDeepLink.route(
            PairDeepLink.ACTION_VIEW,
            "heyarr-mobile://pair?invite=${encode(invite.replace("v=3", "v=2"))}",
        ) as PairDeepLink.Invalid
        assertTrue(v2.message, v2.message.contains("version"))
    }

    @Test fun `percent decoding keeps a plus literal and refuses non-ASCII`() {
        assertEquals("a+b c", PairDeepLink.percentDecode("a+b%20c"))
        assertEquals("é", PairDeepLink.percentDecode("%C3%A9"))
        try {
            PairDeepLink.percentDecode("é")
            throw AssertionError("expected refusal")
        } catch (_: IllegalArgumentException) {}
    }
}
