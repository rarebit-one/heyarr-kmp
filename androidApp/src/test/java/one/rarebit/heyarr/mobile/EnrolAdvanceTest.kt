package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.DeviceKeyInfo
import one.rarebit.heyarr.mobile.device.EnrolAdvance
import one.rarebit.heyarr.mobile.device.EnrolAdvance.OnInvite
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.device.KeyTier
import one.rarebit.heyarr.mobile.net.CredentialUnavailableException
import one.rarebit.voidwhichbinds.AuthenticationRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val joining = EnrolUiState.Joining(unenrolled, "void-which-binds:pair?v=4")
        assertEquals(OnInvite.PARK, EnrolAdvance.onInvite(joining))
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

    // ── an enrolled phone whose key is locked (heyarr-kmp#243) ─────────────────────

    private val locked = AuthenticationRequiredException("user authentication required")

    @Test
    fun aDismissedPromptLeavesAnEnrolledPhoneLockedNotInError() {
        assertEquals(EnrolUiState.Locked(enrolled), EnrolAdvance.afterAdoptFailed(enrolled, locked))
        // However it reaches us: through the transport's wrapping, or a library wrapper around it.
        assertEquals(
            EnrolUiState.Locked(enrolled),
            EnrolAdvance.afterAdoptFailed(enrolled, CredentialUnavailableException(locked)),
        )
        assertEquals(
            EnrolUiState.Locked(enrolled),
            EnrolAdvance.afterAdoptFailed(enrolled, IllegalStateException("mint failed", locked)),
        )
    }

    @Test
    fun aKeyStoreFaultIsAnErrorThatStillCarriesTheEnrolment() {
        val fault = IllegalStateException("keystore exploded")
        assertEquals(
            EnrolUiState.Error(enrolled, "could not sign with the device key: keystore exploded"),
            EnrolAdvance.afterAdoptFailed(enrolled, fault),
        )
        // Locked only means something with an admission to unlock.
        assertEquals(
            "no admission: an error, not locked",
            EnrolUiState.Error(unenrolled, "could not sign with the device key: user authentication required"),
            EnrolAdvance.afterAdoptFailed(unenrolled, locked),
        )
        assertFalse(EnrolAdvance.isLockedKey(null))
        assertFalse(EnrolAdvance.isLockedKey(fault))
    }

    @Test
    fun aLockedPhoneStillHoldsItsAdmissionSoAnInviteIsRefused() {
        assertEquals(OnInvite.REFUSE, EnrolAdvance.onInvite(EnrolUiState.Locked(enrolled)))
        assertEquals(OnInvite.REFUSE, EnrolAdvance.onInvite(EnrolUiState.Locked(enrolled, unlocking = true)))
    }

    @Test
    fun tryAgainSignsInWithTheAdmissionOnlyWhenThereIsOneAndItIsNotInUse() {
        fun retry(info: DeviceKeyInfo?, pairing: Boolean = false, signedIn: Boolean = false) =
            EnrolAdvance.retryAdopts(info, showingPairing = pairing, signedIn = signedIn)
        assertTrue(retry(enrolled))
        assertFalse("already signed in with it", retry(enrolled, signedIn = true))
        assertFalse("a pairing failure is retried as one", retry(enrolled, pairing = true))
        assertFalse("nothing to sign in with", retry(unenrolled))
        assertFalse(retry(null))
    }

    @Test
    fun settingsNeverDescribesAnEnrolledPhoneAsUnenrolled() {
        val enrolledStates = listOf(
            EnrolUiState.Enrolled(enrolled, "holds an admission", needsAdmin = false),
            EnrolUiState.Locked(enrolled),
            EnrolUiState.Locked(enrolled, unlocking = true),
            EnrolUiState.Error(enrolled, "could not sign with the device key: keystore exploded"),
            EnrolUiState.Removed(enrolled, "Another member of your identity removed this device."),
        )
        for (state in enrolledStates) {
            val summary = EnrolAdvance.deviceSummary(state)
            assertNotNull("$state has its own summary, not the not-enrolled default", summary)
            assertFalse("$state: $summary", summary!!.contains("not enrolled"))
        }
        assertTrue(EnrolAdvance.deviceSummary(EnrolUiState.Locked(enrolled))!!.contains("locked"))
        // A failure before any admission still gets the default (not-enrolled) text.
        assertNull(EnrolAdvance.deviceSummary(EnrolUiState.Error(unenrolled, "relay down")))
        assertNull(EnrolAdvance.deviceSummary(EnrolUiState.Loading))
    }

    @Test
    fun settingsOffersUnlockToALockedPhoneAheadOfSignInToSave() {
        assertEquals("Unlock", EnrolAdvance.deviceAction(locked = true, isGuest = true))
        assertEquals("Sign in to save", EnrolAdvance.deviceAction(locked = false, isGuest = true))
        assertEquals("Device enrolment", EnrolAdvance.deviceAction(locked = false, isGuest = false))
    }
}
