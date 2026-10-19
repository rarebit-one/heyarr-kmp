package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.playback.MpvInstallHint
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.XResources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the desktop app fits the Linux it runs on: its scale, and what it asks to be installed. */
class LinuxDesktopTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun scaleFollowsTheSavedValueThenTheEnvironmentThenXftDpi() {
        val plasma150 = { 144f }
        // KDE Plasma sets no GDK_SCALE; its Xft.dpi says 150%.
        assertEquals(1.5f, DesktopConfig().effectiveUiScale(env(), plasma150))
        // GDK_SCALE (Omarchy, most Hyprland setups) wins over Xft.dpi…
        assertEquals(2f, DesktopConfig().effectiveUiScale(env("GDK_SCALE" to "2"), plasma150))
        // …HEYARR_UI_SCALE over both, and a scale picked in Settings over everything.
        assertEquals(
            1.25f,
            DesktopConfig().effectiveUiScale(env("HEYARR_UI_SCALE" to "1.25", "GDK_SCALE" to "2"), plasma150),
        )
        assertEquals(1.75f, DesktopConfig(uiScale = 1.75f).effectiveUiScale(env("GDK_SCALE" to "2"), plasma150))
        // No X server, or Plasma's "scaled by the system" mode (96 dpi): 1x.
        assertEquals(1f, DesktopConfig().effectiveUiScale(env(), { null }))
        assertEquals(1f, DesktopConfig().effectiveUiScale(env(), { 96f }))
        // Nonsense is ignored rather than rendering the app microscopic or enormous.
        assertEquals(1f, DesktopConfig().effectiveUiScale(env(), { 9600f }))
        assertEquals(1f, DesktopConfig().effectiveUiScale(env("GDK_SCALE" to "zero"), { null }))
    }

    @Test
    fun xftDpiIsReadFromXrdbOutput() {
        val plasma = listOf("Xcursor.size:\t24", "Xcursor.theme:\tbreeze_cursors", "Xft.antialias:\t1", "Xft.dpi:\t144")
            .joinToString("\n")
        assertEquals(144f, XResources.parseXftDpi(plasma))
        assertNull(XResources.parseXftDpi("Xcursor.size:\t24\n"))
        assertNull(XResources.parseXftDpi(""))
    }

    @Test
    fun aMissingLibmpvNamesThePackageForThisDistribution() {
        val fedoraKde = listOf("NAME=\"Fedora Linux\"", "VERSION_ID=43", "ID=fedora", "VARIANT_ID=kde")
            .joinToString("\n")
        val ubuntu = "NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\nVERSION_ID=\"24.04\"\n"
        val kubuntuLike = "ID=neon\nID_LIKE=\"ubuntu debian\"\n"
        val tumbleweed = "NAME=\"openSUSE Tumbleweed\"\nID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\"\n"
        val omarchy = "NAME=\"Arch Linux\"\nID=arch\n"
        assertEquals("sudo dnf install mpv-libs", MpvInstallHint.command(fedoraKde))
        assertEquals("sudo apt install libmpv2", MpvInstallHint.command(ubuntu))
        assertEquals("sudo apt install libmpv2", MpvInstallHint.command(kubuntuLike))
        assertEquals("sudo zypper install libmpv2", MpvInstallHint.command(tumbleweed))
        assertEquals("sudo pacman -S mpv", MpvInstallHint.command(omarchy))
        assertEquals("brew install mpv", MpvInstallHint.command(null, mac = true))
        assertNull(MpvInstallHint.command("ID=gentoo\n"))

        val message = MpvInstallHint.message(fedoraKde, mac = false)
        assertTrue(message.startsWith("libmpv is not installed"), message)
        assertTrue(message.endsWith("Install it with: sudo dnf install mpv-libs"), message)
        // An unknown system still gets the plain explanation.
        assertEquals(
            "libmpv is not installed (the mpv library, not only the mpv command)",
            MpvInstallHint.message("ID=gentoo\n", mac = false),
        )
    }
}
