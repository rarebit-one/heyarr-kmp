package one.rarebit.heyarr.desktop.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.state.*
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections

/**
 * Authenticated artwork loading. Poster/cover bytes live behind
 * `GET /api/v1/blobs/{hash}/content` with the same bearer as everything else, so no
 * plain image URL can be handed to a widget — this fetches with the credential,
 * decodes off the UI thread, and caches twice: decoded bitmaps in a small in-memory
 * LRU, and raw bytes on disk under the XDG cache dir keyed by hash, so a relaunch shows
 * art instantly. Concurrency is capped so a 200-card grid does not open 200 sockets.
 *
 * Lazy by construction: [rememberArtwork] only starts a fetch when the card that asked
 * is composed — a virtualised grid never fetches what is off-screen.
 */
class ArtworkLoader(
    private val baseUrl: () -> String,
    private val token: () -> String,
    private val cacheDir: File = defaultCacheDir(),
    maxInMemory: Int = 160,
    parallelism: Int = 6,
    /** Test/preview seam: when set, bytes come from here instead of the network. */
    private val fetcher: ((String) -> ByteArray?)? = null,
) {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val gate = Semaphore(parallelism)
    private val memory: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                size > maxInMemory
        },
    )
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** The decoded artwork for [contentPath] (a `/api/v1/blobs/…/content` path), or null when it cannot be had. */
    suspend fun load(contentPath: String): ImageBitmap? {
        memory[contentPath]?.let { return it }
        if (contentPath in failed) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                memory[contentPath]?.let { return@withPermit it }
                val bytes = readDisk(contentPath) ?: fetch(contentPath)?.also { writeDisk(contentPath, it) }
                val bitmap = bytes?.let { decode(it) }
                if (bitmap == null) failed.add(contentPath) else memory[contentPath] = bitmap
                bitmap
            }
        }
    }

    fun peek(contentPath: String): ImageBitmap? = memory[contentPath]

    /** Forget failures (e.g. after the token changed) so cards retry. */
    fun reset() {
        failed.clear()
    }

    private fun fetch(contentPath: String): ByteArray? = runCatching {
        fetcher?.let { return it(contentPath) }
        // An absolute URL is external art (a public cover source): no credential leaves this app for it.
        val external = contentPath.startsWith("http://") || contentPath.startsWith("https://")
        val base = baseUrl().trimEnd('/')
        if (!external && base.isEmpty()) return null
        val builder = HttpRequest.newBuilder(URI.create(if (external) contentPath else base + contentPath))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", DesktopExternalMetadata.USER_AGENT)
        if (!external) builder.header("Authorization", "Bearer " + token())
        val req = builder.GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() == 200) resp.body() else null
    }.getOrNull()

    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

    private fun diskFile(contentPath: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(contentPath.toByteArray())
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) })
    }

    private fun readDisk(contentPath: String): ByteArray? =
        runCatching { diskFile(contentPath).takeIf { it.isFile }?.readBytes() }.getOrNull()

    private fun writeDisk(contentPath: String, bytes: ByteArray) {
        runCatching {
            cacheDir.mkdirs()
            diskFile(contentPath).writeBytes(bytes)
        }
    }

    companion object {
        fun defaultCacheDir(): File {
            val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".cache")
            return File(File(base, "heyarr-desktop"), "artwork")
        }

        /** A loader that never fetches — for tests and screenshots. */
        val NONE = ArtworkLoader({ "" }, { "" }, fetcher = { null })
    }
}

/** Compose binding: starts the (cached, gated) load when composed; null until it lands. */
@Composable
fun ArtworkLoader.rememberArtwork(contentPath: String?): State<ImageBitmap?> {
    val initial = remember(contentPath) { contentPath?.let { peek(it) } }
    return produceState(initialValue = initial, key1 = contentPath) {
        if (contentPath != null && value == null) value = load(contentPath)
    }
}
