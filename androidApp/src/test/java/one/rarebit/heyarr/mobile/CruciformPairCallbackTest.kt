package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.CruciformPairCallback
import one.rarebit.heyarr.mobile.device.PairDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two URIs of the same-phone one-tap enrolment (voidbind-kmp ADR-0008): what we
 * report to Cruciform once we have joined and derived the SAS, and the `pair-done`
 * landing it opens to bring us back. Both are pure string work, and both have to match
 * the filters on the other side exactly — a near-miss is an invisible no-op, which is
 * the failure mode ADR-0006 already shipped once (a scheme-only `<queries>` entry).
 */
class CruciformPairCallbackTest {

    private val dev = "ed25519:" + "cd".repeat(32)

    @Test
    fun buildsTheJoinedReportCruciformFilterMatches() {
        // The `:` of `ed25519:` is reserved, so it travels as `%3A`: the receiver
        // percent-decodes every value before comparing, so the key it compares is the
        // rendering the relay reveal gave it, byte for byte.
        assertEquals(
            "cruciform://pair-joined?session=sessA&dev=ed25519%3A" + "cd".repeat(32) + "&sas=1234567",
            CruciformPairCallback.joinedUri("sessA", dev, "1234567"),
        )
    }

    @Test
    fun percentEncodesEveryValueWithNoPlusForASpace() {
        // A `+` would be ambiguous for a plain percent-decoder; the receiver uses one.
        val uri = CruciformPairCallback.joinedUri("a b&c", dev, "123 4567")
        assertTrue(uri.contains("session=a%20b%26c"))
        assertTrue(uri.contains("sas=123%204567"))
        assertTrue("no form-encoded spaces", !uri.contains("+"))
    }

    @Test
    fun refusesToReportSomethingIncomplete() {
        for (bad in listOf(
            { CruciformPairCallback.joinedUri("", dev, "1234567") },
            { CruciformPairCallback.joinedUri("s", "", "1234567") },
            { CruciformPairCallback.joinedUri("s", dev, "") },
        )) {
            try {
                bad()
                error("expected a refusal")
            } catch (e: IllegalArgumentException) {
                // as expected: never report a half-filled tuple
            }
        }
    }

    // --- the return leg -----------------------------------------------------

    @Test
    fun routesTheDoneLanding() {
        assertEquals(
            PairDeepLink.Done("sessA"),
            PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair-done?session=sessA"),
        )
        assertEquals(
            PairDeepLink.Done(null),
            PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair-done"),
        )
    }

    @Test
    fun theDoneLandingDoesNotStealTheInviteRoute() {
        // `pair-done` shares the `//pair` prefix; the invite route must still win for a
        // real invite, and `pair-done` must not be read as an invite with no `invite=`.
        val invite = PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair?invite=voidbind%3Apair%3Fv%3D3")
        assertTrue(invite is PairDeepLink.Invalid || invite is PairDeepLink.Invite)
        assertTrue(
            PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair-done?session=x") is PairDeepLink.Done,
        )
        assertNull(PairDeepLink.route(PairDeepLink.ACTION_VIEW, "heyarr-mobile://pair-donex?session=x"))
    }
}
