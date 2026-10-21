package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.desktop.state.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The heartbeat's reading of one probe: a refused credential must not be reported as an
 * unreachable node, and a refused guest must not be reported as a refused credential.
 */
class ConnectionTest {
    @Test fun probeStatusMapsToConnection() {
        val enrolled = ClientMode.ENROLLED
        assertEquals(Connection.ONLINE, Connection.fromProbe(200, enrolled))
        assertEquals(Connection.UNAUTHORIZED, Connection.fromProbe(401, enrolled))
        assertEquals(Connection.UNAUTHORIZED, Connection.fromProbe(403, enrolled))
        assertEquals(Connection.OFFLINE, Connection.fromProbe(0, enrolled))
        assertEquals(Connection.OFFLINE, Connection.fromProbe(502, enrolled))
        assertEquals(Connection.OFFLINE, Connection.fromProbe(404, enrolled))
    }

    // heyarr-kmp#185: a guest probe carries no Authorization header, so its 401/403 is the
    // node declining this network as a guest network — never "refused the token".
    @Test fun aGuestRefusalIsNotACredentialRefusal() {
        val guest = ClientMode.GUEST
        assertEquals(Connection.ONLINE, Connection.fromProbe(200, guest))
        assertEquals(Connection.GUEST_REFUSED, Connection.fromProbe(401, guest))
        assertEquals(Connection.GUEST_REFUSED, Connection.fromProbe(403, guest))
        assertEquals(Connection.OFFLINE, Connection.fromProbe(0, guest))
    }
}
