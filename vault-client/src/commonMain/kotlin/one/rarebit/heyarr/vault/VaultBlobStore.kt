package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.auth.Credential

/**
 * The vault's BINARY blob transport — a SEPARATE seam from the String-bodied
 * [one.rarebit.heyarr.core.net.HttpTransport] because ciphertext blobs are raw bytes that
 * decoding as UTF-8 would corrupt (same reason as the desktop's `open/BlobDownloader`). Uploads a
 * ciphertext blob (PUT, content-addressed) and range-reads one for the frame codec.
 *
 * Actuals: the desktop's `JdkVaultBlobStore` (java.net.http, with a streaming file upload) and
 * [UrlConnectionVaultBlobStore] (HttpURLConnection, for Android and any other JVM client).
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
        fun uploadUrl(baseUrl: String, hash: String): String = baseUrl.trimEnd('/') + "/api/v1/vault/blobs/" + hash

        fun contentUrl(baseUrl: String, hash: String): String =
            baseUrl.trimEnd('/') + "/api/v1/blobs/" + hash + "/content"
    }
}

/** The outcome of [VaultBlobStore.putBlob]. */
sealed interface PutResult {
    /** Stored; the server confirms [hash] and the stored ciphertext [size]. */
    data class Stored(val hash: String, val size: Long) : PutResult

    /** Rejected; [message] is a UI-safe reason (carries no token). */
    data class Failed(val message: String) : PutResult
}
