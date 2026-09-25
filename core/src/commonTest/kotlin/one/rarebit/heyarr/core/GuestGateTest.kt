package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.auth.mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which surfaces a guest may use, and the credential → mode mapping that drives it. */
class GuestGateTest {

    @Test fun guestCredentialIsGuestMode() {
        assertEquals(ClientMode.GUEST, Credential.Guest.mode())
    }

    @Test fun everyCredentialledShapeIsEnrolled() {
        assertEquals(ClientMode.ENROLLED, Credential.Bearer("heyarr_x_y").mode())
        assertEquals(ClientMode.ENROLLED, Credential.Session("tok").mode())
        assertEquals(ClientMode.ENROLLED, Credential.Device("cert", "proof").mode())
    }

    @Test fun guestSendsNoAuthorizationHeader() {
        assertTrue(Credential.Guest.asHeader().isEmpty())
        assertTrue(Credential.Bearer("t").asHeader().containsKey(Credential.HEADER))
    }

    @Test fun guestMayBrowsePlayAndSubtitle() {
        for (s in listOf(Surface.BROWSE, Surface.PLAY, Surface.SUBTITLE)) {
            assertTrue(GuestGate.allows(ClientMode.GUEST, s), "guest should be allowed $s")
            assertFalse(GuestGate.isGated(ClientMode.GUEST, s), "guest should not be gated on $s")
        }
    }

    @Test fun guestIsGatedOnWritesAndPersonalSurfaces() {
        for (s in listOf(
            Surface.WANT,
            Surface.FOLLOW,
            Surface.RATE,
            Surface.MONITOR,
            Surface.ACQUIRE,
            Surface.PLAYLISTS,
            Surface.RESUME,
            Surface.HISTORY,
        )) {
            assertTrue(GuestGate.isGated(ClientMode.GUEST, s), "guest should be gated on $s")
            assertFalse(GuestGate.allows(ClientMode.GUEST, s), "guest should not be allowed $s")
        }
    }

    @Test fun enrolledIsGatedOnNothing() {
        for (s in Surface.entries) {
            assertTrue(GuestGate.allows(ClientMode.ENROLLED, s), "enrolled should be allowed $s")
        }
    }
}
