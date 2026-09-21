package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.core.net.JsonScan

/**
 * A minimal, dependency-free reader for `POST /api/v1/playback/plan` (heyarr-core
 * #432) — kept JVM-testable (no `org.json`, stubbed in unit tests) for the same
 * reason as [one.rarebit.heyarr.mobile.library.WorksJson].
 *
 * The contract: `{ "mode": "direct" | "stream", "url", "mime", "reason",
 * "source": { container, video, audio, width, height } }`. `direct` means play the
 * blob as-is; `stream` means play [Plan.url] as a fragmented-MP4 progressive stream
 * the node repackages for this phone (no seeking in v1). The pre-#432 shape
 * (`decision: "DIRECT"` + `content_url`) is still read as a direct plan so a node
 * mid-rollout cannot make a playable asset look unplayable.
 *
 * When a generated client lands (kotlinx.serialization against the published
 * OpenAPI), swap this for it.
 */
object PlaybackJson {

    /** What the node knows about the bytes it planned for — shown, never computed on. */
    data class Source(
        val container: String?,
        val video: String?,
        val audio: String?,
        val width: Int?,
        val height: Int?,
        /**
         * The source's full runtime in seconds. The ONE field here that is not merely
         * shown: a `stream` plan is a transcode that cannot report its own length until
         * it has finished producing, so ExoPlayer's duration is unset and the scrubber
         * has no total to work against. The node sends the real runtime for exactly
         * this, and the player pins it (see `VideoSession.load`). Absent (`omitempty`)
         * when nothing has probed the blob's duration.
         */
        val durationSeconds: Double?,
    )

    /** The parsed shape of a playback plan response. */
    data class Plan(
        val mode: String?,
        val url: String?,
        val mime: String?,
        val reason: String?,
        val source: Source?,
        /** Pre-#432 fields, read for the transition only. */
        val decision: String? = null,
        val token: String? = null,
    ) {
        /** Play the blob (or [url] when the node names one) as it is. */
        val isDirect: Boolean
            get() = mode?.equals("direct", ignoreCase = true) == true ||
                (mode == null && decision?.equals("DIRECT", ignoreCase = true) == true)

        /** Play [url] as a node-repackaged progressive stream. */
        val isStream: Boolean get() = mode?.equals("stream", ignoreCase = true) == true && !url.isNullOrBlank()
    }

    fun parse(body: String): Plan {
        val root = JsonScan.rootObject(body) ?: body
        val source = JsonScan.objectAt(root, "source")?.let { s ->
            Source(
                container = JsonScan.stringField(s, "container"),
                video = JsonScan.stringField(s, "video"),
                audio = JsonScan.stringField(s, "audio"),
                width = JsonScan.intField(s, "width"),
                height = JsonScan.intField(s, "height"),
                durationSeconds = JsonScan.doubleField(s, "duration_seconds")?.takeIf { it > 0 },
            )
        }
        return Plan(
            mode = JsonScan.stringField(root, "mode"),
            url = JsonScan.firstString(root, listOf("url", "content_url")),
            mime = JsonScan.stringField(root, "mime"),
            reason = JsonScan.stringField(root, "reason"),
            source = source,
            decision = JsonScan.stringField(root, "decision"),
            token = JsonScan.stringField(root, "token"),
        )
    }
}
