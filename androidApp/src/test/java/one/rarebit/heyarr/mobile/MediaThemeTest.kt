package one.rarebit.heyarr.mobile

import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The media → theme table is the brief's, exactly; unknown kinds fall back to slate; text meets AA. */
class MediaThemeTest {

    @Test
    fun accentsAndAspectsMatchTheTable() {
        val expect = mapOf(
            MediaType.MOVIE to Triple(Color(0xFF599C7B), CardAspect.WIDE, "Play"),
            MediaType.SERIES to Triple(Color(0xFF78A58B), CardAspect.WIDE, "Play"),
            MediaType.BOOK to Triple(Color(0xFFC7B66D), CardAspect.POSTER, "Read"),
            MediaType.AUDIOBOOK to Triple(Color(0xFF6FA58C), CardAspect.SQUARE, "Listen"),
            MediaType.PODCAST to Triple(Color(0xFF8DAE8D), CardAspect.SQUARE, "Play episode"),
            MediaType.MUSIC to Triple(Color(0xFF83A876), CardAspect.SQUARE, "Play"),
        )
        for ((type, e) in expect) {
            val t = MediaThemes.of(type)
            assertEquals(type.name, e.first, t.accent)
            assertEquals(type.name, e.second, t.aspect)
            assertEquals(type.name, e.third, t.ctaLabel)
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
            fun ch(v: Float): Double {
                val s = v.toDouble()
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        fun ratio(a: Color, b: Color): Double {
            val l1 = lum(a)
            val l2 = lum(b)
            return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
        }
        assertTrue(ratio(Tokens.textPrimary, Tokens.bgBase) >= 4.5)
        assertTrue(ratio(Tokens.textPrimary, Tokens.surface3) >= 4.5)
        assertTrue(ratio(Tokens.textMuted, Tokens.surface1) >= 4.5)
        // The CTA label against both ends of every pill gradient.
        for (t in MediaThemes.all.values) {
            assertTrue(
                "${t.type.name} start ${ratio(t.onAccent, t.ctaGradientStart)}",
                ratio(t.onAccent, t.ctaGradientStart) >= 4.5,
            )
            assertTrue("${t.type.name} end", ratio(t.onAccent, t.accentGradientEnd) >= 4.5)
        }
        // Raw accents as non-text marks (focus ring, underline, active nav tile) on the base surface: 3:1.
        for (t in MediaThemes.all.values) assertTrue(t.type.name, ratio(t.accent, Tokens.bgBase) >= 3.0)
    }
}
