package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.preview.Fixtures
import kotlin.test.*

class RemoveWorkTest {
    @Test fun removesOnlySelectedWorkWithCredential() {
        val deleted = mutableListOf<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(200, """{"sources":[]}""")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(200, Fixtures.rpc("""{"sources":[]}"""))
            override fun delete(url: String, headers: Map<String, String>): HttpResponse {
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
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(503, "")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(503, "")
            override fun delete(url: String, headers: Map<String, String>): HttpResponse =
                error("Must not delete when subscriptions cannot be read")
        }
        assertFails {
            HeyarrApi(transport, "https://example.test", Credential.Bearer("fixture")).removeWork("selected")
        }
    }

    @Test fun unfollowsOnlyThisWorkBeforeRemovingIt() {
        val actions = mutableListOf<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = error("Unexpected read")
            override fun post(
                url: String,
                body: String?,
                contentType: String?,
                headers: Map<String, String>,
            ): HttpResponse {
                if (body.orEmpty().contains(
                        "list_followed",
                    )
                ) {
                    return HttpResponse(
                        200,
                        Fixtures.rpc(
                            """{"sources":[{"id":"mine","title":"Mine","work_id":"selected"},{"id":"other","title":"Other","work_id":"untouched"}]}""",
                        ),
                    )
                }
                assertTrue(body.orEmpty().contains("mine"))
                assertFalse(body.orEmpty().contains("other"))
                assertTrue(body.orEmpty().contains("keep_archive"))
                actions += "unfollow"
                return HttpResponse(200, Fixtures.rpc("{}"))
            }
            override fun delete(url: String, headers: Map<String, String>): HttpResponse {
                actions += "remove"
                return HttpResponse(204, "")
            }
        }
        HeyarrApi(transport, "https://example.test", Credential.Bearer("fixture")).removeWork("selected")
        assertEquals(listOf("unfollow", "remove"), actions)
    }
}
