package one.rarebit.heyarr.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HeyarrConfigRelayTest {
    @Test fun relayDefaultsToTheNodesPairMountBeforeTheV1TheWireAdds() {
        assertEquals("http://h:7777/pair", HeyarrConfig(baseUrl = "http://h:7777/").effectiveRelayBase)
        assertEquals(
            "https://relay.example",
            HeyarrConfig(baseUrl = "http://h", relayBaseUrl = "https://relay.example/").effectiveRelayBase,
        )
    }

    /**
     * The composed "Start pairing" URL must land on heyarr-core's voidbind-go relay
     * mount (`/pair/v1/sessions`, heyarr-core #421 / ADR-0066). voidbind-client's
     * `RelayClient.createSession(base)` POSTs `{base}/v1/sessions`, so a base of
     * `/pair/v1` would double up to `/pair/v1/v1/sessions` (404).
     */
    @Test fun composedSessionUrlHitsTheNodesPairV1SessionsRoute() {
        val cfg = HeyarrConfig(baseUrl = "http://192.168.16.224:7777")
        assertEquals("http://192.168.16.224:7777/pair/v1/sessions", cfg.relaySessionsUrl)
        assertEquals(
            "http://192.168.16.224:7777/pair/v1/sessions",
            cfg.effectiveRelayBase.trimEnd('/') + "/v1/sessions", // exactly RelayClient.createSession's composition
        )
        assertEquals(
            "https://relay.example/v1/sessions",
            cfg.copy(relayBaseUrl = "https://relay.example/").relaySessionsUrl,
        )
    }
}
