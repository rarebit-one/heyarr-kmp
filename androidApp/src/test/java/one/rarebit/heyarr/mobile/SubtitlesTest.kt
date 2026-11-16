package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure half of subtitle-track selection — the picker label. */
class SubtitlesTest {

    @Test fun theLanguageLeadsAndAMeaningfulLabelIsTheVariant() {
        assertEquals(
            "English (Director's commentary)",
            Subtitles.label(language = "en", label = "Director's commentary", index = 0),
        )
        // With no language, the label alone still names the track.
        assertEquals("Commentary", Subtitles.label(language = null, label = "Commentary", index = 0))
    }

    @Test fun extractorPlaceholderAndRedundantLabelsAreDropped() {
        assertEquals("German", Subtitles.label(language = "ger", label = "language7", index = 0))
        assertEquals("Track 2", Subtitles.label(language = "und", label = "Language 3", index = 1))
        assertEquals("English", Subtitles.label(language = "eng", label = "English", index = 0))
        assertEquals("English", Subtitles.label(language = "eng", label = "eng", index = 0))
    }

    @Test fun aSidecarsOwnLabelIsNotDoubled() {
        // PlaybackClient labels a sidecar "English (SDH)" and Media3 reports language "en".
        assertEquals("English (SDH)", Subtitles.label(language = "en", label = "English (SDH)", index = 0))
    }

    @Test fun fallsBackToTheLanguageName() {
        assertEquals("English", Subtitles.label(language = "en", label = null, index = 0))
        assertEquals("French", Subtitles.label(language = "fr", label = "  ", index = 1))
    }

    @Test fun fallsBackToAnOrdinalWhenNothingIsKnown() {
        assertEquals("Track 1", Subtitles.label(language = null, label = null, index = 0))
        assertEquals("Track 3", Subtitles.label(language = "und", label = null, index = 2))
    }

    @Test fun languageNameHandlesUnknownAndUnd() {
        assertEquals("English", Subtitles.languageName("en"))
        // Matroska's ISO 639-2 tags, as Media3 reports them, read as names too.
        assertEquals("German", Subtitles.languageName("ger"))
        assertEquals("Czech", Subtitles.languageName("cze"))
        assertEquals("Portuguese (Brazil)", Subtitles.languageName("por-BR"))
        assertNull(Subtitles.languageName("und"))
        assertNull(Subtitles.languageName(null))
        assertNull(Subtitles.languageName(""))
        // A code with no display name comes back as the code itself, not null.
        assertEquals("zz", Subtitles.languageName("zz"))
    }

    @Test fun externalMimeMapsKnownSubtitleExtensions() {
        assertEquals("application/x-subrip", Subtitles.externalMimeType("Show.S04E01.en.srt"))
        assertEquals("text/vtt", Subtitles.externalMimeType("clip.vtt"))
        assertEquals("text/x-ssa", Subtitles.externalMimeType("a.ass"))
        assertEquals("text/x-ssa", Subtitles.externalMimeType("a.ssa"))
        // Unknown/absent/bitmap → null so Media3 sniffs (or the asset's own MIME wins upstream).
        assertNull(Subtitles.externalMimeType("a.sub"))
        assertNull(Subtitles.externalMimeType("movie.mkv"))
        assertNull(Subtitles.externalMimeType(null))
    }

    @Test fun languageTagReadsTheFilenameSuffix() {
        assertEquals("en", Subtitles.languageTag("Show.S04E01.en.srt"))
        assertEquals("en", Subtitles.languageTag("Show.S04E01.eng.srt")) // 3-letter → 2
        assertEquals("en", Subtitles.languageTag("Show.S04E01.en.forced.srt"))
        assertEquals("fr", Subtitles.languageTag("Show.fra.vtt"))
        // No language tag, or a non-language trailing token → null (not a false code).
        assertNull(Subtitles.languageTag("Show.S04E01.srt"))
        assertNull(Subtitles.languageTag(null))
    }

    @Test fun sidecarLabelReadsTheLanguageAndVariantSuffix() {
        assertEquals("English (SDH)", Subtitles.sidecarLabel("Show.S04E01.eng.sdh.srt"))
        assertEquals("English (Forced)", Subtitles.sidecarLabel("Show.S04E01.en.forced.srt"))
        assertEquals("Spanish (Latin American)", Subtitles.sidecarLabel("Show.S04E01.spa.latin-american.srt"))
        assertEquals("Korean", Subtitles.sidecarLabel("Show.S04E01.kor.srt"))
        assertNull(Subtitles.sidecarLabel("Show.S04E01.srt"))
        assertNull(Subtitles.sidecarLabel(null))
    }

    @Test fun audioDetailsNameTheCodecAndLayout() {
        assertEquals("E-AC-3 · 5.1", Subtitles.audioDetails("audio/eac3", 6))
        assertEquals("AAC · Stereo", Subtitles.audioDetails("audio/mp4a-latm", 2))
        assertEquals("Mono", Subtitles.audioDetails("audio/x-unknown", 1))
        assertEquals("TrueHD · 7.1", Subtitles.audioDetails("audio/true-hd", 8))
        assertEquals("DTS · 3 ch", Subtitles.audioDetails("audio/vnd.dts", 3))
        assertNull(Subtitles.audioDetails(null, 0))
    }
}
