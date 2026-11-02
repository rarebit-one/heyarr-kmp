package one.rarebit.heyarr.desktop

import com.sun.net.httpserver.HttpServer
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.desktop.playback.AuthenticatedMediaProxy
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthenticatedMediaProxyTest {
    private data class Seen(val auth: String?, val range: String?, val query: String?)

    private val requests = LinkedBlockingQueue<Seen>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                requests.add(
                    Seen(
                        it.requestHeaders.getFirst("Authorization"),
                        it.requestHeaders.getFirst("Range"),
                        it.requestURI.rawQuery,
                    ),
                )
                it.responseHeaders.set("Content-Type", "video/mp4")
                it.responseHeaders.set("Accept-Ranges", "bytes")
                val ranged = it.requestHeaders.getFirst("Range") != null
                if (ranged) it.responseHeaders.set("Content-Range", "bytes 2-5/10")
                val body = if (ranged) "2345" else "0123456789"
                it.responseHeaders.set("Content-Length", body.length.toString())
                val size = if (it.requestMethod == "HEAD") -1 else body.length.toLong()
                it.sendResponseHeaders(if (ranged) 206 else 200, size)
                if (it.requestMethod != "HEAD") it.responseBody.write(body.toByteArray())
            }
        }
        start()
    }
    private val origin = "http://127.0.0.1:${server.address.port}"
    private val client = HttpClient.newHttpClient()

    @AfterTest fun cleanup() {
        server.stop(0)
        client.close()
    }

    @Test fun ranges_and_restart_seeks_get_fresh_device_proofs() {
        var proof = "first-proof"
        AuthenticatedMediaProxy({ origin }, { Credential.Device("cert", proof) }).use { proxy ->
            val url = proxy.urlFor("$origin/api/v1/playback/stream/capability?profile=1")
            val ranged = HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=2-5")
                .header("Authorization", "Bearer ignored-downstream-header").GET().build()
            val first = client.send(ranged, HttpResponse.BodyHandlers.ofString())
            assertEquals(206, first.statusCode())
            assertEquals("2345", first.body())
            assertEquals("bytes 2-5/10", first.headers().firstValue("Content-Range").orElse(null))
            assertEquals(Seen("Device cert~first-proof", "bytes=2-5", "profile=1"), requests.poll(5, TimeUnit.SECONDS))

            proof = "renewed-proof"
            val seek = client.send(
                HttpRequest.newBuilder(URI.create("$url?start=90")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, seek.statusCode())
            assertEquals("0123456789", seek.body())
            val secondRequest = requests.poll(5, TimeUnit.SECONDS)
            assertEquals(Seen("Device cert~renewed-proof", null, "profile=1&start=90"), secondRequest)
        }
    }

    @Test fun bearer_and_guest_media_keep_their_authentication_mode() {
        for (credential in listOf(Credential.Bearer("saved-token"), Credential.Guest)) {
            AuthenticatedMediaProxy({ origin }, { credential }).use { proxy ->
                val url = proxy.urlFor("$origin/api/v1/blobs/hash/content")
                val response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, response.statusCode())
                assertEquals(credential.asHeader()["Authorization"], requests.poll(5, TimeUnit.SECONDS)?.auth)
            }
        }
    }

    @Test fun head_preserves_length_without_sending_a_body() {
        AuthenticatedMediaProxy({ origin }, { Credential.Guest }).use { proxy ->
            val url = proxy.urlFor("$origin/api/v1/blobs/hash/content")
            val response = client.send(
                HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertEquals("10", response.headers().firstValue("Content-Length").orElse(null))
            assertEquals("", response.body())
        }
    }

    @Test fun unregistered_paths_and_other_origins_cannot_use_the_credential() {
        var configuredOrigin = origin
        AuthenticatedMediaProxy({ configuredOrigin }, { error("must not read credentials") }).use { proxy ->
            assertFailsWith<IllegalArgumentException> {
                proxy.urlFor("https://other.example/api/v1/blobs/hash/content")
            }
            assertFailsWith<IllegalArgumentException> { proxy.urlFor("$origin/api/v1/desired") }
            val url = proxy.urlFor("$origin/api/v1/blobs/hash/content")
            val unknown = URI.create(url).resolve("/not-registered")
            val missing = client.send(
                HttpRequest.newBuilder(unknown).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(404, missing.statusCode())
            configuredOrigin = "https://another-node.example"
            val oldNode = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(404, oldNode.statusCode())
            assertNull(requests.poll())
        }
    }
}
