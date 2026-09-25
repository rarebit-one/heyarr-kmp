package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.DeviceKeyInfo
import one.rarebit.heyarr.mobile.device.EnrolAdvance
import one.rarebit.heyarr.mobile.device.EnrolAdvance.OnInvite
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.device.KeyTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same-phone enrolment asks this app for nothing; cross-device pairing still stops at the code. */
class EnrolAdvanceTest {
    private val unenrolled = DeviceKeyInfo("ed25519:aa", "x25519:bb", KeyTier.TEE, certToken = null)
    private val enrolled = unenrolled.copy(certToken = "op.token")

    @Test
    fun anInviteJoinsWhenTheKeyExistsAndProvisionsWhenItDoesNot() {
        assertEquals(OnInvite.JOIN, EnrolAdvance.onInvite(EnrolUiState.Ready(unenrolled)))
        assertEquals(OnInvite.PROVISION_THEN_JOIN, EnrolAdvance.onInvite(EnrolUiState.Unprovisioned))
        // An earlier failure before any key existed: still nothing to join with — create it.
        assertEquals(OnInvite.PROVISION_THEN_JOIN, EnrolAdvance.onInvite(EnrolUiState.Error(null, "relay down")))
        assertEquals(OnInvite.JOIN, EnrolAdvance.onInvite(EnrolUiState.Error(unenrolled, "relay down")))
    }

    @Test
    fun anInviteIsParkedWhileThePhoneIsBusyAndRefusedOnceEnrolled() {
        assertEquals(OnInvite.PARK, EnrolAdvance.onInvite(EnrolUiState.Loading))
        assertEquals(OnInvite.PARK, EnrolAdvance.onInvite(EnrolUiState.Joining(unenrolled, "voidbind:pair?v=3")))
        assertEquals(OnInvite.PARK, EnrolAdvance.onInvite(EnrolUiState.CompareSas(unenrolled, "482 7316")))
        assertEquals(OnInvite.PARK, EnrolAdvance.onInvite(EnrolUiState.Registering(unenrolled)))
        assertEquals(OnInvite.PARK, EnrolAdvance.onInvite(EnrolUiState.Removed(enrolled, "removed by another member")))
        assertEquals(
            OnInvite.REFUSE,
            EnrolAdvance.onInvite(EnrolUiState.Enrolled(enrolled, "holds an admission", needsAdmin = false)),
        )
    }

    @Test
    fun onlyAnAdmissionTheNodeAcceptedSignsThePhoneInByItself() {
        assertTrue(EnrolAdvance.adoptsOnEnrolled(registered = true, needsAdmin = false, retriable = false))
        assertFalse(EnrolAdvance.adoptsOnEnrolled(registered = false, needsAdmin = true, retriable = false))
        assertFalse(EnrolAdvance.adoptsOnEnrolled(registered = false, needsAdmin = false, retriable = true))
    }
}
