package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.login.LoginTuple
import one.rarebit.heyarr.mobile.login.VoidbindHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoidbindHandoffTest {

    private val tuple = LoginTuple.encode(rp = "http://192.168.16.224:7777", id = "abc123")

    @Test fun loginUriIsTheTuplePlusAnEncodedCallback() {
        assertEquals(
            "voidbind:login?id=abc123&rp=http%3A%2F%2F192.168.16.224%3A7777&callback=heyarr-mobile%3A%2F%2Flogin",
            VoidbindHandoff.loginUri(tuple),
        )
    }

    @Test fun loginUriWithoutCallbackIsExactlyTheQrTuple() {
        assertEquals(tuple, VoidbindHandoff.loginUri(tuple, callback = null))
        // The tuple still decodes with the callback appended (decode is tolerant of extra keys).
        val p = LoginTuple.decode(VoidbindHandoff.loginUri(tuple))
        assertEquals("abc123", p.id)
        assertEquals("http://192.168.16.224:7777", p.rp)
    }

    @Test fun pairUriIsVerbatimAndTyped() {
        val invite = "voidbind:pair?relay=http%3A%2F%2Fh%2Fpair&salt=00&session=s&v=2"
        assertEquals(invite, VoidbindHandoff.pairUri(invite))
        assertThrows(IllegalArgumentException::class.java) { VoidbindHandoff.pairUri(tuple) }
        assertThrows(IllegalArgumentException::class.java) { VoidbindHandoff.loginUri(invite) }
    }

    @Test fun loginUriIsVoidbindClientsDeepLinkShape() {
        // voidbind-client 0.2.0 is the source of truth for the handoff URI (ADR-0003).
        assertEquals(
            one.rarebit.voidwhichbinds.VoidbindDeepLink.loginUriFromTuple(tuple, VoidbindHandoff.CALLBACK_URI),
            VoidbindHandoff.loginUri(tuple),
        )
        val parsed = one.rarebit.voidwhichbinds.VoidbindDeepLink.parse(VoidbindHandoff.loginUri(tuple))
        assertEquals(VoidbindHandoff.CALLBACK_URI, parsed.callback)
    }
}
