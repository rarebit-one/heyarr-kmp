package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.device.MembershipClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** `GET /membership/{usr}` → `{usr, ops}` (heyarr-core ADR-0068); a node without the route teaches nothing. */
class MembershipClientTest {

    private class Fake(private val status: Int, private val body: String) : HttpTransport {
        val urls = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url
            return HttpResponse(status, body)
        }
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = HttpResponse(405, "")
    }

    private val usr = "ed25519:" + "ab".repeat(32)

    @Test fun readsTheOpsTheNodeHolds() {
        val f = Fake(200, """{"usr":"$usr","ops":["A.a","B.b"]}""")
        assertEquals(listOf("A.a", "B.b"), MembershipClient(f, "http://h/").fetch(usr))
        assertEquals("http://h/membership/$usr", f.urls.single()) // the identity rides the path verbatim
    }

    @Test fun anEmptyLogIsAnEmptyList() {
        assertEquals(emptyList<String>(), MembershipClient(Fake(200, """{"usr":"$usr","ops":[]}"""), "http://h").fetch(usr))
        assertEquals(emptyList<String>(), MembershipClient.parse("""{"usr":"x"}"""))
    }

    @Test fun aNodeWithoutTheRouteYieldsNull() {
        assertNull(MembershipClient(Fake(404, "not found"), "http://h").fetch(usr))
        assertNull(MembershipClient(Fake(405, ""), "http://h").fetch(usr))
    }

    @Test fun otherFailuresThrow() {
        assertThrows(IllegalStateException::class.java) { MembershipClient(Fake(500, ""), "http://h").fetch(usr) }
    }
}
