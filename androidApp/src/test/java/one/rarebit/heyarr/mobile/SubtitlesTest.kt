package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure half of subtitle-track selection — the picker label. */
class SubtitlesTest {

    @Test fun prefersAnExplicitLabel() {
        assertEquals(
            "Director's commentary",
            Subtitles.label(language = "en", label = "Director's commentary", index = 0),
        )
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
}
