package one.rarebit.heyarr.desktop.state

import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.ExternalMetadata
import one.rarebit.heyarr.core.state.MetaKey
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The desktop's side of `:core`'s [ExternalMetadata]: its cache under the XDG cache dir, a
 * bare `java.net.http` fetcher with the desktop's User-Agent, and the movie look-up
 * through the node's own discovery provider.
 */
object DesktopExternalMetadata {
    const val USER_AGENT = "heyarr-desktop/0.1 (+https://github.com/rarebit-one/heyarr-desktop)"

    private const val CONNECT_TIMEOUT_S = 8L
    private const val REQUEST_TIMEOUT_S = 15L
    private const val HTTP_OK = 200

    fun defaultCacheDir(): File = File(ArtworkLoader.defaultCacheDir().parentFile, "meta")

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun httpGet(url: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("User-Agent", USER_AGENT)
            .header("Accept", ExternalMetadata.ACCEPT)
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == HTTP_OK) resp.body() else null
    }.getOrNull()

    /** The desktop's instance: its cache dir and fetcher, and always a movie look-up (the v3 movie cache). */
    fun create(
        enabled: () -> Boolean = { true },
        movieLookup: (MetaKey) -> ExternalMeta? = { null },
        cacheDir: File = defaultCacheDir(),
        fetch: (String) -> String? = ::httpGet,
    ): ExternalMetadata = ExternalMetadata(cacheDir, enabled, fetch, movieLookup)

    /** The disabled instance for previews and tests. */
    val NONE: ExternalMetadata = create(enabled = { false }, fetch = { null })
}
