package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.device.DeviceEnrolment
import one.rarebit.heyarr.mobile.device.EnrolClient
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.device.InMemoryPendingPairingStore
import one.rarebit.heyarr.mobile.device.PairInvite
import one.rarebit.heyarr.mobile.device.PairingCoordinator
import one.rarebit.heyarr.mobile.device.PairingSteps
import one.rarebit.heyarr.mobile.login.LoginUiState
import one.rarebit.heyarr.mobile.personalstate.DevicePersonalState
import one.rarebit.heyarr.mobile.personalstate.InMemorySpaceRegistry
import one.rarebit.voidbind.Invite
import one.rarebit.voidbind.auth.DeviceCredential
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enrolment half split out of AppViewModel: the paths that need no keyring (a link
 * before the keys are read, a refused link, the 401 hook before attach) behave as the
 * ViewModel's inline code did, and never touch the session side.
 */
class DeviceEnrolmentTest {

    private object NoSteps : PairingSteps {
        private const val WHY = "no pairing in this test"
        private val failed = PairingOutcome.Failed(PairingFailureKind.PROTOCOL, WHY, "")
        override suspend fun handshake(
            inviteQr: String,
            deadlineMillis: Long,
        ): PairingOutcome<PairingSteps.Handshaked> = failed
        override suspend fun receive(deadlineMillis: Long): PairingOutcome<String> = failed
        override suspend fun register(op: String): EnrolClient.Outcome = EnrolClient.Outcome.Failed(WHY)
    }

    /** Records every session-side call, so a test can assert enrolment made none. */
    private class RecordingHost : DeviceEnrolment.Host {
        val calls = mutableListOf<String>()
        override val baseUrl: String = "https://heyarr.example.test:7777"
        override var deviceCredential: DeviceCredential? = null
            set(value) {
                calls += "deviceCredential"
                field = value
            }
        override var credential: Credential? = null
            set(value) {
                calls += "credential"
                field = value
            }
        override fun setLoginState(state: LoginUiState) {
            calls += "login"
        }
        override fun loadSessionAuthority() {
            calls += "authority"
        }
        override fun loadLibrary() {
            calls += "library"
        }
        override fun startGuestBrowsing() {
            calls += "guest"
        }
        override fun signOut() {
            calls += "signOut"
        }
    }

    private val noHttp = object : HttpTransport {
        override fun get(url: String, headers: Map<String, String>): HttpResponse = error("no network in this test")
        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse = error("no network in this test")
    }

    private val host = RecordingHost()
    private val enrolment = DeviceEnrolment(
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        pairing = PairingCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
            store = InMemoryPendingPairingStore(),
            steps = { NoSteps },
        ),
        rawTransport = noHttp,
        host = host,
    )

    private val invite = Invite.encode(
        "http://192.0.2.10:8788",
        "sess",
        ByteArray(32) { it.toByte() },
        "ed25519:" + "ab".repeat(32),
    )

    @Test fun startsLoadingWithNothingParked() {
        assertEquals(EnrolUiState.Loading, enrolment.enrolState.value)
        assertNull(enrolment.parkedInvite.value)
        assertNull(enrolment.keyring)
    }

    @Test fun anInvalidLinkIsShownWithTheParsersReason() {
        enrolment.receiveInviteLink("not an invite")
        val expected = (PairInvite.check("not an invite") as PairInvite.Invalid).message
        assertEquals(EnrolUiState.Error(null, expected), enrolment.enrolState.value)
        assertTrue("no session-side effect", host.calls.isEmpty())
    }

    @Test fun aRejectedLinkSaysWhy() {
        enrolment.rejectInviteLink("that link was not for this app")
        assertEquals(EnrolUiState.Error(null, "that link was not for this app"), enrolment.enrolState.value)
    }

    @Test fun aValidLinkBeforeTheKeysAreReadIsParkedThenDiscardable() {
        enrolment.receiveInviteLink(invite)
        assertEquals("parked until the keys are read", invite, enrolment.parkedInvite.value)
        assertEquals(EnrolUiState.Loading, enrolment.enrolState.value)
        enrolment.discardParkedInvite()
        assertNull(enrolment.parkedInvite.value)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun theUnauthorizedHookBeforeAttachLetsTheRetryGoAhead() {
        assertTrue("no keyring: nothing learned, retry", enrolment.refreshMembership())
        assertTrue(host.calls.isEmpty())
    }

    @Test fun personalStateNeedsTheDeviceKeys() {
        val ps = DevicePersonalState(noHttp, InMemorySpaceRegistry(), keyring = { null })
        assertNull(ps.coordinator("https://heyarr.example.test:7777", Credential.Guest))
    }
}
