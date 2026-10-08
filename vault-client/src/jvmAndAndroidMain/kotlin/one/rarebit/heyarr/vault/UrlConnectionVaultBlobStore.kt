package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.JsonScan
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A [VaultBlobStore] on `java.net.HttpURLConnection`, which both the JDK and Android ship — so the
 * android app and any other JVM client (the Jumpdrive client) read and write vault blobs without
 * bringing an HTTP library. The desktop keeps its `JdkVaultBlobStore` (java.net.http, absent on
 * Android) for its streaming uploads of large files; objects addressed by ref are small.
 *
 * Every refusal is a [VaultHttpException] carrying the status, so a caller can tell a blob the
 * node cannot serve (404) from access that went away (401/403).
 */
class UrlConnectionVaultBlobStore(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 120_000,
) : VaultBlobStore {

    override fun putBlob(baseUrl: String, hash: String, bytes: ByteArray, credential: Credential): PutResult {
        val conn = open(VaultBlobStore.uploadUrl(baseUrl, hash), "PUT", credential)
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code == HTTP_CREATED) {
                val body = conn.inputStream.use { it.readBytes() }.decodeToString()
                PutResult.Stored(
                    hash = JsonScan.stringField(body, "hash") ?: hash,
                    size = JsonScan.longField(body, "size") ?: bytes.size.toLong(),
                )
            } else {
                PutResult.Failed("upload failed: HTTP $code", status = code)
            }
        } catch (e: IOException) {
            PutResult.Failed("upload failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The bytes `[start, end)`. A node that ignores the Range header answers 200 with the whole
     * blob; the window is then cut from it here (heyarr-core's `blobFetcher.FetchRange` does the
     * same), never returned as if it were the range.
     */
    override fun fetchRange(baseUrl: String, hash: String, start: Long, end: Long, credential: Credential): ByteArray {
        val conn = open(VaultBlobStore.contentUrl(baseUrl, hash), "GET", credential)
        conn.setRequestProperty("Range", VaultBlobStore.rangeHeader(start, end))
        return try {
            when (val code = conn.responseCode) {
                HTTP_PARTIAL -> conn.inputStream.use { readExactly(it, end - start, hash) }

                HttpURLConnection.HTTP_OK -> conn.inputStream.use {
                    skipFully(it, start, hash)
                    readExactly(it, end - start, hash)
                }

                else -> throw VaultHttpException(code, "vault: range GET of $hash failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray {
        val conn = open(VaultBlobStore.contentUrl(baseUrl, hash), "GET", credential)
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw VaultHttpException(code, "vault: GET of $hash failed: HTTP $code")
            }
            conn.inputStream.use { readBounded(it, VaultBlobStore.MAX_WHOLE_BLOB_BYTES, hash) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, credential: Credential): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMillis
        conn.readTimeout = readTimeoutMillis
        conn.instanceFollowRedirects = true
        for ((k, v) in credential.asHeader()) conn.setRequestProperty(k, v)
        return conn
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_PARTIAL = 206
        const val READ_CHUNK = 8192

        fun skipFully(input: InputStream, n: Long, hash: String) {
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw IOException("vault: $hash ended before offset $n")
                    left--
                } else {
                    left -= skipped
                }
            }
        }

        /** All of [input], refused once it passes [max] bytes — never buffered beyond that. */
        fun readBounded(input: InputStream, max: Int, hash: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(READ_CHUNK)
            while (true) {
                val r = input.read(buf)
                if (r < 0) return out.toByteArray()
                if (out.size() + r > max) {
                    throw VaultFrame.IntegrityException(
                        "vault: $hash is larger than the $max bytes a whole-blob read takes",
                    )
                }
                out.write(buf, 0, r)
            }
        }

        fun readExactly(input: InputStream, n: Long, hash: String): ByteArray {
            val out = ByteArray(n.toInt())
            var off = 0
            while (off < out.size) {
                val r = input.read(out, off, out.size - off)
                if (r < 0) throw IOException("vault: $hash ended after $off of $n range bytes")
                off += r
            }
            return out
        }
    }
}
