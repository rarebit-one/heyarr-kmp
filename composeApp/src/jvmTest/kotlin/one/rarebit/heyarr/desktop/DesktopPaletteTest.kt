package one.rarebit.heyarr.desktop

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.FileSettingsStore
import one.rarebit.heyarr.desktop.theme.DesktopPalette
import one.rarebit.heyarr.desktop.theme.KdePalette
import one.rarebit.heyarr.desktop.theme.OmarchyPalette
import one.rarebit.heyarr.desktop.theme.PortalBus
import one.rarebit.heyarr.desktop.theme.PortalPalette
import one.rarebit.heyarr.desktop.theme.WalPalette
import one.rarebit.heyarr.ui.theme.Palette
import one.rarebit.heyarr.ui.theme.derive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each desktop's colour file reads into the same [one.rarebit.heyarr.ui.theme.SystemColors] shape. */
class DesktopPaletteTest {

    private fun hex(v: Long) = Color(0xFF000000 or v)

    // Omarchy's osaka-jade colors.toml, as shipped (trimmed of the bright_* slots heyarr ignores).
    private val osakaJadeToml = """
        mode = "dark"

        accent = "#509475"
        selection = "#32473B"
        muted = "#53685B"

        background = "#111c18"
        foreground = "#C1C497"

        red = "#FF5345"
        yellow = "#459451"
        orange = "#a2734b"
        green = "#549e6a"
        cyan = "#2DD5B7"
        blue = "#509475"
        magenta = "#D2689C"
    """.trimIndent()

    // The groups Plasma writes into kdeglobals for Breeze Dark, abridged.
    private val breezeDark = """
        [ColorEffects:Disabled]
        Color=56,56,56

        [Colors:View]
        BackgroundNormal=20,22,24
        ForegroundActive=61,174,233
        ForegroundLink=29,153,243
        ForegroundNegative=218,68,83
        ForegroundNeutral=246,116,0
        ForegroundNormal=252,252,252
        ForegroundPositive=39,174,96
        ForegroundVisited=155,89,182

        [Colors:Window]
        BackgroundNormal=32,35,38
        ForegroundNormal=252,252,252

        [Colors:Selection]
        BackgroundNormal=61,174,233

        [General]
        ColorScheme=BreezeDark
        Name[en_GB]=Breeze Dark
    """.trimIndent()

    private val walJson = """
        {
            "wallpaper": "/home/someone/Pictures/wall.jpg",
            "alpha": "100",
            "special": {
                "background": "#0f1419",
                "foreground": "#c3c7ca",
                "cursor": "#c3c7ca"
            },
            "colors": {
                "color0": "#0f1419", "color1": "#d3605a", "color2": "#7ba05b", "color3": "#d9a85f",
                "color4": "#5f8fd9", "color5": "#a76fd0", "color6": "#5fbcd9", "color7": "#c3c7ca"
            }
        }
    """.trimIndent()

    private fun omarchyHome(toml: String = osakaJadeToml, name: String = "osaka-jade"): Path {
        val home = Files.createTempDirectory("heyarr-palette")
        val current = home.resolve(".local/state/omarchy/current")
        current.resolve("theme").createDirectories()
        current.resolve("theme/colors.toml").writeText(toml)
        current.resolve("theme.name").writeText("$name\n")
        return home
    }

    private class FakeBus(val replies: Map<String, String>) : PortalBus {
        override fun read(key: String) = replies[key]
        override fun monitor(onChange: () -> Unit): AutoCloseable? = null
    }

    private val noPortal = FakeBus(emptyMap())

    @Test
    fun omarchyReadsItsColourFileAndThemeName() {
        val found = OmarchyPalette.forHome(omarchyHome()).read()
        assertNotNull(found)
        assertEquals("Omarchy · osaka-jade", found.source)
        val c = found.colors
        assertEquals(true, c.dark)
        assertEquals(hex(0x111c18), c.background)
        assertEquals(hex(0xC1C497), c.foreground)
        assertEquals(hex(0x509475), c.accent)
        assertEquals(hex(0x32473B), c.selection)
        assertEquals(hex(0xa2734b), c.orange)
        assertEquals(hex(0x2DD5B7), c.cyan)
        assertTrue(c.suppliesSurfaces)
    }

