package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.core.vault.VaultFrame
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * The vault's BINARY blob transport — a SEPARATE seam from the String-bodied
 * [one.rarebit.heyarr.core.net.HttpTransport] because ciphertext blobs are raw bytes that
 * decoding as UTF-8 would corrupt (same reason as `open/BlobDownloader`). Uploads a
 * ciphertext blob (PUT, content-addressed) and range-reads one for the frame codec.
 */
interface VaultBlobStore {
    /** PUT the ciphertext [bytes] at [hash] (`blake3:<hex>`). Idempotent (content-addressed). */
    fun putBlob(baseUrl: String, hash: String, bytes: ByteArray, credential: Credential): PutResult

    /** GET ciphertext bytes `[start, end)` of blob [hash] — the codec's per-frame fetch. */
    fun fetchRange(baseUrl: String, hash: String, start: Long, end: Long, credential: Credential): ByteArray

    /** GET the whole blob [hash] (small blobs like the sealed manifest). */
    fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray

    /** A [VaultFrame.Fetch] bound to one blob, so [VaultFrame.openAll]/openRange can read it. */
    fun fetchFor(baseUrl: String, hash: String, credential: Credential): VaultFrame.Fetch =
        VaultFrame.Fetch { start, end -> fetchRange(baseUrl, hash, start, end, credential) }
}

/** The outcome of [VaultBlobStore.putBlob]. */
sealed interface PutResult {
    /** Stored; the server confirms [hash] and the stored ciphertext [size]. */
    data class Stored(val hash: String, val size: Long) : PutResult

    /** Rejected; [message] is a UI-safe reason (carries no token). */
    data class Failed(val message: String) : PutResult
}

/** The desktop actual on JDK 17's `java.net.http`, mirroring `JdkBlobDownloader`. */
class JdkVaultBlobStore(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(120),
) : VaultBlobStore {

    override fun putBlob(baseUrl: String, hash: String, bytes: ByteArray, credential: Credential): PutResult {
        val builder = HttpRequest.newBuilder(URI.create(uploadUrl(baseUrl, hash)))
            .timeout(requestTimeout)
            .header("Content-Type", "application/octet-stream")
            .PUT(BodyPublishers.ofByteArray(bytes))
        for ((k, v) in credential.asHeader()) builder.header(k, v)
        return try {
            val resp = client.send(builder.build(), BodyHandlers.ofString())
            if (resp.statusCode() == 201) {
                PutResult.Stored(
                    hash = JsonScan.stringField(resp.body(), "hash") ?: hash,
                    size = JsonScan.longField(resp.body(), "size") ?: bytes.size.toLong(),
                )
            } else {
                PutResult.Failed("upload failed: HTTP ${resp.statusCode()}")
            }
        } catch (e: Exception) {
            PutResult.Failed("upload failed: ${e.message}")
        }
    }

    override fun fetchRange(baseUrl: String, hash: String, start: Long, end: Long, credential: Credential): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(contentUrl(baseUrl, hash)))
            .timeout(requestTimeout)
            .header("Range", rangeHeader(start, end))
            .GET()
        for ((k, v) in credential.asHeader()) builder.header(k, v)
        val resp = client.send(builder.build(), BodyHandlers.ofByteArray())
        val code = resp.statusCode()
        require(code == 206 || code == 200) { "vault: range GET of $hash failed: HTTP $code" }
        return resp.body()
    }

    override fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(contentUrl(baseUrl, hash))).timeout(requestTimeout).GET()
        for ((k, v) in credential.asHeader()) builder.header(k, v)
        val resp = client.send(builder.build(), BodyHandlers.ofByteArray())
        require(resp.statusCode() == 200) { "vault: GET of $hash failed: HTTP ${resp.statusCode()}" }
        return resp.body()
    }

    companion object {
        /** Half-open `[start, end)` → an inclusive HTTP byte range `start-(end-1)`. */
        fun rangeHeader(start: Long, end: Long): String {
            require(end > start) { "empty range [$start,$end)" }
            return "bytes=$start-${end - 1}"
        }

        // The blob id `blake3:<64 lowercase hex>` goes into the path VERBATIM — not URL-encoded.
        // Its only non-alphanumeric byte is the ':', which is a legal path-segment char (RFC 3986
        // pchar). URLEncoder would percent-encode it to %3A, and the Go server (go-chi) routes on the
        // RAW path, so `chi.URLParam` would hand `hashing.Parse` a colon-less `blake3%3A…` and it
        // would 400 the upload as a malformed id. The Go CLI sends the literal id (`hash.String()`);
        // we match it byte-for-byte.
        fun uploadUrl(baseUrl: String, hash: String): String =
            baseUrl.trimEnd('/') + "/api/v1/vault/blobs/" + hash

        fun contentUrl(baseUrl: String, hash: String): String =
            baseUrl.trimEnd('/') + "/api/v1/blobs/" + hash + "/content"
    }
}
