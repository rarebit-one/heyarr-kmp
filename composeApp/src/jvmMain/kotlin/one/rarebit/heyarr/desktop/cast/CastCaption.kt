package one.rarebit.heyarr.desktop.cast

import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.music.Track
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal data class CastCaption(
    val url: String,
    val name: String,
    val language: String?,
    val selected: Boolean = false,
) {
    fun metadata(id: Int): Map<String, Any?> = buildMap {
        put("trackId", id)
        put("type", "TEXT")
        put("subtype", "SUBTITLES")
        put("trackContentId", url)
        put("trackContentType", "text/vtt")
        put("name", name)
        language?.let { put("language", it) }
    }

    fun webVtt(offset: Double): ByteArray {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "Caption fetch failed" }
            val bytes = connection.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES) { "Caption file is too large" }
            return WebVttTimestamp.convert(bytes.decodeToString(), offset).encodeToByteArray()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val MAX_BYTES = 4 * 1024 * 1024

        fun from(track: Track, baseUrl: String, mediaUrl: (String) -> String): CastCaption? {
            val hash = track.blobHash
            val extension = track.filename?.substringAfterLast('.')?.lowercase()
            val text = extension in setOf("vtt", "srt") || track.mime in setOf("text/vtt", "application/x-subrip")
            if (hash == null || !track.isPlayable || !text) {
                return null
            }
            val language = track.language?.takeUnless { it == "und" || it.isBlank() }
            val name = language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }
                ?: track.filename ?: "Subtitles"
            return CastCaption(
                mediaUrl(HeyarrApi.blobUrl(baseUrl, hash)),
                if (track.hearingImpaired) "$name (SDH)" else name,
                language,
            )
        }
    }
}
