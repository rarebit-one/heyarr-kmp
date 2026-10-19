package one.rarebit.heyarr.ui.theme

import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.core.theme.MediaType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A palette derived from any desktop keeps the invariants the hand-tuned Archive palette was
 * built to (MediaThemeTest). Seeds are real Omarchy themes, chosen for their edges:
 * osaka-jade's muted text fails AA on its own ground, catppuccin-latte is light, and the
 * last seed is deliberately unreadable.
 */
class PaletteTest {

    @AfterTest
    fun restore() {
        ActivePalette.current = Palette.Archive
    }

    private fun hex(v: Long) = Color(0xFF000000 or v)

    private val osakaJade = SystemColors(
        dark = true, background = hex(0x111c18), foreground = hex(0xC1C497), accent = hex(0x509475),
        selection = hex(0x32473B), red = hex(0xFF5345), orange = hex(0xa2734b), yellow = hex(0x459451),
        green = hex(0x549e6a), cyan = hex(0x2DD5B7), blue = hex(0x509475), magenta = hex(0xD2689C),
    )
    private val tokyoNight = SystemColors(
        dark = true, background = hex(0x1a1b26), foreground = hex(0xa9b1d6), accent = hex(0x7aa2f7),
        selection = hex(0x292e42), red = hex(0xf7768e), orange = hex(0xeb927b), yellow = hex(0xe0af68),
        green = hex(0x9ece6a), cyan = hex(0x449dab), blue = hex(0x7aa2f7), magenta = hex(0xad8ee6),
    )
    private val nord = SystemColors(
        dark = true,
        background = hex(0x2e3440),
        foreground = hex(0xd8dee9),
        accent = hex(0x81a1c1),
        red = hex(0xbf616a),
        yellow = hex(0xebcb8b),
        green = hex(0xa3be8c),
        blue = hex(0x81a1c1),
    )
    private val catppuccinLatte = SystemColors(
        dark = false, background = hex(0xeff1f5), foreground = hex(0x4c4f69), accent = hex(0x1e66f5),
        red = hex(0xd20f39), orange = hex(0xd84e2b), yellow = hex(0xdf8e1d), green = hex(0x40a02b),
        cyan = hex(0x179299), blue = hex(0x1e66f5), magenta = hex(0xea76cb),
    )

    // A light theme with pale hues (its yellow is 2.3:1 on its paper): they must be deepened.
    private val flexokiLight = SystemColors(
        dark = false, background = hex(0xFFFCF0), foreground = hex(0x100F0F), accent = hex(0x205EA6),
        red = hex(0xD14D41), orange = hex(0xd0772b), yellow = hex(0xD0A215), green = hex(0x879A39),
        cyan = hex(0x3AA99F), blue = hex(0x205EA6), magenta = hex(0xCE5D97),
    )

    /** Ground, text and accent all nearly the same grey: nothing readable as given. */
    private val murky = SystemColors(
        dark = true,
        background = hex(0x303030),
        foreground = hex(0x404040),
        accent = hex(0x353535),
    )

    /** What the XDG portal gives on GNOME or Plasma: a scheme and an accent, nothing else. */
    private val portalAccent = SystemColors(dark = true, accent = hex(0x3584e4))

    private fun assertInvariants(name: String, p: Palette) {
        fun r(a: Color, b: Color) = Contrast.ratio(a, b)
        for (s in listOf(p.bgBase, p.surface1, p.surface2, p.surface3)) {
            assertTrue(r(p.textPrimary, s) >= 4.5, "$name text on $s: ${r(p.textPrimary, s)}")
            assertTrue(r(p.textMuted, s) >= 4.5, "$name muted on $s: ${r(p.textMuted, s)}")
            assertTrue(r(p.danger, s) >= 4.5, "$name danger on $s")
            assertTrue(r(p.success, s) >= 4.5, "$name success on $s")
            assertTrue(r(p.ochre, s) >= 4.5, "$name ochre on $s")
        }
        assertTrue(r(p.bgBase, p.accent) >= 4.5, "$name label on accent")
        assertTrue(r(p.bgBase, p.accentHover) >= 4.5, "$name label on accent hover")
        for (type in MediaType.entries) {
            val a = p.mediaAccent(type)
            assertTrue(r(p.bgBase, a.accent) >= 4.5, "$name ${type.name} CTA start")
            assertTrue(r(p.bgBase, a.gradientEnd) >= 4.5, "$name ${type.name} CTA end")
            assertTrue(r(a.accent, p.bgBase) >= 3.0, "$name ${type.name} mark")
            assertTrue(r(a.hover, p.surface3) >= 3.0, "$name ${type.name} focus ring")
        }
        // The surface ramp is four distinct steps away from the ground: lighter panels on a
        // dark palette, deeper ones on a light palette.
        val ramp = listOf(p.bgBase, p.surface1, p.surface2, p.surface3).map(Contrast::luminance)
        assertEquals(if (p.dark) ramp.sorted() else ramp.sortedDescending(), ramp, "$name surfaces step away")
        // Text sits on the far side of the ground from where the surfaces step.
        assertEquals(p.dark, Contrast.luminance(p.textPrimary) > Contrast.luminance(p.bgBase), "$name text side")
        // Whatever the palette, the player's stage is dark and holds the same invariants.
        if (!p.dark) {
            assertTrue(p.stage.dark, "$name stage is dark")
            assertInvariants("$name stage", p.stage)
        }
        assertEquals(4, ramp.distinct().size, "$name surfaces distinct")
    }

