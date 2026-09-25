package one.rarebit.heyarr.mobile.playback

import androidx.media3.common.MimeTypes

/** The container MIME hint Media3 wants for a server MIME, or null to let it sniff. Pure, unit-tested. */
object MediaMime {
    fun of(mime: String?): String? = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
        "video/mp4", "audio/mp4", "video/quicktime", "audio/m4a", "audio/x-m4a" -> MimeTypes.VIDEO_MP4
        "video/webm", "audio/webm" -> MimeTypes.VIDEO_WEBM
        "video/x-matroska", "video/matroska", "audio/x-matroska" -> MimeTypes.VIDEO_MATROSKA
        "audio/mpeg", "audio/mp3" -> MimeTypes.AUDIO_MPEG
        "audio/flac", "audio/x-flac" -> MimeTypes.AUDIO_FLAC
        "audio/ogg", "application/ogg", "audio/vorbis" -> MimeTypes.AUDIO_OGG
        "audio/aac" -> MimeTypes.AUDIO_AAC
        "audio/wav", "audio/x-wav", "audio/wave" -> MimeTypes.AUDIO_WAV
        else -> null
    }

    /** True for a MIME (or filename) that is audio, so a music tap never opens the video surface. */
    fun isAudio(mime: String?, filename: String? = null): Boolean {
        if (mime?.lowercase()?.startsWith("audio/") == true) return true
        val ext = filename?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in AUDIO_EXTENSIONS
    }

    private val AUDIO_EXTENSIONS =
        setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "alac", "aiff")
}
