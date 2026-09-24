package one.rarebit.heyarr.mobile.playback

/**
 * Turns what Media3 reports about a stream into an HONEST message, instead of the
 * silent-audio / black-surface it produces on its own. Pure: the `Player.Listener`
 * in [PlayerScreen] flattens `Tracks` into [TrackGroup]s and hands them here, so the
 * "AC-3 5.1 can't be decoded" verdict is unit-tested, not discovered on a phone.
 *
 * Three failure shapes, all real on the Nothing Phone:
 *  - an audio (or video) track group the phone has no decoder for — Media3 keeps
 *    playing the other renderer and says nothing ([assess]);
 *  - a decoder/renderer failure at prepare/first-frame — a black surface with no
 *    error ([describeError]);
 *  - a container the extractor accepts but yields no video frames from (AVI:
 *    H.264 + MP2, heyarr-core #432) — duration shown, picture never arrives
 *    ([noFrameMessage] after [NO_FRAME_GRACE_MS] of READY).
 */
object PlaybackDiagnostics {

    /** Media3's `C.TRACK_TYPE_*` values for the two renderers we report on. */
    const val TYPE_AUDIO = 1
    const val TYPE_VIDEO = 2

    /** How long after READY a video stream may go without a rendered frame before we say so. */
    const val NO_FRAME_GRACE_MS = 5_000L

    /** One `Tracks.Group`, flattened: its renderer type, whether ANY format in it is playable, and the first format's shape. */
    data class TrackGroup(
        val type: Int,
        val supported: Boolean,
        val sampleMime: String?,
        val channels: Int = 0,
        /** The planner's short codec name for [sampleMime], for a re-plan that strikes it off. */
        val codec: String? = sampleMime?.let { ClientCapabilities.codecName(it) },
    )

    /** What the player should tell the user — and, for a re-plan, which codec failed. */
    data class Issue(val message: String, val type: Int, val codec: String?)

    /**
     * The first renderer whose every track group is unsupported. A video item with an
     * audio group it cannot decode is the Yellowstone case; a video group it cannot
     * decode is worse. Returns null when Media3 can play at least one track of each
     * renderer type the stream carries.
     */
    fun assess(groups: List<TrackGroup>, target: PlaybackTarget): Issue? {
        for (type in intArrayOf(TYPE_VIDEO, TYPE_AUDIO)) {
            val ofType = groups.filter { it.type == type }
            if (ofType.isEmpty() || ofType.any { it.supported }) continue
            val first = ofType.first()
            val what = describeFormat(first)
            val label = if (type == TYPE_VIDEO) "Video" else "Audio"
            return Issue("$label is $what, which this phone can't decode — ${remedy(target)}", type, first.codec)
        }
        return null
    }

    /** The banner for a video stream that reached READY but never rendered a frame. */
    fun noFrameMessage(target: PlaybackTarget): String {
        val container = target.mimeType?.let { containerName(it) }
        val what = if (container != null) "This $container file" else "This file"
        return "$what isn't producing any picture on this phone (the container may not be supported) — ${remedy(target)}"
    }

    /**
     * A `PlaybackException` as a sentence: the decoder cases name the codec, the
     * source cases name the transport, the rest carry Media3's own error name.
     */
    fun describeError(errorCodeName: String, message: String?, target: PlaybackTarget): String {
        val base = when {
            errorCodeName.startsWith("ERROR_CODE_DECODER_INIT_FAILED") ||
                errorCodeName.startsWith("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED") ->
                "This phone has no working decoder for this file — ${remedy(target)}"

            errorCodeName.startsWith("ERROR_CODE_DECODING") -> "The decoder failed part-way through this file."

            errorCodeName.startsWith("ERROR_CODE_PARSING") -> "This file's container couldn't be read (${containerName(target.mimeType ?: "")})."

            errorCodeName.startsWith("ERROR_CODE_IO_BAD_HTTP_STATUS") -> "The node refused the stream (HTTP error)."

            errorCodeName.startsWith("ERROR_CODE_IO") -> "The stream from the node broke off."

            else -> "Playback failed."
        }
        val detail = message?.takeIf { it.isNotBlank() }
        return if (detail != null) "$base\n$errorCodeName: $detail" else "$base\n$errorCodeName"
    }

    /** The banner once a re-plan with the failing codec struck off still came back `direct`. */
    fun afterReplanFailed(issue: Issue): String =
        issue.message.substringBefore(" — ") + " — the server offered no phone-friendly stream for it."

    /** What the node can do about it, honestly, given how this target was chosen. */
    private fun remedy(target: PlaybackTarget): String = when (target.origin) {
        PlaybackTarget.Origin.DIRECT_PLANNED -> "asking the server for a phone-friendly stream"
        PlaybackTarget.Origin.STREAM -> "the server's phone-friendly stream didn't help either"
        PlaybackTarget.Origin.DIRECT_UNPLANNED -> "a phone-friendly stream from the server is not available yet"
    }

    /** `audio/ac3` + 6 channels → "AC-3 5.1"; `video/hevc` → "HEVC (H.265)". */
    fun describeFormat(g: TrackGroup): String {
        val codec = codecLabel(g.sampleMime)
        val layout = channelLayout(g.channels)
        return if (g.type == TYPE_AUDIO && layout != null) "$codec $layout" else codec
    }

    private fun codecLabel(mime: String?): String = when (mime?.lowercase()) {
        "audio/ac3" -> "AC-3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3 (Dolby Digital Plus)"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l2" -> "MP2"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC (H.265)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Part 2"
        "video/mpeg2" -> "MPEG-2"
        null, "" -> "an unknown codec"
        else -> mime
    }

    private fun channelLayout(channels: Int): String? = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        0 -> null
        else -> "$channels-channel"
    }

    private fun containerName(mime: String): String = when (mime.lowercase()) {
        "video/x-msvideo", "video/avi" -> "AVI"
        "video/x-matroska", "video/matroska" -> "MKV"
        "video/mp4", "audio/mp4" -> "MP4"
        "video/webm", "audio/webm" -> "WebM"
        "video/quicktime" -> "QuickTime"
        "video/x-ms-wmv", "video/x-ms-asf" -> "WMV"
        "video/x-flv" -> "FLV"
        "video/mpeg", "video/mp2t" -> "MPEG"
        "" -> "unknown"
        else -> mime
    }
}
