package one.rarebit.heyarr.desktop.games

import one.rarebit.heyarr.desktop.playback.BlobStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.StandardCopyOption
import java.time.Duration

/** Fetches one installer file from the node to a chosen place. A seam, so tests need no node. */
fun interface GameFileFetcher {
    /** Write [file]'s bytes to [target]; null on success, else a UI-safe reason. */
    fun fetch(file: GameFile, target: File): String?
}

/**
 * The real [GameFileFetcher]: `GET /api/v1/blobs/<hash>/content` with the session's own
 * credential headers — asked for per request, so an enrolled device's proof is fresh and a
 * guest on a trusted network sends none, exactly as the rest of the app's requests do —
 * streamed to a `.part` file and moved into place only once complete, so a cancelled or
 * failed download never leaves a truncated installer that looks finished.
 *
 * Unlike the open-externally downloader, the file is kept (an installer is reused for
 * reinstalls) and the timeout is generous: a GOG data part is gigabytes.
 */
class JdkGameFileFetcher(
    private val baseUrl: String,
    private val credentialHeaders: () -> Map<String, String>,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofHours(REQUEST_TIMEOUT_HOURS),
) : GameFileFetcher {

    override fun fetch(file: GameFile, target: File): String? {
        val url = runCatching { BlobStream.contentUrl(baseUrl, file.blobHash) }.getOrElse {
            return "Cannot fetch ${file.name}: ${it.message}."
        }
        val part = File(target.parentFile, target.name + ".part")
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .apply { credentialHeaders().forEach { (k, v) -> header(k, v) } }
                .GET()
                .build()
            val resp = client.send(request, BodyHandlers.ofFile(part.toPath()))
            if (resp.statusCode() != HTTP_OK) {
                part.delete()
                // Never echo the body — it could carry the request back.
                "Downloading ${file.name} failed: HTTP ${resp.statusCode()}."
            } else {
                java.nio.file.Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                null
            }
        } catch (e: java.io.IOException) {
            part.delete()
            "Downloading ${file.name} failed: ${e.javaClass.simpleName}."
        } catch (e: InterruptedException) {
            part.delete()
            Thread.currentThread().interrupt()
            "Downloading ${file.name} was interrupted."
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val REQUEST_TIMEOUT_HOURS = 6L
        const val HTTP_OK = 200
    }
}
