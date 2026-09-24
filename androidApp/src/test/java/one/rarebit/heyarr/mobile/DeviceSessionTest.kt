package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.net.DeviceAuthTransport
import one.rarebit.voidbind.Ed25519Signer
import one.rarebit.voidbind.auth.DeviceCredential
import one.rarebit.voidbind.auth.PossessionProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's Device-credential lifecycle over voidbind-client's `DeviceCredential` +
 * `DeviceAuthPolicy` (0.4.0): one proof reused for `reuseForSeconds`, re-minted
 * proactively inside the skew window, and re-minted + retried ONCE on a 401 by
 * [DeviceAuthTransport]. Behaviour pinned here is what the app relied on before the
 * library took the implementation over — the biometric cost must not change.
 */
class DeviceSessionTest {

    private val seed = ByteArray(32) { 0x02 }
    private val cert = "eyJ2IjoyfQ.c2ln" // any opaque token — the proof hashes it as presented
    private var signs = 0
    private val signer = Ed25519Signer {
        signs++
        JdkEd25519.sign(seed, it)
    }

    private fun session(clock: () -> Long, ttl: Long = 120) =
        DeviceCredential(cert, signer, clock, ttlSeconds = ttl, reuseForSeconds = ttl - PossessionProof.SKEW_SECONDS)

    @Test fun reusesAProofUntilTheSkewWindowThenRemints() {
        var now = 1_000_000L
        val s = session({ now })
        val a = s.current()
        assertSame(a, s.current())
        now += 80 // 40s left: still outside the 30s skew → reuse
        assertSame(a, s.current())
        now += 15 // 25s left: inside the skew → proactive re-mint
        val b = s.current()
        assertNotEquals(a, b)
        assertEquals(2, signs)
        assertEquals(now + 120, PossessionProof.parse(b.proof).expiresAt)
    }

