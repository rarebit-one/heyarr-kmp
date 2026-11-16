package one.rarebit.heyarr.desktop.open

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.desktop.playback.BlobStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * Downloads an authenticated blob to a local temp file so an [ExternalOpener] can hand it
 * to the system reader/browser. This is a SEPARATE seam from the String-bodied
 * [one.rarebit.heyarr.core.net.HttpTransport]: books and archived articles are BINARY
 * (EPUB/PDF/CBZ/single-file HTML), and decoding those bytes as a UTF-8 String — which is
 * all the shared transport does — corrupts them. So the binary read streams straight to a
 * file here, reusing only the pure [BlobStream] URL builder.
 *
 * The request carries the session's [Credential] (a device's fresh possession proof, a
 * bearer token, or no header for a guest), never a saved bearer string on its own: a
 * desktop enrolled only through Voidbind has no bearer token (heyarr-kmp#208). The
 * credential rides in the `Authorization` header value only and is never logged.
 */
interface BlobDownloader {

    /**
     * Fetch the blob [blobHash] from [baseUrl] (authenticated as [credential]) into
     * a fresh temp file whose name ends in `.[ext]` (so the opener picks the right handler).
     * Blocking; the caller runs it off the UI thread.
     */
    fun download(baseUrl: String, blobHash: String, credential: Credential, ext: String): DownloadResult
}

/** The outcome of a [BlobDownloader.download]. */
sealed interface DownloadResult {
    /** The blob was written to [file]. */
    data class Downloaded(val file: File) : DownloadResult

    /** Nothing was written; [message] is a UI-safe reason (carries NO token). */
    data class Failed(val message: String) : DownloadResult
}

/**
 * The desktop actual, on JDK 17's `java.net.http.HttpClient` (the same client the rest of
 * the desktop uses). Streams the response body to a temp file with `BodyHandlers.ofFile`,
 * so a large book never sits in memory.
 */
class JdkBlobDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(120),
    private val tempDir: File = File(System.getProperty("java.io.tmpdir")),
) : BlobDownloader {

    override fun download(baseUrl: String, blobHash: String, credential: Credential, ext: String): DownloadResult {
        val url = try {
            BlobStream.contentUrl(baseUrl, blobHash)
        } catch (e: IllegalArgumentException) {
            return DownloadResult.Failed("Cannot open this file: ${e.message}.")
        }
        val suffix = if (ext.isBlank()) ".bin" else ".${ext.trimStart('.')}"
        val target = File.createTempFile("heyarr-", suffix, tempDir)
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .apply { credential.asHeader().forEach { (name, value) -> header(name, value) } }
                .GET()
                .build()
            val resp = client.send(request, BodyHandlers.ofFile(target.toPath()))
            if (resp.statusCode() == 200) {
                target.deleteOnExit()
                DownloadResult.Downloaded(target)
            } else {
                target.delete()
                // Never echo the response body — it could carry the request/URL back.
                DownloadResult.Failed("Download failed: HTTP ${resp.statusCode()}.")
            }
        } catch (e: Exception) {
            target.delete()
            DownloadResult.Failed("Download failed: ${e.javaClass.simpleName}.")
        }
    }
}
