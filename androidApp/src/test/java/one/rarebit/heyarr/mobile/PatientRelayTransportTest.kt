package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.device.PatientRelayTransport
import one.rarebit.voidbind.net.HttpResponse
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.RelayTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay poll stretched to the session TTL: a 404 keeps polling inside ONE library-level
 * GET, a non-404 returns at once, the deadline surfaces as the library's own [RelayTimeout]
 * (so it still classifies as TIMEOUT, apart from unreachable), and an interrupt tears it down.
 */
class PatientRelayTransportTest {

    private class Scripted(vararg statuses: Int) : HttpTransport {
        private val queue = ArrayDeque(statuses.toList())
        var gets = 0
        override fun get(url: String): HttpResponse {
            gets++
            return HttpResponse(queue.removeFirstOrNull() ?: 404, ByteArray(0))
        }
        override fun post(url: String, body: ByteArray?, contentType: String?) = HttpResponse(200, ByteArray(0))
        override fun put(url: String, body: ByteArray, contentType: String?) = HttpResponse(204, ByteArray(0))
        override fun sleep(millis: Long) = Unit
    }

    private val url = "http://relay/v1/sessions/s/initiator/reveal"

    @Test fun `keeps polling through 404s inside one get, then returns the slot`() {
        var now = 0L
        val slept = ArrayList<Long>()
        val inner = Scripted(404, 404, 404, 200)
        val t =
            PatientRelayTransport(inner, deadlineMillis = 600_000, clock = {
                now
            }, pollIntervalMillis = 1_000, sleeper = {
                slept +=
                    it
                now += it
            })
        assertEquals(200, t.get(url).status)
        assertEquals(4, inner.gets)
        assertEquals(listOf(1_000L, 1_000L, 1_000L), slept)
    }

    @Test fun `the deadline surfaces as the library's RelayTimeout, never a 404 to the caller`() {
        var now = 0L
        val inner = Scripted()
        val t =
            PatientRelayTransport(inner, deadlineMillis = 3_000, clock = {
                now
            }, pollIntervalMillis = 1_000, sleeper = {
                now +=
                    it
            })
        val e = assertThrows(RelayTimeout::class.java) { t.get(url) }
        assertTrue(e.message!!.contains("expired"))
        assertEquals("polled at 0, 1, 2, 3 s — then gave up", 4, inner.gets)
    }

    @Test fun `a non-404 answer is returned immediately - a refusal is the library's to classify`() {
        val inner = Scripted(409)
        val t =
            PatientRelayTransport(inner, deadlineMillis = 600_000, clock = { 0 }, sleeper = { error("must not sleep") })
        assertEquals(409, t.get(url).status)
        assertEquals(1, inner.gets)
    }

    @Test fun `an interrupted thread stops the wait`() {
        val inner = Scripted(404)
        val t =
            PatientRelayTransport(inner, deadlineMillis = 600_000, clock = {
                0
            }, sleeper = { Thread.currentThread().interrupt() })
        assertThrows(InterruptedException::class.java) { t.get(url) }
        assertEquals(1, inner.gets)
    }
}
