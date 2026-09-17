package one.rarebit.heyarr.desktop.net

import one.rarebit.heyarr.core.net.*

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * The desktop actual of [HttpTransport], built on JDK 17's `java.net.http.HttpClient`
 * — the JVM analog of heyarr-mobile's OkHttp `OkHttpTransport`. No third-party HTTP
 * dependency: the JDK client is enough for the REST calls the client makes, and it
 * keeps the desktop module's dependency surface small (matching the org's "no
 * Retrofit/Ktor" stance).
 *
 * The shared [HttpClient] pools connections and follows normal redirects. [reset]
 * replaces it: the JDK client keeps an HTTP/2 connection whose peer went away with
 * the old network, and a stream that times out does not close the connection under
 * it, so without a fresh client every later request would time out the same way.
 * Bodies are read as UTF-8 strings; the auth header travels in the per-call [headers]
 * map exactly as the mobile transport carries it.
 */
class JdkHttpTransport(
    private val newClient: () -> HttpClient = {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    },
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : HttpTransport {
    @Volatile private var client: HttpClient = newClient()

    override fun reset() { client = newClient() }

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        send(baseRequest(url, headers).GET().build())

    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        send(
            baseRequest(url, headers)
                .header("Content-Type", contentType ?: "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
                .build(),
        )

    override fun patch(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        send(
            baseRequest(url, headers)
                .header("Content-Type", contentType ?: "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: ""))
                .build(),
        )

    override fun delete(url: String, headers: Map<String, String>): HttpResponse =
        send(baseRequest(url, headers).DELETE().build())

    override fun delete(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        send(
            baseRequest(url, headers)
                .header("Content-Type", contentType ?: "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body ?: ""))
                .build(),
        )

    private fun baseRequest(url: String, headers: Map<String, String>): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    private fun send(request: HttpRequest): HttpResponse {
        val resp = client.send(request, BodyHandlers.ofString(Charsets.UTF_8))
        return HttpResponse(resp.statusCode(), resp.body().orEmpty())
    }
}
