package one.rarebit.heyarr.mobile.playback

/**
 * What THIS phone can play, as `POST /api/v1/playback/plan` wants to hear it
 * (heyarr-core #432): the containers Media3 demuxes, the video/audio codecs the
 * device has a decoder for (short names — `h264`, `hevc`, `aac`, `ac3`…), and the
 * tallest frame worth sending. The planner answers `direct` (play the blob as-is) or
 * `stream` (a phone-friendly fMP4 the node repackages), so an honest list here is
 * what stops a 5.1 AC-3 track playing as silence.
 *
 * Pure: the Android `MediaCodecList` probe lives in [MediaCodecCapabilities]; this
 * type, the codec-name mapping and the request body are JVM-tested.
 */
data class ClientCapabilities(
    val containers: List<String> = MEDIA3_CONTAINERS,
    val video: List<String>,
    val audio: List<String>,
    val maxHeight: Int,
) {
    /** The same capabilities with one codec struck off — a decoder that exists but fails. */
    fun without(codec: String): ClientCapabilities =
        copy(video = video.filterNot { it == codec }, audio = audio.filterNot { it == codec })

    /** The plan request body: `{"asset_id", "client": {containers, video, audio, max_height}}`. */
    fun planRequestBody(assetId: String): String = "{\"asset_id\":${quote(assetId)},\"client\":{" +
        "\"containers\":${array(containers)}," +
        "\"video\":${array(video)}," +
        "\"audio\":${array(audio)}," +
        "\"max_height\":$maxHeight}}"

    companion object {
        /** The containers Media3's bundled extractors demux without a remux. */
        val MEDIA3_CONTAINERS = listOf("mp4", "mkv", "webm")

        /**
         * Android `MediaCodec` MIME → the short codec name the planner speaks. Null for a
         * MIME the planner has no name for (it can't route on it anyway).
         */
        fun codecName(mediaCodecMime: String): String? = when (mediaCodecMime.lowercase()) {
            "video/avc" -> "h264"
            "video/hevc" -> "hevc"
            "video/x-vnd.on2.vp8" -> "vp8"
            "video/x-vnd.on2.vp9" -> "vp9"
            "video/av01" -> "av1"
            "video/mp4v-es" -> "mpeg4"
            "video/mpeg2" -> "mpeg2"
            "audio/mp4a-latm" -> "aac"
            "audio/mpeg" -> "mp3"
            "audio/mpeg-l2" -> "mp2"
            "audio/opus" -> "opus"
            "audio/vorbis" -> "vorbis"
            "audio/flac" -> "flac"
            "audio/ac3" -> "ac3"
            "audio/eac3" -> "eac3"
            "audio/eac3-joc" -> "eac3"
            "audio/true-hd" -> "truehd"
            "audio/vnd.dts" -> "dts"
            "audio/vnd.dts.hd" -> "dtshd"
            "audio/raw" -> "pcm"
            "audio/alac" -> "alac"
            else -> null
        }

        /**
         * Fold a list of decodable `MediaCodec` MIMEs into the planner's (video, audio)
         * short-name lists — deduplicated, in first-seen order, unknowns dropped.
         */
        fun fromDecoderMimes(mimes: Iterable<String>, maxHeight: Int): ClientCapabilities {
            val video = LinkedHashSet<String>()
            val audio = LinkedHashSet<String>()
            for (mime in mimes) {
                val name = codecName(mime) ?: continue
                if (mime.startsWith("video/", ignoreCase = true)) {
                    video.add(name)
                } else if (mime.startsWith("audio/", ignoreCase = true)) {
                    audio.add(name)
                }
            }
            return ClientCapabilities(video = video.toList(), audio = audio.toList(), maxHeight = maxHeight)
        }

        private fun array(items: List<String>): String = items.joinToString(",", "[", "]") { quote(it) }

        internal fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.append("\"").toString()
        }
    }
}