    @Test
    fun anOmarchyLightThemeIsALightPalette() {
        val latte = """mode = "light"
            |accent = "#1e66f5"
            |background = "#eff1f5"
            |foreground = "#4c4f69"
        """.trimMargin()
        val c = OmarchyPalette.forHome(omarchyHome(latte, "catppuccin-latte")).read()!!.colors
        assertEquals(false, c.dark)
        val p = Palette.derive(c)
        assertFalse(p.dark)
        assertEquals(hex(0xeff1f5), p.bgBase)
        assertTrue(p.stage.dark)
    }

    @Test
    fun kdeReadsTheViewGroupAndFallsBackToTheSelectionAccent() {
        val found = KdePalette.colorsOf(KdePalette.parse(breezeDark))
        assertNotNull(found)
        assertEquals(Color(20, 22, 24), found.background) // View, not Window
        assertEquals(Color(252, 252, 252), found.foreground)
        assertEquals(Color(61, 174, 233), found.accent)
        assertEquals(Color(218, 68, 83), found.red)
        assertEquals(Color(39, 174, 96), found.green)
        assertEquals(true, found.dark)

        val withAccent = KdePalette.parse("$breezeDark\nAccentColor=233,100,61")
        assertEquals(Color(233, 100, 61), KdePalette.colorsOf(withAccent)?.accent)
    }

    @Test
    fun aKdeglobalsWithoutColourGroupsSaysNothing() {
        assertNull(
            KdePalette.colorsOf(KdePalette.parse("[General]\nColorScheme=BreezeLight\n[KDE]\nSingleClick=false")),
        )
    }

    @Test
    fun walReadsSpecialAndTheAnsiSlots() {
        val c = WalPalette.colorsOf(walJson)
        assertNotNull(c)
        assertEquals(hex(0x0f1419), c.background)
        assertEquals(hex(0xc3c7ca), c.foreground)
        assertEquals(hex(0x5f8fd9), c.accent)
        assertEquals(hex(0xd3605a), c.red)
        assertEquals(hex(0x5fbcd9), c.cyan)
        assertNull(WalPalette.colorsOf("""{"colors": {}}"""))
    }

    @Test
    fun portalRepliesParseInBothShapes() {
        assertEquals(true, PortalPalette.parseScheme("(<uint32 1>,)"))
        assertEquals(false, PortalPalette.parseScheme("(<<uint32 2>>,)")) // the older Read
        assertNull(PortalPalette.parseScheme("(<uint32 0>,)"))
        val accent = PortalPalette.parseAccent("(<(0.20784313976764679, 0.51764708757400513, 0.89411765336990356)>,)")
        assertEquals(Color(0.20784314f, 0.5176471f, 0.89411765f), accent)
        // Out of range is the spec's "unset".
        assertNull(PortalPalette.parseAccent("(<(-1.0, -1.0, -1.0)>,)"))
    }

    @Test
    fun portalWithoutAnAccentSaysNothingUnlessLight() {
        // What Omarchy's portal answers today: dark, and the accent key is not found.
        assertNull(PortalPalette(FakeBus(mapOf("color-scheme" to "(<uint32 1>,)"))).read())
        val gnome = PortalPalette(
            FakeBus(mapOf("color-scheme" to "(<uint32 1>,)", "accent-color" to "(<(0.2, 0.5, 0.9)>,)")),
        ).read()
        assertNotNull(gnome)
        assertEquals("Desktop accent (XDG portal)", gnome.source)
        assertEquals(true, gnome.colors.dark)
        assertFalse(gnome.colors.suppliesSurfaces)

        // A light desktop (GNOME's default) turns heyarr light even without an accent: Archive's
        // light twin, on its own reading paper.
        val light = PortalPalette(FakeBus(mapOf("color-scheme" to "(<uint32 2>,)"))).read()
        assertNotNull(light)
        assertEquals("Desktop light (XDG portal)", light.source)
        val p = Palette.derive(light.colors)
        assertFalse(p.dark)
        assertEquals(Palette.Archive.readingPaper, p.bgBase)
    }

