package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.core.auth.Credential
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The credential shim's parsing + caching, with the process spawn faked via the [runner] seam — so
 * the header split and the proof cache are proven without invoking the real `voidbind` CLI.
 */
class VoidbindCliCredentialTest {

    private val sampleOutput =
        "Authorization: Device eyJhbGciOiJFZERTQSJ9.CERT~eyJhbGciOiJFZERTQSJ9.PROOF\n" +
            "Voidbind-Membership: op1,op2\n"

    @Test
    fun parsesBothHeaderLines() {
        val h = VoidbindCliCredential.parseHeaders(sampleOutput)
        assertEquals("Device eyJhbGciOiJFZERTQSJ9.CERT~eyJhbGciOiJFZERTQSJ9.PROOF", h["Authorization"])
        assertEquals("op1,op2", h["Voidbind-Membership"])
    }

    @Test
    fun blankAndMalformedLinesAreSkipped() {
        val h = VoidbindCliCredential.parseHeaders("\nAuthorization: Device X\n\nnot-a-header\n:no-name\n")
        assertEquals(1, h.size)
        assertEquals("Device X", h["Authorization"])
    }

    @Test
    fun credentialSendsBothHeaders() {
        val cred = VoidbindCliCredential("/dev/null", runner = { sampleOutput }).credential()
        val headers = cred.asHeader()
        assertTrue(headers[Credential.HEADER]!!.startsWith("Device "))
        assertEquals("op1,op2", headers["Voidbind-Membership"])
    }

    @Test
    fun cachesWithinTtlThenRemints() {
        val calls = AtomicInteger(0)
        var now = 0L
        val cred = VoidbindCliCredential(
            deviceDir = "/dev/null",
            ttlMs = 1_000,
            clock = { now },
            runner = { calls.incrementAndGet(); sampleOutput },
        ).credential()

        cred.asHeader(); cred.asHeader()
        assertEquals(1, calls.get(), "second call within TTL must hit the cache")

        now = 2_000 // past the TTL
        cred.asHeader()
        assertEquals(2, calls.get(), "past the TTL it must re-mint")
    }

    @Test
    fun outputWithoutAuthorizationFails() {
        val cred = VoidbindCliCredential("/dev/null", runner = { "Voidbind-Membership: op1\n" }).credential()
        assertFailsWith<IllegalArgumentException> { cred.asHeader() }
    }
}