    /** Scripted transport: statuses served in order; records the headers it saw. */
    private class Scripted(vararg statuses: Int) : HttpTransport {
        private val queue = ArrayDeque(statuses.toList())
        val seen = ArrayList<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            seen += headers
            return HttpResponse(queue.removeFirst(), "")
        }
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = get(url, headers)
    }

    @Test fun transportRestampsStaleDeviceHeaderAndRetriesOnceOn401() {
        var now = 1_000_000L
        // A ticking clock, so the re-minted proof (deterministic Ed25519) differs by `iat`.
        val s = session({ now++ })
        val stale = Credential.Device(cert, "stale.proof")
        val inner = Scripted(401, 200)
        val t = DeviceAuthTransport(inner, { s })

        val resp = t.get("https://h/api/v1/works", stale.asHeader())
        assertEquals(200, resp.status)
        assertEquals(2, inner.seen.size)
        // Neither attempt carried the client's stale snapshot; the second carried a re-minted proof.
        val first = inner.seen[0]["Authorization"]!!
        val second = inner.seen[1]["Authorization"]!!
        assertTrue(first.startsWith("Device $cert~") && !first.endsWith("stale.proof"))
        assertTrue(second.startsWith("Device $cert~"))
        assertNotEquals(first, second)
        assertEquals(2, signs)
    }

    @Test fun transportSurfacesASecond401AsIs() {
        val s = session({ 5L })
        val inner = Scripted(401, 401, 200)
        val resp = DeviceAuthTransport(inner, { s }).get("u", Credential.Device(cert, "x").asHeader())
        assertEquals(401, resp.status)
        assertEquals(2, inner.seen.size)
    }

    @Test fun transportLeavesBearerAndAnonymousRequestsAlone() {
        val inner = Scripted(401, 401)
        val t = DeviceAuthTransport(inner, { session({ 1L }) })
        assertEquals(401, t.get("u", Credential.Session("tok").asHeader()).status)
        assertEquals(401, t.get("u").status)
        assertEquals("Bearer tok", inner.seen[0]["Authorization"])
        assertEquals(0, signs)
    }

    @Test fun noSessionMeansPassThrough() {
        val inner = Scripted(401)
        val t = DeviceAuthTransport(inner, { null })
        assertEquals(401, t.get("u", Credential.Device(cert, "p").asHeader()).status)
        assertEquals("Device $cert~p", inner.seen[0]["Authorization"])
    }

    /** The app's real window: a 1 h ttl reused for 59.5 min — one prompt an hour, not one per request. */
    @Test fun appHourWindowReusesForTtlMinusSkew() {
        var now = 1_000_000L
        val s = session({ now }, ttl = 3600)
        val a = s.current()
        now += 3600 - 31 // one second outside the skew window → still reused
        assertSame(a, s.current())
        now += 1 // inside → re-mint
        assertNotEquals(a, s.current())
        assertEquals(2, signs)
    }

    @Test fun membershipHeaderIsAbsentUntilProvided() {
        val inner = Scripted(200, 200, 200)
        val s = session({ 5L })
        DeviceAuthTransport(inner, { s }).get("u", Credential.Device(cert, "x").asHeader())
        DeviceAuthTransport(inner, { s }, membership = { "" }).get("u", Credential.Device(cert, "x").asHeader())
        assertFalse(inner.seen[0].containsKey(DeviceAuthTransport.MEMBERSHIP_HEADER))
        assertFalse(inner.seen[1].containsKey(DeviceAuthTransport.MEMBERSHIP_HEADER))
        assertEquals("Voidbind-Membership", DeviceAuthTransport.MEMBERSHIP_HEADER)
    }

    /**
     * The membership hook runs after the first 401 and BEFORE the re-mint: the app uses
     * it to re-read `GET /membership/{usr}`; a device that finds itself removed answers
     * `false`, the 401 stands, and no biometric prompt (no second signature) is spent.
     */
    @Test fun onUnauthorizedRunsBeforeTheRefreshAndCanVetoTheRetry() {
        val s = session({ 5L })
        var calls = 0
        var signsWhenCalled = -1
        val inner = Scripted(401, 200)
        val t = DeviceAuthTransport(inner, { s }, onUnauthorized = {
            calls++
            signsWhenCalled = signs
            false
        })
        val resp = t.get("u", Credential.Device(cert, "x").asHeader())
        assertEquals(401, resp.status)
        assertEquals(1, inner.seen.size) // vetoed: no retry
        assertEquals(1, calls)
        assertEquals(1, signsWhenCalled) // only the first proof had been signed when the hook ran
        assertEquals(1, signs) // and no re-mint followed
    }

    @Test fun onUnauthorizedTrueProceedsToTheSingleRetry() {
        var now = 1_000_000L
        val s = session({ now++ })
        var calls = 0
        val inner = Scripted(401, 401)
        val resp = DeviceAuthTransport(inner, { s }, onUnauthorized = {
            calls++
            true
        }).get("u", Credential.Device(cert, "x").asHeader())
        assertEquals(401, resp.status)
        assertEquals(2, inner.seen.size)
        assertEquals(1, calls) // a second 401 is final — the hook is not consulted again
        assertEquals(2, signs)
    }

    /** Ops learned inside the hook ride the retry: the membership header is re-read per attempt. */
    @Test fun membershipHeaderIsReReadForTheRetry() {
        var now = 1_000_000L
        val s = session({ now++ })
        var ops = "old.op"
        val inner = Scripted(401, 200)
        val t = DeviceAuthTransport(inner, { s }, membership = { ops }, onUnauthorized = {
            ops = "old.op,new.op"
            true
        })
        assertEquals(200, t.get("u", Credential.Device(cert, "x").asHeader()).status)
        assertEquals("old.op", inner.seen[0][DeviceAuthTransport.MEMBERSHIP_HEADER])
        assertEquals("old.op,new.op", inner.seen[1][DeviceAuthTransport.MEMBERSHIP_HEADER])
    }

    @Test fun membershipHeaderNameIsTheLibrarys() {
        assertEquals(DeviceCredential.MEMBERSHIP_HEADER, DeviceAuthTransport.MEMBERSHIP_HEADER)
    }

    @Test fun membershipHeaderRidesDeviceRequestsWhenProvided() {
        val inner = Scripted(401, 200, 200)
        val s = session({ 5L })
        val t = DeviceAuthTransport(inner, { s }, membership = { "m.assertion" })
        t.get("u", Credential.Device(cert, "x").asHeader())
        assertEquals("m.assertion", inner.seen[0][DeviceAuthTransport.MEMBERSHIP_HEADER])
        assertEquals("m.assertion", inner.seen[1][DeviceAuthTransport.MEMBERSHIP_HEADER]) // survives the retry
        t.get("u", Credential.Session("tok").asHeader())
        assertFalse(inner.seen[2].containsKey(DeviceAuthTransport.MEMBERSHIP_HEADER)) // Bearer: untouched
    }
}