    @Test
    fun breezeLightIsALightPaletteInBreezeBlue() {
        // The roles KDE's own BreezeLight.colors scheme writes into kdeglobals (abridged).
        val breezeLight = """
            [Colors:View]
            BackgroundNormal=255,255,255
            ForegroundActive=61,174,233
            ForegroundLink=41,128,185
            ForegroundNegative=218,68,83
            ForegroundNeutral=246,116,0
            ForegroundNormal=35,38,41
            ForegroundPositive=39,174,96
            ForegroundVisited=155,89,182

            [Colors:Window]
            BackgroundNormal=239,240,241
            ForegroundNormal=35,38,41

            [Colors:Selection]
            BackgroundNormal=61,174,233

            [General]
            ColorScheme=BreezeLight
            Name=Breeze Light
            Name[de]=Breeze Hell
        """.trimIndent()
        val c = KdePalette.colorsOf(KdePalette.parse(breezeLight))
        assertNotNull(c)
        assertEquals(false, c.dark)
        val p = Palette.derive(c)
        assertFalse(p.dark)
        assertEquals(Color.White, p.bgBase)
        // Breeze blue is 2.3:1 on white: deepened until a white label reads on it.
        assertTrue(one.rarebit.heyarr.ui.theme.Contrast.ratio(p.bgBase, p.accent) >= 4.5)
    }

    @Test
    fun theChainPrefersTheMostSpecificSource() {
        val home = omarchyHome()
        home.resolve(".config").createDirectories()
        home.resolve(".config/kdeglobals").writeText(breezeDark)
        val gnomeBus = FakeBus(mapOf("accent-color" to "(<(0.2, 0.5, 0.9)>,)"))
        val env = mapOf("XDG_CURRENT_DESKTOP" to "KDE")

        // Omarchy wins over everything that also answers.
        assertEquals("Omarchy · osaka-jade", DesktopPalette.forThisDesktop(env::get, home, gnomeBus).resolve()?.source)

        // Without Omarchy, a Plasma session reads kdeglobals…
        Files.delete(home.resolve(".local/state/omarchy/current/theme/colors.toml"))
        assertEquals(
            "KDE Plasma · BreezeDark",
            DesktopPalette.forThisDesktop(env::get, home, gnomeBus).resolve()?.source,
        )

        // …but a kdeglobals left behind under another desktop is not that session's palette.
        val hyprland = mapOf("XDG_CURRENT_DESKTOP" to "Hyprland")
        assertEquals(
            "Desktop accent (XDG portal)",
            DesktopPalette.forThisDesktop(hyprland::get, home, gnomeBus).resolve()?.source,
        )
        assertNull(DesktopPalette.forThisDesktop(hyprland::get, home, noPortal).resolve())
    }

    @Test
    fun heyarrPaletteEnvPinsOrRefusesASource() {
        val home = omarchyHome()
        val gnomeBus = FakeBus(mapOf("accent-color" to "(<(0.2, 0.5, 0.9)>,)"))
        fun resolve(v: String) =
            DesktopPalette.forThisDesktop(mapOf("HEYARR_PALETTE" to v)::get, home, gnomeBus).resolve()
        assertNull(resolve("archive"))
        assertEquals("Desktop accent (XDG portal)", resolve("portal")?.source)
        assertEquals("Omarchy · osaka-jade", resolve("auto")?.source)
    }

    @Test
    fun anOmarchyThemeSwitchIsSeenLive() = runBlocking {
        val home = omarchyHome()
        val current = home.resolve(".local/state/omarchy/current")
        val palette = DesktopPalette(listOf(OmarchyPalette.forHome(home)))
        withTimeout(10_000) {
            val collected = async { palette.changes().take(2).toList() }
            // Let the watch register before switching, as omarchy-theme-set does: build
            // next-theme, rename it over theme, then write theme.name.
            delay(500)
            val next = current.resolve("next-theme").createDirectories()
            next.resolve("colors.toml").writeText(
                """mode = "dark"
                  |accent = "#7aa2f7"
                  |background = "#1a1b26"
                  |foreground = "#a9b1d6"
                """.trimMargin(),
            )
            Files.walk(current.resolve("theme")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            Files.move(next, current.resolve("theme"), StandardCopyOption.ATOMIC_MOVE)
            current.resolve("theme.name").writeText("tokyo-night\n")
            val both = collected.await()
            assertEquals(listOf("Omarchy · osaka-jade", "Omarchy · tokyo-night"), both.map { it?.source })
            assertEquals(hex(0x7aa2f7), both[1]?.colors?.accent)
        }
    }

    @Test
    fun theSettingPersistsAndDefaultsOn() {
        val file = Files.createTempDirectory("heyarr-config").resolve("config.json").toFile()
        val store = FileSettingsStore(file)
        assertTrue(store.load().systemColors)
        store.save(DesktopConfig(systemColors = false))
        assertFalse(store.load().systemColors)
    }
}
