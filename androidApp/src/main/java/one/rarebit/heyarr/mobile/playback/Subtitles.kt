package one.rarebit.heyarr.mobile.playback

import java.util.Locale

/**
 * The pure half of subtitle- and audio-track selection — the label a track shows in its
 * picker. The Media3 side (enabling the text renderer, reading `Tracks`, applying a
 * `TrackSelectionOverride`) lives in the player composable and is phone-gated; this
 * name-building is exercised in CI.
 *
 * heyarr's `mov_text` (MP4 tx3g / MKV timed text) tracks surface through Media3's
 * extractors as ordinary TEXT tracks; each may carry a BCP-47 [language] and/or a
 * human [label]. The language name leads, with a meaningful label appended as a variant
 * ("English (Commentary)"). Extractor-generated labels such as `language7` or `und` say
 * nothing and are dropped; with neither field useful, a 1-based ordinal keeps two unnamed
 * tracks distinguishable. Audio tracks (heyarr-kmp#200) use the same rule.
 */
object Subtitles {

    /** The picker label for a track: its language name, any meaningful variant, else "Track N". */
    fun label(language: String?, label: String?, index: Int): String {
        val name = languageName(language)
        val detail = label?.trim()
            ?.takeIf { it.isNotEmpty() && !RE_GENERIC_LABEL.matches(it) && !it.equals("und", ignoreCase = true) }
            // A label that only restates the language ("English", "eng") adds nothing.
            ?.takeUnless {
                name != null &&
                    (it.equals(name, ignoreCase = true) || it.equals(language, ignoreCase = true))
            }
        return when {
            // A sidecar arrives already labelled by [sidecarLabel] ("English (SDH)"): keep it whole.
            name != null && detail != null && detail.startsWith(name, ignoreCase = true) -> detail

            name != null && detail != null -> "$name ($detail)"

            name != null -> name

            detail != null -> detail

            else -> "Track ${index + 1}"
        }
    }

    /**
     * A BCP-47 / ISO-639 code turned into a display name ("en" → "English", "ger" →
     * "German"), or null for no / `und` language. ISO 639-2 codes are mapped to 639-1 first,
     * because Media3 reports Matroska's three-letter tags as they are. A code with no display
     * name comes back as the code itself.
     */
    fun languageName(language: String?): String? {
        val code = language?.trim()?.replace('_', '-')?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        val parts = code.split('-')
        val tag = (listOf(THREE_TO_TWO[parts.first()] ?: parts.first()) + parts.drop(1)).joinToString("-")
        val display = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH)
        return display.takeIf { it.isNotBlank() && it != tag && it != code } ?: code
    }

    /**
     * The picker label a sidecar's filename suffix carries: `….eng.sdh.srt` → "English (SDH)",
     * `….spa.latin-american.srt` → "Spanish (Latin American)", `….en.srt` → "English". Null
     * when the name carries no language, so the caller falls back to an ordinal rather than
     * show a raw filename.
     */
    fun sidecarLabel(filename: String?): String? {
        val match = filename?.substringBeforeLast('.')?.lowercase()?.let { RE_LANG_SUFFIX.find(it) }
        val name = match?.let { languageName(it.groupValues[1]) } ?: return null
        val detail = match.groupValues[2].removePrefix(".").takeIf { it.isNotBlank() }?.let { variant ->
            VARIANT_NAMES[variant] ?: variant.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        }
        return if (detail == null) name else "$name ($detail)"
    }

    /**
     * The audio picker's detail line, codec then channel layout ("E-AC-3 · 5.1"), or null
     * when neither is known. Unknown codecs are left out rather than shown as a raw MIME.
     */
    fun audioDetails(sampleMimeType: String?, channelCount: Int): String? {
        val codec = sampleMimeType?.lowercase()?.let { CODEC_NAMES[it] }
        val channels = when {
            channelCount == 1 -> "Mono"
            channelCount == 2 -> "Stereo"
            channelCount == SURROUND_5_1 -> "5.1"
            channelCount == SURROUND_7_1 -> "7.1"
            channelCount > 0 -> "$channelCount ch"
            else -> null
        }
        return listOfNotNull(codec, channels).joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /**
     * The Media3 MIME for an EXTERNAL subtitle sidecar, by extension — the hint a
     * `MediaItem.SubtitleConfiguration` needs since a blob URL carries no extension.
     * Unknown/absent returns null and Media3 sniffs. `.sub`/`.idx`/`.sup` are omitted
     * deliberately: they are bitmap/paired formats a SubtitleConfiguration can't take
     * from a lone URL, so we let them fall through rather than promise a MIME.
     */
    fun externalMimeType(filename: String?): String? = when (filename?.substringAfterLast('.', "")?.lowercase()) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }

    /** The BCP-47 language a sidecar filename carries (`…S04E01.en.srt` → "en"), or null. */
    fun languageTag(filename: String?): String? {
        val stem = filename?.substringBeforeLast('.')?.lowercase() ?: return null
        val code = RE_LANG_SUFFIX.find(stem)?.groupValues?.get(1) ?: return null
        return THREE_TO_TWO[code] ?: code
    }

    /** A language tag, then an optional variant (`.sdh`, `.forced`, `.latin-american`), at the end of a stem. */
    private val RE_LANG_SUFFIX =
        Regex(
            """\.(en|eng|es|spa|fr|fre|fra|de|ger|deu|it|ita|pt|por|ja|jpn|zh|chi|nl|dut|sv|swe|""" +
                """cs|ces|cze|da|dan|el|ell|gre|fi|fin|hu|hun|ko|kor|no|nor|pl|pol|ro|ron|rum|sk|slk|slo|tr|tur)""" +
                """(\.[a-z]+(?:-[a-z]+)*)?$""",
        )

    /** Media3's audio sample MIME types, by the name a person knows the codec by. */
    private val CODEC_NAMES = mapOf(
        "audio/ac3" to "AC-3",
        "audio/eac3" to "E-AC-3",
        "audio/eac3-joc" to "E-AC-3",
        "audio/true-hd" to "TrueHD",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS",
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/opus" to "Opus",
        "audio/flac" to "FLAC",
    )

    /** Sidecar variants whose name is not just their capitalised words. */
    private val VARIANT_NAMES = mapOf("sdh" to "SDH", "cc" to "CC")

    private const val SURROUND_5_1 = 6
    private const val SURROUND_7_1 = 8

    /** The placeholder labels some extractors invent (`language7`, `Language 2`). */
    private val RE_GENERIC_LABEL = Regex("""(?i)^language[\s_-]*\d+$""")

    private val THREE_TO_TWO = mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
        "ita" to "it", "por" to "pt", "jpn" to "ja", "chi" to "zh", "dut" to "nl", "swe" to "sv",
        "ces" to "cs", "cze" to "cs", "dan" to "da", "ell" to "el", "gre" to "el", "fin" to "fi",
        "hun" to "hu", "kor" to "ko", "nor" to "no", "pol" to "pl", "ron" to "ro", "rum" to "ro",
        "slk" to "sk", "slo" to "sk", "tur" to "tr",
    )
}
