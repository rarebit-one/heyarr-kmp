package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.device.EnrolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolClientTest {

    private class Fake(private val byUrl: Map<String, Int>) : HttpTransport {
        val posts = ArrayList<Triple<String, String?, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse {
            posts += Triple(url, body, headers)
            return HttpResponse(byUrl[url] ?: 404, """{"detail":"nope"}""")
        }
    }

    private val base = "http://h"

    @Test fun selfEnrolRouteWinsWhenPresent() {
        val f = Fake(mapOf("http://h/enrol" to 201))
        val out = EnrolClient(f, base).register("CERT", "PROOF", "phone", Credential.Session("t"))
        assertEquals(EnrolClient.Outcome.Registered("POST /enrol"), out)
        // No ops known (a bare genesis add): the pre-ADR-0068 body, byte for byte.
        assertEquals("""{"cert":"CERT","proof":"PROOF","name":"phone"}""", f.posts.single().second)
    }

    /** ADR-0068: the admitting op rides under `cert` (either token, either field) and the known ops under `ops`. */
    @Test fun selfEnrolPresentsTheKnownOps() {
        val f = Fake(mapOf("http://h/enrol" to 201))
        val out = EnrolClient(f, base).register("OP", "PROOF", "phone", null, ops = listOf("A.a", "OP"))
        assertEquals(EnrolClient.Outcome.Registered("POST /enrol"), out)
        assertEquals("""{"cert":"OP","proof":"PROOF","name":"phone","ops":["A.a","OP"]}""", f.posts.single().second)
    }

    /**
     * A node that predates `ops` decodes strictly (an unknown field is a 400): the
     * body is retried once WITHOUT them, so an app ahead of its server keeps enrolling.
     */
    @Test fun aNodeThatRefusesOpsIsRetriedOnceWithoutThem() {
        val f = object : HttpTransport {
            val bodies = ArrayList<String?>()
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
            override fun post(
                url: String,
                body: String?,
                contentType: String?,
                headers: Map<String, String>,
            ): HttpResponse {
                bodies += body
                return if (body!!.contains(
                        "\"ops\"",
                    )
                ) {
                    HttpResponse(400, """{"detail":"json: unknown field \"ops\""}""")
                } else {
                    HttpResponse(201, "{}")
                }
            }
        }
        val out = EnrolClient(f, base).register("OP", "PROOF", "phone", null, ops = listOf("A.a"))
        assertEquals(EnrolClient.Outcome.Registered("POST /enrol"), out)
        assertEquals(2, f.bodies.size)
        assertEquals("""{"cert":"OP","proof":"PROOF","name":"phone"}""", f.bodies[1])
    }

    @Test fun aSecond400WithoutOpsSurfacesTheDetailOnce() {
        val f = Fake(mapOf("http://h/enrol" to 400))
        val out = EnrolClient(f, base).register("OP", "P", "n", null, ops = listOf("A.a"))
        assertEquals(EnrolClient.Outcome.Failed("nope"), out)
        assertEquals(2, f.posts.size) // with ops, then without — never a third
    }

    @Test fun fallsBackToAdminRouteAndReportsNeedsAdminOn403() {
        val f = Fake(mapOf("http://h/enrol" to 404, "http://h/api/v1/identities/devices" to 403))
        val out = EnrolClient(f, base).register("CERT", "PROOF", "phone", Credential.Session("t"))
        assertTrue(out is EnrolClient.Outcome.NeedsAdmin)
        assertEquals(2, f.posts.size)
        assertEquals("Bearer t", f.posts[1].third["Authorization"])
        assertEquals("""{"cert":"CERT","name":"phone"}""", f.posts[1].second)
    }

    @Test fun adminRouteSuccessRegisters() {
        val f = Fake(mapOf("http://h/enrol" to 405, "http://h/api/v1/identities/devices" to 201))
        assertEquals(
            EnrolClient.Outcome.Registered("POST /api/v1/identities/devices"),
            EnrolClient(f, base).register("C", "P", "n", Credential.Session("t")),
        )
    }

    @Test fun otherSelfEnrolFailureSurfacesDetail() {
        val f = Fake(mapOf("http://h/enrol" to 400))
        assertEquals(EnrolClient.Outcome.Failed("nope"), EnrolClient(f, base).register("C", "P", "n", null))
    }

    /** Issue #41 part 2: the enrolment response carries the recovery encryption PUBLIC key. */
    @Test fun selfEnrolCarriesTheRecoveryEncryptionKey() {
        val f = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(201, """{"recovery_encryption_key":"x25519:abcd"}""")
        }
        val out = EnrolClient(f, base).register("CERT", "PROOF", "phone", null)
        assertEquals(EnrolClient.Outcome.Registered("POST /enrol", "x25519:abcd"), out)
    }

    /** No field (an older node, or an identity with no recovery key) → null, never a crash. */
    @Test fun selfEnrolWithoutRecoveryKeyIsNull() {
        val f = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(204, "")
        }
        assertEquals(
            EnrolClient.Outcome.Registered("POST /enrol", null),
            EnrolClient(f, base).register("C", "P", "n", null),
        )
    }
}
