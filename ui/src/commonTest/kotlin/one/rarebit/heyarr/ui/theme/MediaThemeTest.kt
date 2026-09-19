package one.rarebit.heyarr.ui.theme

import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The media → theme table is the brief's, exactly; unknown kinds fall back to slate. */
class MediaThemeTest {

    @Test
    fun accentsAndAspectsMatchTheTable() {
        val expect = mapOf(
            MediaType.MOVIE to Triple(Color(0xFF00935E), CardAspect.WIDE, "Play"),
            MediaType.SERIES to Triple(Color(0xFF7C5CFF), CardAspect.WIDE, "Play"),
            MediaType.BOOK to Triple(Color(0xFFE0A458), CardAspect.POSTER, "Read"),
            MediaType.AUDIOBOOK to Triple(Color(0xFF2DB3A6), CardAspect.SQUARE, "Listen"),
            MediaType.PODCAST to Triple(Color(0xFFC13BAD), CardAspect.SQUARE, "Play episode"),
            MediaType.MUSIC to Triple(Color(0xFFFF4D6D), CardAspect.SQUARE, "Play"),
        )
        for ((type, e) in expect) {
            val t = MediaThemes.of(type)
            assertEquals(e.first, t.accent, type.name)
            assertEquals(e.second, t.aspect, type.name)
            assertEquals(e.third, t.ctaLabel, type.name)
        }
        assertTrue(MediaThemes.of(MediaType.BOOK).spineShadow)
        assertEquals(Tokens.accent, MediaThemes.default.accent)
    }

    @Test
    fun unknownAndNonMediaKindsFallBackToSlate() {
        assertEquals(Tokens.slate, MediaThemes.of("document").accent)
        assertEquals(Tokens.slate, MediaThemes.of("rss_feed").accent)
        assertEquals(Tokens.slate, MediaThemes.of(null).accent)
        assertEquals(Tokens.slate, MediaThemes.of("whatever").accent)
    }

    @Test
    fun heyarrTypeWordsMapOntoKinds() {
        assertEquals(MediaType.SERIES, MediaType.from("tv_series"))
        assertEquals(MediaType.SERIES, MediaType.from("series"))
        assertEquals(MediaType.MOVIE, MediaType.from("Movie"))
        assertEquals(MediaType.PODCAST, MediaType.from("podcast"))
        assertEquals(MediaType.FEED, MediaType.from("rss_feed"))
        assertEquals(MediaType.FEED, MediaType.from("document"))
        assertEquals(MediaType.UNKNOWN, MediaType.from("unknown"))
        assertEquals(listOf("movie", "series", "music", "book"), MediaType.SEARCHABLE.map { it.apiName })
    }

    @Test
    fun textOnSurfacesMeetsAaContrast() {
        fun lum(c: Color): Double {
            fun ch(v: Float): Double { val s = v.toDouble(); return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        fun ratio(a: Color, b: Color): Double { val l1 = lum(a); val l2 = lum(b); return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05) }
        assertTrue(ratio(Tokens.textPrimary, Tokens.bgBase) >= 4.5)
        assertTrue(ratio(Tokens.textPrimary, Tokens.surface3) >= 4.5)
        assertTrue(ratio(Tokens.textMuted, Tokens.surface1) >= 4.5)
        // The CTA label against both ends of every pill gradient.
        for (t in MediaThemes.all.values) {
            assertTrue(ratio(t.onAccent, t.ctaGradientStart) >= 4.5, "${t.type.name} start ${ratio(t.onAccent, t.ctaGradientStart)}")
            assertTrue(ratio(t.onAccent, t.accentGradientEnd) >= 4.5, "${t.type.name} end")
        }
        // Raw accents as non-text marks (focus ring, underline) on the base surface: 3:1.
        for (t in MediaThemes.all.values) assertTrue(ratio(t.accent, Tokens.bgBase) >= 3.0, t.type.name)
    }
}
