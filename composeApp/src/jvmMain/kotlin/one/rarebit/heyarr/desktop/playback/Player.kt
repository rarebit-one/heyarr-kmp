package one.rarebit.heyarr.desktop.playback

/**
 * The playback seam. heyarr serves a work's file as a range-capable blob stream
 * (`GET /api/v1/blobs/{hash}/content`, ADR-0013); on the desktop we do not decode it
 * in-process — we hand the URL and the bearer credential to an external media player.
 * The interface exists so the UI depends on a seam a test can fake, and so a second
 * backend (a different player) is a new [Player], not a rewrite.
 */
interface Player {

    /**
     * Play the blob [blobHash] from [baseUrl], authenticating with [token] (a bearer
     * token, sent as an `Authorization` header to the player, never logged). Blocking:
     * it launches the player process and returns — the caller runs it off the UI thread.
     */
    fun play(baseUrl: String, blobHash: String, token: String): PlayResult

    /**
     * Queue an ordered list of blobs as a single playlist (e.g. a whole album), all
     * authenticated with the one [token]. The default plays only the first (enough for a
     * backend that cannot queue); [MpvPlayer] overrides it to hand mpv the whole ordered
     * list. An empty list is a no-op failure.
     */
    fun playAll(baseUrl: String, blobHashes: List<String>, token: String): PlayResult {
        val first = blobHashes.firstOrNull() ?: return PlayResult.Failed("Nothing to play.")
        return play(baseUrl, first, token)
    }
}

/** The outcome of a [Player.play] — a value the UI renders, never a thrown exception. */
sealed interface PlayResult {
    /** The player process was launched. */
    object Launched : PlayResult

    /** Nothing was launched; [message] is a UI-safe reason (it carries NO token). */
    data class Failed(val message: String) : PlayResult
}

/**
 * The blob-stream URL builder — the desktop twin of heyarr-mobile's
 * `PlaybackClient.blobContentUrl`. Pure and unit-tested: the hash is heyarr's content
 * hash (`blake3:<64 lowercase hex>`) and goes into the path VERBATIM. The server
 * validates that exact shape and answers 400 to a percent-encoded colon
 * (`blake3%3A…`), which is how every live playback used to fail — so anything outside
 * that alphabet is refused here rather than encoded.
 */
object BlobStream {

    private val BLOB_HASH = Regex("^blake3:[0-9a-f]{64}$")

    /** The range-capable content URL for [hash]. Throws [IllegalArgumentException] on a bad hash. */
    fun contentUrl(baseUrl: String, hash: String): String {
        require(BLOB_HASH.matches(hash)) { "not a blob hash: $hash" }
        return baseUrl.trimEnd('/') + "/api/v1/blobs/" + hash + "/content"
    }
}

/**
 * The restart-seek URL builder for a node-repackaged transcode stream (ADR-0069).
 *
 * A `mode: "stream"` plan is fragmented MP4 produced by an ffmpeg that is running
 * right now: no `Content-Length`, no `Range`, so the bytes ahead of the playhead do
 * not exist to be fetched. mpv can seek only inside its demuxer cache, which is why
 * a scrub into unbuffered territory silently does nothing. The server's answer is to
 * restart: re-request the same token URL with `?start=<seconds>` and a fresh ffmpeg
 * seeks the *input* to that instant (cheap — `-ss` before `-i` lands on a keyframe
 * without decoding up to it).
 *
 * Pure and unit-tested, the desktop twin of the phone's `PlaybackTarget.streamUrl`.
 */
object StreamSeek {

    /**
     * [base] — the token URL with NO `start` of its own — restarted at [startSeconds].
     * At or below zero it is [base] unchanged, so the first play is the plain URL the
     * plan gave. Always build off the base, never off a URL that already carries a
     * `start`, or a second seek stacks one param on another. The path token goes on
     * the wire VERBATIM (the `blake3:`→`blake3%3A` trap [BlobStream] exists to avoid).
     */
    fun urlAt(base: String, startSeconds: Double): String {
        if (startSeconds <= 0.0) return base
        val sep = if (base.contains('?')) '&' else '?'
        return base + sep + "start=" + seconds(startSeconds)
    }

    /** Seconds as an integer when whole, else up to 3 decimals with no trailing zeros. */
    fun seconds(value: Double): String {
        val whole = value.toLong()
        if (value == whole.toDouble()) return whole.toString()
        return String.format(java.util.Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    }
}
