package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.vault.PutResult
import one.rarebit.heyarr.vault.VaultBlobStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * PUT a ciphertext blob STREAMING from [file] at [hash] — for large content blobs whose bytes must
 * not sit in memory (the streaming seal writes them to a temp file first). [JdkVaultBlobStore]
 * streams the body off disk; any other store (a test fake) reads the file into memory and
 * delegates to [VaultBlobStore.putBlob]. Idempotent (content-addressed).
 *
 * An extension rather than a member because [VaultBlobStore] is common code (`:vault-client`) and
 * java.nio.file is not.
 */
fun VaultBlobStore.putBlobFile(baseUrl: String, hash: String, file: Path, credential: Credential): PutResult =
    if (this is JdkVaultBlobStore) {
        putFile(baseUrl, hash, file, credential)
    } else {
        putBlob(baseUrl, hash, Files.readAllBytes(file), credential)
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
        val builder = HttpRequest.newBuilder(URI.create(VaultBlobStore.uploadUrl(baseUrl, hash)))
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

    /** The streaming upload behind [putBlobFile]. */
    internal fun putFile(baseUrl: String, hash: String, file: Path, credential: Credential): PutResult {
        // Stream the ciphertext straight off disk — the bytes never sit in memory. Deliberately
        // NO request timeout: a multi-GB blob over a slow link would blow the 120s cap; the
        // connect timeout still bounds establishing the connection.
        val builder = HttpRequest.newBuilder(URI.create(VaultBlobStore.uploadUrl(baseUrl, hash)))
            .header("Content-Type", "application/octet-stream")
            .PUT(BodyPublishers.ofFile(file))
        for ((k, v) in credential.asHeader()) builder.header(k, v)
        return try {
            val resp = client.send(builder.build(), BodyHandlers.ofString())
            if (resp.statusCode() == 201) {
                PutResult.Stored(
                    hash = JsonScan.stringField(resp.body(), "hash") ?: hash,
                    size = JsonScan.longField(resp.body(), "size") ?: Files.size(file),
                )
            } else {
                PutResult.Failed("upload failed: HTTP ${resp.statusCode()}")
            }
        } catch (e: Exception) {
            PutResult.Failed("upload failed: ${e.message}")
        }
    }

    override fun fetchRange(baseUrl: String, hash: String, start: Long, end: Long, credential: Credential): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(VaultBlobStore.contentUrl(baseUrl, hash)))
            .timeout(requestTimeout)
            .header("Range", VaultBlobStore.rangeHeader(start, end))
            .GET()
        for ((k, v) in credential.asHeader()) builder.header(k, v)
        val resp = client.send(builder.build(), BodyHandlers.ofByteArray())
        val code = resp.statusCode()
        require(code == 206 || code == 200) { "vault: range GET of $hash failed: HTTP $code" }
        return resp.body()
    }

    override fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(VaultBlobStore.contentUrl(baseUrl, hash)))
            .timeout(requestTimeout)
            .GET()
        for ((k, v) in credential.asHeader()) builder.header(k, v)
        val resp = client.send(builder.build(), BodyHandlers.ofByteArray())
        require(resp.statusCode() == 200) { "vault: GET of $hash failed: HTTP ${resp.statusCode()}" }
        return resp.body()
    }
}
