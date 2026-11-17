package one.rarebit.heyarr.mobile.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Fetches a PDF to this phone's cache before it is shown. Jetpack's PDF viewer reads a
 * local file descriptor, not a URL, so — unlike Readium, which range-read EPUBs over the
 * blob route — the whole file is downloaded first (#162).
 *
 * The request goes through the app's shared OkHttp, so [one.rarebit.heyarr.mobile.net.AuthInterceptor]
 * stamps the LIVE credential onto it (the same rule as every other blob read: only our
 * node, only under /api/v1/) and the credential is never baked into a file or a URI.
 *
 * Blobs are content-addressed, so a file is cached under its blob hash: reopening a PDF
 * reuses it, and a changed file is a different hash. A download lands in a `.part` file
 * and is renamed only when complete, so a cached file is always a whole one. Only the
 * [keep] most recent PDFs stay; the system may also clear the cache dir at any time.
 */
class PdfDownload(private val client: OkHttpClient, private val dir: File, private val keep: Int = DEFAULT_KEEP) {
    /** A download that did not produce a file, with the reason the reader shows. */
    class Failed(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * The local copy of [url], downloading it unless already cached. [onProgress] gets a
     * whole percentage as it changes, and only when the response declares its length.
     */
    suspend fun fetch(url: String, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, PdfCache.fileNameFor(url))
        if (target.isFile) {
            target.setLastModified(System.currentTimeMillis())
        } else {
            download(url, target, onProgress)
        }
        prune(target.name)
        target
    }

    private suspend fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val part = File(dir, target.name + PART_SUFFIX)
        try {
            client.newCall(request(url)).execute().use { response ->
                val body = bodyOf(response)
                copy(body.byteStream(), part, body.contentLength(), onProgress)
            }
            store(part, target)
        } catch (e: Failed) {
            throw e
        } catch (e: IOException) {
            throw Failed(e.message ?: e.javaClass.simpleName, e)
        } finally {
            // A failed or cancelled download (the reader closed) leaves no partial file behind.
            part.delete()
        }
    }

    private fun request(url: String): Request = try {
        Request.Builder().url(url).get().build()
    } catch (e: IllegalArgumentException) {
        throw Failed("Not a URL: $url", e)
    }

    private fun bodyOf(response: Response): ResponseBody {
        if (!response.isSuccessful) throw Failed("HTTP ${response.code}")
        return response.body ?: throw Failed("empty response")
    }

    private fun store(part: File, target: File) {
        if (!part.renameTo(target)) throw Failed("could not store the file")
    }

    /** Stream [input] into [part], reporting each new whole percentage of [total]; stops when the reader closes. */
    private suspend fun copy(input: InputStream, part: File, total: Long, onProgress: (Int) -> Unit) {
        input.use { part.outputStream().use { output -> pump(input, output, total, onProgress) } }
    }

    private suspend fun pump(input: InputStream, output: OutputStream, total: Long, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var read = 0L
        var lastPercent = -1
        var n = input.read(buffer)
        while (n >= 0) {
            currentCoroutineContext().ensureActive()
            output.write(buffer, 0, n)
            read += n
            val percent = PdfCache.percent(read, total)
            if (percent != null && percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
            n = input.read(buffer)
        }
    }

    private fun prune(current: String) {
        val cached = dir.listFiles().orEmpty().filter { it.isFile }
        val names = PdfCache.toDelete(cached.map { it.name to it.lastModified() }, current, keep)
        cached.filter { it.name in names }.forEach { it.delete() }
    }

    companion object {
        const val DEFAULT_KEEP = 3
        internal const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** The cache's pure rules: the file a URL lands in, what to evict, and progress. Unit-tested. */
object PdfCache {
    private val BLOB_CONTENT = Regex("/api/v1/blobs/blake3:([0-9a-f]{64})/content$")
    private const val PERCENT = 100

    /**
     * `<blake3 hex>.pdf` for a blob-content URL (`/api/v1/blobs/blake3:<hex>/content`), so the
     * name follows the content, not the node's address; any other URL gets a SHA-256 of itself.
     */
    fun fileNameFor(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val hex = BLOB_CONTENT.find(path)?.groupValues?.get(1)
            ?: MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$hex.pdf"
    }

    /**
     * The cached file names to delete: every other `.pdf` beyond the [keep] most recent
     * (counting [current]), and any `.part` left by a download that never finished.
     */
    fun toDelete(files: List<Pair<String, Long>>, current: String, keep: Int): Set<String> {
        val inFlight = current + PdfDownload.PART_SUFFIX
        val stale = files.map { it.first }.filter { it.endsWith(PdfDownload.PART_SUFFIX) && it != inFlight }
        val others = files.filter { it.first != current && it.first.endsWith(".pdf") }
            .sortedByDescending { it.second }
            .drop((keep - 1).coerceAtLeast(0))
            .map { it.first }
        return (stale + others).toSet()
    }

    /** A whole percentage of [read] out of [total], or null when the length is unknown. */
    fun percent(read: Long, total: Long): Int? =
        if (total <= 0) null else (read * PERCENT / total).coerceIn(0, PERCENT.toLong()).toInt()
}