    @Test
    fun archiveKeepsItsOwnInvariants() = assertInvariants("archive", Palette.Archive)

    @Test
    fun everyDerivedPaletteKeepsTheInvariants() {
        for ((name, seed) in listOf(
            "osaka-jade" to osakaJade,
            "tokyo-night" to tokyoNight,
            "nord" to nord,
            "catppuccin-latte" to catppuccinLatte,
            "murky" to murky,
            "portal" to portalAccent,
            "portal light" to portalAccent.copy(dark = false),
            "flexoki-light" to flexokiLight,
        )) {
            assertInvariants(name, Palette.derive(seed))
        }
    }

    @Test
    fun aDarkThemeSuppliesTheGroundAndKeepsItsHues() {
        val p = Palette.derive(tokyoNight)
        assertEquals(tokyoNight.background, p.bgBase)
        assertEquals(tokyoNight.foreground, p.textPrimary)
        // Tokyo Night's blue already clears AA on its ground, so it is used untouched.
        assertEquals(tokyoNight.accent, p.accent)
        assertEquals(tokyoNight.red, p.danger)
        assertEquals(tokyoNight.selection, p.fern)
    }

    @Test
    fun mutedTextThatFailsIsNudgedNotReplaced() {
        // osaka-jade's own `muted` is 2.9:1 on its ground; the derived muted clears AA and is
        // still distinct from (dimmer than) the primary text.
        val p = Palette.derive(osakaJade)
        assertTrue(Contrast.ratio(p.textMuted, p.bgBase) >= 4.5)
        assertTrue(Contrast.luminance(p.textMuted) < Contrast.luminance(p.textPrimary))
    }

    @Test
    fun aLightThemeRebuildsALightGround() {
        val p = Palette.derive(catppuccinLatte)
        assertFalse(p.dark)
        assertEquals(catppuccinLatte.background, p.bgBase)
        assertEquals(catppuccinLatte.foreground, p.textPrimary)
        // Latte's blue sits just under AA (4.4:1) against its own ground as a fill under a
        // light label, so it is deepened a touch — and stays recognisably the same blue.
        assertTrue(Contrast.ratio(p.bgBase, p.accent) >= 4.5)
        assertTrue(Contrast.ratio(p.accent, catppuccinLatte.accent!!) < 1.1)
        // While media plays, the same theme's accents sit on Archive's dark forest.
        assertEquals(Palette.Archive.bgBase, p.stage.bgBase)
        assertNotEquals(Palette.Archive.accent, p.stage.accent)
    }

    @Test
    fun aLightPreferenceWithoutAGroundUsesArchivePaper() {
        // GNOME or Plasma through the portal: "light", an accent, no colours of its own.
        val p = Palette.derive(portalAccent.copy(dark = false))
        assertFalse(p.dark)
        assertEquals(Palette.Archive.readingPaper, p.bgBase)
        assertEquals(Palette.Archive.readingInk, p.textPrimary)
        assertTrue(Contrast.ratio(p.bgBase, p.accent) >= 4.5)
    }

    @Test
    fun aDarkAccentOnlySourceRetintsArchive() {
        val p = Palette.derive(portalAccent)
        assertTrue(p.dark)
        assertEquals(Palette.Archive.bgBase, p.bgBase)
        assertEquals(Palette.Archive.surface3, p.surface3)
        assertEquals(Palette.Archive.textPrimary, p.textPrimary)
        assertNotEquals(Palette.Archive.accent, p.accent)
        assertSame(p, p.stage)
        // The portal names no hues, so the other media types keep their Archive accents.
        assertEquals(Palette.Archive.mediaAccent(MediaType.BOOK), p.mediaAccent(MediaType.BOOK))
    }

    @Test
    fun anEmptySeedIsArchive() {
        assertEquals(Palette.Archive, Palette.derive(SystemColors()))
    }

    @Test
    fun tokensAndMediaThemesFollowTheActivePalette() {
        val p = Palette.derive(tokyoNight)
        val archiveDefault = MediaThemes.default
        ActivePalette.current = p
        assertEquals(p.bgBase, Tokens.bgBase)
        assertEquals(p.accent, Tokens.accent)
        assertEquals(p.accent, MediaThemes.default.accent)
        assertEquals(p.bgBase, MediaThemes.default.onAccent)
        assertEquals(p.mediaAccent(MediaType.MUSIC).accent, MediaThemes.of(MediaType.MUSIC).accent)
        assertEquals(p.slate, MediaThemes.of("rss_feed").accent)
        // The table is built once per palette, not per read.
        assertSame(MediaThemes.all, MediaThemes.all)
        ActivePalette.current = Palette.Archive
        assertEquals(archiveDefault, MediaThemes.default)
    }
}
