package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import kotlin.test.*

class RemoveWorkTest {
    @Test fun removesOnlySelectedWorkWithCredential() {
        val deleted = mutableListOf<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String,String>) = HttpResponse(200, """{"sources":[]}""")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String,String>) = error("No subscriptions expected")
            override fun delete(url: String, headers: Map<String,String>): HttpResponse {
                assertEquals("Bearer fixture", headers["Authorization"])
                deleted += url
                return HttpResponse(204, "")
            }
        }
        HeyarrApi(transport, "https://example.test", Credential.Bearer("fixture")).removeWork("selected")
        assertEquals(listOf("https://example.test/api/v1/works/selected"), deleted)
    }
    @Test fun discoveryFailureDoesNotDeleteAnything() {
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String,String>) = HttpResponse(503, "")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String,String>) = error("Must not mutate")
            override fun delete(url: String, headers: Map<String,String>): HttpResponse = error("Must not delete when subscriptions cannot be read")
        }
        assertFails { HeyarrApi(transport, "https://example.test", Credential.Bearer("fixture")).removeWork("selected") }
    }
}
