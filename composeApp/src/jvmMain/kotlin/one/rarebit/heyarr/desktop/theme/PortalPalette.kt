package one.rarebit.heyarr.desktop.theme

import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.ui.theme.SystemColors
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The XDG desktop portal's `org.freedesktop.appearance` settings: the one cross-desktop
 * standard for this. GNOME (47+ for the accent), Plasma and COSMIC answer it, and so does
 * Omarchy, whose theme hook sets GNOME's `color-scheme` that the GTK portal reports. It gives
 * only light/dark and an accent, never a ground or text colour, so it re-tints heyarr rather
 * than re-building it; it is the fallback behind the desktop-specific sources.
 *
 * Read through `gdbus` (glib, present wherever a portal is) rather than a JVM D-Bus library:
 * two one-shot calls and one monitor don't justify a new dependency in a desktop app.
 */
class PortalPalette(private val bus: PortalBus = GdbusPortalBus()) : PaletteSource {
    override val watched: List<Path> = emptyList()

    override fun read(): DesktopColors? {
        val scheme = bus.read("color-scheme")?.let(::parseScheme)
        val accent = bus.read("accent-color")?.let(::parseAccent)
        // "No preference" and no accent is the portal saying nothing at all.
        if (accent == null) return null
        return DesktopColors("Desktop accent (XDG portal)", SystemColors(dark = scheme, accent = accent))
    }

    /** Watch for appearance changes, calling [onChange] for each; null when no monitor can start. */
    fun monitor(onChange: () -> Unit): AutoCloseable? = bus.monitor(onChange)

    companion object {
        private const val RGB = 3
        private val UINT = Regex("""uint32 (\d+)""")
        private val TRIPLE = Regex("""\(\s*([-0-9.e]+),\s*([-0-9.e]+),\s*([-0-9.e]+)\s*\)""")

        /** `(<uint32 1>,)` → dark; 2 → light; 0 (no preference) → null. */
        fun parseScheme(reply: String): Boolean? = when (UINT.find(reply)?.groupValues?.get(1)) {
            "1" -> true
            "2" -> false
            else -> null
        }

        /**
         * `(<(0.21, 0.52, 0.89)>,)` → that colour. The spec says an accent outside 0..1 means
         * "unset", and that is how GNOME before 47 and an accent-less Plasma answer.
         */
        fun parseAccent(reply: String): Color? {
            val v = TRIPLE.find(reply)?.groupValues?.drop(1)?.mapNotNull { it.toFloatOrNull() }
            return v?.takeIf { it.size == RGB && it.all { c -> c in 0f..1f } }?.let { Color(it[0], it[1], it[2]) }
        }
    }
}

/** The two portal operations [PortalPalette] needs, as a seam so the parsing is testable without a bus. */
interface PortalBus {
    /** The raw reply for one `org.freedesktop.appearance` key, or null when there is no portal or no such key. */
    fun read(key: String): String?

    /** Start watching for appearance changes; null when no monitor can be started. */
    fun monitor(onChange: () -> Unit): AutoCloseable?
}

class GdbusPortalBus(private val gdbus: String = "gdbus") : PortalBus {
    private val target = listOf(
        "--session",
        "--dest",
        "org.freedesktop.portal.Desktop",
        "--object-path",
        "/org/freedesktop/portal/desktop",
    )

    override fun read(key: String): String? =
        // ReadOne is portal v2 (2023); Read is the older, doubly-wrapped form. The parsers
        // accept either, so fall back rather than branch on the portal's version.
        call("org.freedesktop.portal.Settings.ReadOne", key) ?: call("org.freedesktop.portal.Settings.Read", key)

    private fun call(method: String, key: String): String? = runCatching {
        val args = listOf(gdbus, "call") + target + listOf("--method", method, "org.freedesktop.appearance", key)
        val p = ProcessBuilder(args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!p.waitFor(CALL_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        p.inputStream.bufferedReader().readText().takeIf { p.exitValue() == 0 }
    }.getOrNull()

    override fun monitor(onChange: () -> Unit): AutoCloseable? {
        val p = runCatching {
            ProcessBuilder(listOf(gdbus, "monitor") + target).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return null
        Thread({
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.filter { "SettingChanged" in it && "org.freedesktop.appearance" in it }
                        .forEach { onChange() }
                }
            }
        }, "heyarr-portal-monitor").apply { isDaemon = true }.start()
        return AutoCloseable { p.destroy() }
    }

    private companion object {
        const val CALL_TIMEOUT_S = 2L
    }
}
