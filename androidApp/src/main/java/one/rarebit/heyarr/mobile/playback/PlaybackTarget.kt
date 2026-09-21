package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.core.auth.Credential
import java.util.Locale

/**
 * Everything a player needs to start streaming one item: WHERE the bytes are, WHICH
 * credential authorises the pull, and enough about the media to configure the player
 * (video vs. audio, an optional MIME hint, whether a scrub is honest).
 *
 * A **direct** target's [contentUrl] is the range-capable blob endpoint
 * (`/api/v1/blobs/{hash}/content`, ADR-0013) — the player issues `Range` requests
 * against it and the server answers `206 Partial Content`, which is what lets a
 * scrub seek without pulling the whole file (the M10 win). A **stream** target
 * (heyarr-core #432/#433, `mode: "stream"`) is a fragmented-MP4 the node repackages
 * for this phone as it goes — same origin, same credential on every read.
 *
 * A stream has **no native seek** (no `Content-Length`, no ranges), so [seekable] is
 * false and the player hides the scrubber. But ADR-0069 gives it a *restart* seek:
 * re-request the same HMAC token'd URL with `?start=<seconds>` and ffmpeg restarts
 * from that instant. [restartSeekable] marks a stream that can do this; [streamBaseUrl]
 * is the token URL WITHOUT any `start` (so a re-seek always builds off the base, never
 * off a URL that already carries one) and [streamStartSeconds] is where the current
 * repackage began in the source. [streamUrl] builds the `?start=` URL — appending a
 * query param and never percent-encoding the path token (the #16 `blake3:`→`blake3%3A`
 * trap: the token goes on the wire verbatim).
 *
 * The type is pure and unit-tested; feeding it into a real ExoPlayer/Media3 pipeline
 * is the phone-gated half (a codec + a surface only exist on a device).
 */
data class PlaybackTarget(
    val contentUrl: String,
    val credential: Credential,
    /** A video item drives a video surface; an audio item shows transport-only UI. */
    val isVideo: Boolean,
    /** Optional MIME hint (e.g. `video/mp4`, `audio/mpeg`) to help the player pick a track. */
    val mimeType: String? = null,
    /** False for a node-repackaged progressive stream: no native (range) scrubber. */
    val seekable: Boolean = true,
    /** How this target was chosen — what the banner says when the phone still can't play it. */
    val origin: Origin = Origin.DIRECT_UNPLANNED,
    /** The planner's `reason`, when it gave one (shown on a stream target). */
    val reason: String? = null,
    /**
     * A `mode: stream` target the node can re-serve from a new offset (#433/ADR-0069).
     * The player re-requests [streamBaseUrl] with `?start=` on a user seek and resumes there.
     */
    val restartSeekable: Boolean = false,
    /** The token URL with no `start` param — the base every restart-seek is built from. */
    val streamBaseUrl: String? = null,
    /** Where the current repackage begins in the source, in seconds (0 at first play). */
    val streamStartSeconds: Double = 0.0,
    /**
     * The source's full runtime in seconds, as the plan reported it — the scrubber's
     * total for a stream. A repackage has no `Content-Length` and its own duration is
     * unset until ffmpeg finishes, so without this the player's duration is 0, the
     * playhead sits at the left edge, and a fractional seek resolves to 0 × 0 = the
     * top of the film: dragging the bar anywhere restarted playback. Null for a direct
     * blob (the player reads the container) or a blob whose duration was never probed.
     */
    val sourceDurationSeconds: Double? = null,
    /**
     * External subtitle sidecars (ingested `.srt`/`.vtt` assets) to hand the player as
     * `MediaItem.SubtitleConfiguration`s. Each [Sidecar.url] is the same range-capable
     * blob endpoint the video uses, so Media3 fetches it through the one authenticated
     * data source — the bearer applies unchanged. Empty for audio, movies with no
     * sidecar, or a node that returned none.
     */
    val subtitles: List<Sidecar> = emptyList(),
) {
    /** One external subtitle: where its bytes are, its MIME hint, and how to label it. */
    data class Sidecar(
        val url: String,
        val mimeType: String? = null,
        val language: String? = null,
        val label: String? = null,
    )

    /** Where a target came from, which decides what the player can honestly promise. */
    enum class Origin {
        /** The blob, without asking the node (no asset id, or the node predates the plan contract). */
        DIRECT_UNPLANNED,
        /** The node judged the blob playable as-is against this phone's capabilities. */
        DIRECT_PLANNED,
        /** The node is repackaging the asset into a phone-friendly stream. */
        STREAM,
    }

    /** True when the node understood the plan contract — a re-plan can ask for a stream. */
    val planned: Boolean get() = origin != Origin.DIRECT_UNPLANNED

    /**
     * The same stream target re-pointed at [seconds] into the source (#433). Rebuilds
     * [contentUrl] off [streamBaseUrl] so a second seek never stacks a `?start=` on a
     * URL that already has one. A no-op for a non-restart-seekable target.
     */
    fun atStreamStart(seconds: Double): PlaybackTarget {
        val base = streamBaseUrl ?: return this
        val clamped = seconds.coerceAtLeast(0.0)
        return copy(contentUrl = streamUrl(base, clamped), streamStartSeconds = clamped)
    }

    /** The single `Authorization` header map the data source sends on every range read. */
    fun authHeaders(): Map<String, String> = credential.asHeader()

    companion object {
        /** MIME prefixes heyarr may hand back that a player should treat as video. */
        private const val VIDEO_PREFIX = "video/"

        /**
         * The stream URL for [base] restarted at [startSeconds] (ADR-0069). At or below
         * zero it is [base] unchanged. Otherwise `?start=<seconds>` is appended (with
         * `&` if [base] already carries a query) — the path token is left **verbatim**,
         * never percent-encoded (the #16 double-encode trap). Pure + unit-tested.
         */
        fun streamUrl(base: String, startSeconds: Double): String {
            if (startSeconds <= 0.0) return base
            val sep = if (base.contains('?')) '&' else '?'
            return base + sep + "start=" + formatSeconds(startSeconds)
        }

        /** Seconds as an integer when whole, else up to 3 decimals with no trailing zeros. */
        fun formatSeconds(seconds: Double): String {
            val whole = seconds.toLong()
            if (seconds == whole.toDouble()) return whole.toString()
            return String.format(Locale.ROOT, "%.3f", seconds).trimEnd('0').trimEnd('.')
        }

        /**
         * Decide whether an item is video from the signals a browse row or a playback
         * plan carries — a video MIME wins, otherwise a coarse `kind` (movie, episode
         * or video ⇒ video; everything else ⇒ audio). Pure so the audio-vs-video
         * branch is exercised in CI, not discovered on a device.
         */
        fun looksLikeVideo(mimeType: String?, kind: String?): Boolean {
            mimeType?.lowercase()?.let { if (it.startsWith(VIDEO_PREFIX)) return true }
            if (mimeType?.lowercase()?.startsWith("audio/") == true) return false
            return when (kind?.lowercase()?.trim()) {
                "movie", "episode", "video", "film", "tv", "show" -> true
                else -> false
            }
        }
    }
}
