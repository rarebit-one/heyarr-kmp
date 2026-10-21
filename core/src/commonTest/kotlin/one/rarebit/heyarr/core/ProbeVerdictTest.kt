package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.ProbeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who a refused heartbeat blames (heyarr-kmp#185): a 401/403 to a request that carried a
 * credential is that credential's refusal; the same status to a guest request (no
 * `Authorization` header) is the node declining this network as a guest network, and must
 * never read as a refused credential.
 */
class ProbeVerdictTest {

    @Test fun okIsOnlineInEitherMode() {
        assertEquals(ProbeVerdict.ONLINE, ProbeVerdict.of(200, ClientMode.GUEST))
        assertEquals(ProbeVerdict.ONLINE, ProbeVerdict.of(200, ClientMode.ENROLLED))
    }

    @Test fun aGuest401IsAGuestRefusalNotACredentialRefusal() {
        assertEquals(ProbeVerdict.GUEST_REFUSED, ProbeVerdict.of(401, ClientMode.GUEST))
        assertEquals(ProbeVerdict.GUEST_REFUSED, ProbeVerdict.of(403, ClientMode.GUEST))
    }

    @Test fun anEnrolled401IsTheCredentialsRefusal() {
        assertEquals(ProbeVerdict.CREDENTIAL_REFUSED, ProbeVerdict.of(401, ClientMode.ENROLLED))
        assertEquals(ProbeVerdict.CREDENTIAL_REFUSED, ProbeVerdict.of(403, ClientMode.ENROLLED))
    }

    @Test fun noAnswerAndOtherStatusesAreOfflineInEitherMode() {
        for (mode in ClientMode.entries) {
            assertEquals(ProbeVerdict.OFFLINE, ProbeVerdict.of(0, mode), "0 in $mode")
            assertEquals(ProbeVerdict.OFFLINE, ProbeVerdict.of(404, mode), "404 in $mode")
            assertEquals(ProbeVerdict.OFFLINE, ProbeVerdict.of(500, mode), "500 in $mode")
        }
    }
}
