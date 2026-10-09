package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.login.LoginTuple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LoginTupleTest {

    @Test fun encodesKeysSortedIdBeforeRp() {
        val s = LoginTuple.encode(rp = "https://heyarr.example", id = "abc123")
        assertEquals("void-which-binds:login?id=abc123&rp=https%3A%2F%2Fheyarr.example", s)
    }

    @Test fun roundTrips() {
        val enc = LoginTuple.encode(rp = "https://a.b/c", id = "id-1")
        val p = LoginTuple.decode(enc)
        assertEquals("https://a.b/c", p.rp)
        assertEquals("id-1", p.id)
    }

    @Test fun decodeIsTolerantOfKeyOrder() {
        val p = LoginTuple.decode("void-which-binds:login?rp=https%3A%2F%2Fx.y&id=z")
        assertEquals("https://x.y", p.rp)
        assertEquals("z", p.id)
    }

    @Test fun rejectsWrongScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            LoginTuple.decode("https://not-void-which-binds?id=x&rp=y")
        }
    }

    @Test fun rejectsTheGen1Scheme() {
        // void-which-binds-client 0.11.0 is gen2-only (ADR-0022): a `voidbind:` tuple is refused.
        assertThrows(IllegalArgumentException::class.java) {
            LoginTuple.decode("voidbind:login?id=x&rp=https%3A%2F%2Fx.y")
        }
    }

    @Test fun rejectsMissingField() {
        assertThrows(IllegalArgumentException::class.java) {
            LoginTuple.decode("void-which-binds:login?id=x")
        }
    }
}
