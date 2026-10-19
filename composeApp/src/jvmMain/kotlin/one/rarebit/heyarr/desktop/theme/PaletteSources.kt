package one.rarebit.heyarr.desktop.theme

import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.ui.theme.Contrast
import one.rarebit.heyarr.ui.theme.SystemColors
import java.nio.file.Files
import java.nio.file.Path

/** What a source found: where the colours came from (shown in Settings) and the colours. */
data class DesktopColors(val source: String, val colors: SystemColors)

/**
 * One place a Linux desktop publishes its colours. Sources only read; deriving heyarr's
 * palette from what they find is `:ui`'s job (`Palette.derive`), so every desktop is held to
 * the same contrast rules.
 */
interface PaletteSource {
    /** The colours now, or null when this source has nothing to say on this machine. */
    fun read(): DesktopColors?

    /** Files whose change (create, rewrite, rename into place) means [read] may answer differently. */
    val watched: List<Path>
}

/** Colour notations the sources use: `#rrggbb`/`#rgb` (Omarchy, pywal) and `r,g,b` (KDE). */
internal object ColorText {
    @Suppress("MagicNumber") // Channel widths and shifts.
    fun hex(text: String?): Color? {
        val h = text?.trim()?.removePrefix("#").orEmpty()
        val rgb = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")

            6 -> h

            8 -> h.substring(2)

            // #aarrggbb, as some generators write; the alpha is not ours to keep
            else -> null
        }
        return rgb?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }

    @Suppress("MagicNumber") // 8-bit channel range.
    fun rgbTriple(text: String?): Color? {
        val t = text?.trim().orEmpty()
        if (t.startsWith("#")) return hex(t)
        val parts = t.split(',').map { it.trim().toIntOrNull() }
        val rgb = parts.take(3).filterNotNull()
            .takeIf { parts.size in 3..4 && it.size == 3 && it.all { v -> v in 0..255 } }
        return rgb?.let { Color(it[0], it[1], it[2]) }
    }

    fun isDark(background: Color, foreground: Color): Boolean =
        Contrast.luminance(background) < Contrast.luminance(foreground)
}

/**
 * Omarchy (Arch + Hyprland). Every Omarchy theme carries a flat `colors.toml` naming the
 * ground, text, accent, selection and the ANSI hues — the file Omarchy itself renders every
 * app's theme from — so this is the richest source there is. `omarchy-theme-set` builds the
 * next theme in `next-theme/`, renames it over `theme/`, then writes `theme.name`; watching
 * the `current/` directory sees all three. Older installs kept the same tree under
 * `~/.config/omarchy/current`.
 */
class OmarchyPalette(private val roots: List<Path>) : PaletteSource {
    override val watched: List<Path> = roots.flatMap { listOf(it.resolve("theme"), it.resolve("theme.name")) }

    override fun read(): DesktopColors? {
        val root = roots.firstOrNull { Files.isRegularFile(it.resolve("theme/colors.toml")) }
        val colors = root?.let { colorsOf(parse(Files.readString(it.resolve("theme/colors.toml")))) }
        val name = root?.let { runCatching { Files.readString(it.resolve("theme.name")).trim() }.getOrNull() }
        return colors?.let { DesktopColors(if (name.isNullOrBlank()) "Omarchy" else "Omarchy · $name", it) }
    }

    companion object {
        private val ENTRY = Regex("""^\s*([A-Za-z_]+)\s*=\s*"([^"]*)"""")

        /** The flat `key = "value"` lines; tables, arrays and comments are not part of the colour file. */
        fun parse(toml: String): Map<String, String> = toml.lineSequence()
            .mapNotNull { ENTRY.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }

        fun colorsOf(v: Map<String, String>): SystemColors? {
            fun c(key: String) = ColorText.hex(v[key])
            val bg = c("background")
            val fg = c("foreground")
            if (bg == null && fg == null && c("accent") == null) return null
            return SystemColors(
                dark = when (v["mode"]) {
                    "dark" -> true
                    "light" -> false
                    else -> if (bg != null && fg != null) ColorText.isDark(bg, fg) else null
                },
                background = bg,
                foreground = fg,
                accent = c("accent"),
                selection = c("selection"),
                red = c("red"),
                orange = c("orange"),
                yellow = c("yellow"),
                green = c("green"),
                cyan = c("cyan"),
                blue = c("blue"),
                magenta = c("magenta"),
            )
        }

        fun forHome(home: Path) = OmarchyPalette(
            listOf(home.resolve(".local/state/omarchy/current"), home.resolve(".config/omarchy/current")),
        )
    }
}

/**
 * KDE Plasma (Fedora KDE, Kubuntu, openSUSE, Arch + Plasma…). Plasma writes the active colour
 * scheme into `kdeglobals` whenever it is applied, as `[Colors:*]` groups of `r,g,b` roles;
 * the accent lives in `[General] AccentColor` when one is set (custom or from the wallpaper),
 * else the selection colour is the accent. The View group is the content area — the ground
 * heyarr's surfaces sit on — so it wins over Window. Plasma rewrites the file by renaming a
 * temp file over it, which a directory watch sees.
 */
class KdePalette(private val kdeglobals: Path) : PaletteSource {
    override val watched: List<Path> = listOf(kdeglobals)

    override fun read(): DesktopColors? {
        val ini = kdeglobals.takeIf(Files::isRegularFile)?.let { parse(Files.readString(it)) }
        val scheme = ini?.get("General")?.get("ColorScheme")
        val source = if (scheme.isNullOrBlank()) "KDE Plasma" else "KDE Plasma · $scheme"
        return ini?.let(::colorsOf)?.let { DesktopColors(source, it) }
    }

    companion object {
        /** Group → key → value. A key's locale or immutability suffix (`Name[en_GB]`, the `$i` marker) is dropped. */
        fun parse(text: String): Map<String, Map<String, String>> {
            val out = LinkedHashMap<String, MutableMap<String, String>>()
            var group: MutableMap<String, String>? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith("#") -> Unit

                    line.startsWith("[") && line.endsWith("]") ->
                        group = out.getOrPut(line.substring(1, line.length - 1)) { LinkedHashMap() }

                    '=' in line -> {
                        val key = line.substringBefore('=').substringBefore('[').trim()
                        group?.putIfAbsent(key, line.substringAfter('=').trim())
                    }
                }
            }
            return out
        }

        fun colorsOf(ini: Map<String, Map<String, String>>): SystemColors? {
            val view = ini["Colors:View"].orEmpty()
            val window = ini["Colors:Window"].orEmpty()
            fun role(key: String) = ColorText.rgbTriple(view[key]) ?: ColorText.rgbTriple(window[key])
            val bg = role("BackgroundNormal")
            val fg = role("ForegroundNormal")
            val accent = ColorText.rgbTriple(ini["General"]?.get("AccentColor"))
                ?: ColorText.rgbTriple(ini["Colors:Selection"]?.get("BackgroundNormal"))
            // A Plasma that has never applied a scheme writes no colour groups; then there is
            // nothing here and the portal (which Plasma also answers) speaks for it.
            if (bg == null && fg == null && accent == null) return null
            return SystemColors(
                dark = if (bg != null && fg != null) ColorText.isDark(bg, fg) else null,
                background = bg,
                foreground = fg,
                accent = accent,
                red = role("ForegroundNegative"),
                yellow = role("ForegroundNeutral"),
                green = role("ForegroundPositive"),
                blue = role("ForegroundLink"),
                magenta = role("ForegroundVisited"),
                cyan = role("ForegroundActive"),
            )
        }
    }
}

/**
 * pywal and wallust, the wallpaper-to-palette tools common on Hyprland, sway and i3 setups on
 * any distro (wallust can write pywal's file too). `colors.json` names a background, a
 * foreground and sixteen terminal colours. The slots are what the generator picked from the
 * wallpaper, not true hues, so the media mapping is approximate by nature; color4 is taken as
 * the accent, the slot most templates treat as primary.
 */
class WalPalette(private val colorsJson: Path) : PaletteSource {
    override val watched: List<Path> = listOf(colorsJson)

    override fun read(): DesktopColors? = colorsJson.takeIf(Files::isRegularFile)
        ?.let { colorsOf(Files.readString(it)) }
        ?.let { DesktopColors("pywal / wallust", it) }

    @Suppress("MagicNumber") // pywal's numbered terminal slots.
    companion object {
        fun colorsOf(json: String): SystemColors? {
            val root = JsonScan.rootObject(json)
            val special = root?.let { JsonScan.objectAt(it, "special") }
            val slots = root?.let { JsonScan.objectAt(it, "colors") }
            fun slot(n: Int) = slots?.let { ColorText.hex(JsonScan.stringField(it, "color$n")) }
            fun special(key: String) = special?.let { ColorText.hex(JsonScan.stringField(it, key)) }
            val bg = special("background") ?: slot(0)
            val fg = special("foreground") ?: slot(7)
            if (bg == null || fg == null) return null
            return SystemColors(
                dark = ColorText.isDark(bg, fg),
                background = bg,
                foreground = fg,
                accent = slot(4),
                red = slot(1),
                green = slot(2),
                yellow = slot(3),
                blue = slot(4),
                magenta = slot(5),
                cyan = slot(6),
            )
        }
    }
}
