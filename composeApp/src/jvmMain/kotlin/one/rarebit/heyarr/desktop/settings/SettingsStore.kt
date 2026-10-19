package one.rarebit.heyarr.desktop.settings

import one.rarebit.heyarr.core.net.JsonScan
import java.io.File

/**
 * The persisted desktop configuration — the desktop analog of heyarr-mobile's
 * `settings.SettingsStore` (which is SharedPreferences-backed on the phone). Here it is
 * a single JSON file at `~/.config/heyarr-desktop/config.json` (XDG-ish; honours
 * `$XDG_CONFIG_HOME`).
 *
 * NOTE on the token: the bearer token IS a secret. This v1 slice persists it in
 * plaintext in the config file (`chmod 600`), which matches the "paste a token" flow
 * and is enough to prove the path. A later revision moves it to the OS secret store
 * (libsecret / GNOME Keyring via `secret-tool`, or KWallet) — see the desktop TODO.
 */
data class DesktopConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val bearerToken: String = "",
    /**
     * UI scale (1.0 = 96 dpi). Null means "detect": `HEYARR_UI_SCALE`, else `GDK_SCALE`, else
     * the X resource `Xft.dpi` / 96, else 1.0. Explicit because a JVM under XWayland on a HiDPI
     * Wayland compositor (Hyprland, sway, KDE Plasma) reports 1x and ignores
     * `sun.java2d.uiScale` for Compose.
     */
    val uiScale: Float? = null,
    /**
     * Fetch cover art and synopses from public, keyless sources (TVmaze, Wikipedia,
     * Open Library, iTunes, Cover Art Archive, a feed's own image) when the node holds
     * none. Titles are sent to those services; off means nothing leaves but the node's URL.
     */
    val externalMetadata: Boolean = true,
    /**
     * Take the app's colours from the desktop (Omarchy, KDE Plasma, pywal/wallust, or the XDG
     * portal's accent) instead of heyarr's own Archive palette. `HEYARR_PALETTE` can pin or
     * refuse a source for one launch; see `DesktopPalette.forThisDesktop`.
     */
    val systemColors: Boolean = true,
    /**
     * The designated vault folder this machine keeps in sync (W4), or null when vault sync is
     * off. One local folder ⇄ one heyarr vault space.
     */
    val vaultFolder: String? = null,
    /**
     * The vault space id this folder is bound to. Null on first run before the custody bootstrap
     * mints one; the app records the minted id here so later launches OPEN it rather than mint again.
     */
    val vaultSpaceId: String? = null,
) {
    /**
     * The scale to render at: the saved value, else the environment, else what the desktop
     * tells X clients to scale by, else 1x.
     *
     * `GDK_SCALE` is how Omarchy and most Hyprland/sway setups say it. KDE Plasma doesn't set
     * it: under its default "apply scaling themselves" mode for X11 apps, Plasma publishes the
     * factor as the `Xft.dpi` X resource instead (144 at 150%), the same knob GTK and Qt read on
     * X11. In Plasma's other mode, "scaled by the system", it publishes 96 and upscales the
     * window itself, so 1x is right there too.
     */
    fun effectiveUiScale(env: (String) -> String? = System::getenv, xftDpi: () -> Float? = XResources::xftDpi): Float =
        uiScale ?: env("HEYARR_UI_SCALE")?.toFloatOrNull()?.takeIf { it in SCALE_RANGE }
            ?: env("GDK_SCALE")?.toFloatOrNull()?.takeIf { it in SCALE_RANGE }
            ?: xftDpi()?.let { it / BASE_DPI }?.takeIf { it in SCALE_RANGE }
            ?: 1f

    companion object {
        /** The build's configured node ([SiteDefaults]); a reserved placeholder in an unconfigured build. */
        val DEFAULT_BASE_URL: String get() = SiteDefaults.baseUrl
        val UI_SCALES = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
        private val SCALE_RANGE = 0.5f..4f
        private const val BASE_DPI = 96f
    }
}

/**
 * The X resources database (`xrdb -query`), read once per process: the answer only changes when
 * the desktop's scale does, and a scale change means a restart for every XWayland app anyway.
 * Null where there is no X server or no `xrdb` (a headless test, a bare Wayland session).
 */
object XResources {
    private const val QUERY_TIMEOUT_S = 2L

    private val cached: Float? by lazy { query()?.let(::parseXftDpi) }

    fun xftDpi(): Float? = cached

    /** `Xft.dpi:\t144` → 144. */
    fun parseXftDpi(resources: String): Float? = resources.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("Xft.dpi:") }
        ?.substringAfter(':')?.trim()?.toFloatOrNull()
        ?.takeIf { it > 0f }

    private fun query(): String? = runCatching {
        val p = ProcessBuilder("xrdb", "-query").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(QUERY_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else {
            p.inputStream.bufferedReader().readText().takeIf { p.exitValue() == 0 }
        }
    }.getOrNull()
}

/** Read/write seam, so the resolution logic is JVM-testable with an in-memory fake. */
interface SettingsStore {
    fun load(): DesktopConfig
    fun save(config: DesktopConfig)
}

/** Non-persistent store for tests and previews. */
class InMemorySettingsStore(private var config: DesktopConfig = DesktopConfig()) : SettingsStore {
    override fun load(): DesktopConfig = config
    override fun save(config: DesktopConfig) {
        this.config = config
    }
}

/**
 * The file-backed store. Reads tolerantly with [JsonScan] (same dependency-free stance
 * as the network readers); writes a small, hand-escaped JSON object. Missing/corrupt
 * file → defaults, never a crash.
 */
class FileSettingsStore(private val file: File = defaultConfigFile()) : SettingsStore {

    override fun load(): DesktopConfig {
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
            ?: return DesktopConfig()
        val obj = JsonScan.rootObject(text) ?: return DesktopConfig()
        return DesktopConfig(
            baseUrl = JsonScan.stringField(obj, "base_url")?.takeIf { it.isNotBlank() }
                ?: DesktopConfig.DEFAULT_BASE_URL,
            bearerToken = JsonScan.stringField(obj, "bearer_token").orEmpty(),
            uiScale = JsonScan.stringField(obj, "ui_scale")?.toFloatOrNull()?.takeIf { it in 0.5f..4f },
            externalMetadata = JsonScan.boolField(obj, "external_metadata") ?: true,
            systemColors = JsonScan.boolField(obj, "system_colors") ?: true,
            vaultFolder = JsonScan.stringField(obj, "vault_folder")?.takeIf { it.isNotBlank() },
            vaultSpaceId = JsonScan.stringField(obj, "vault_space_id")?.takeIf { it.isNotBlank() },
        )
    }

    override fun save(config: DesktopConfig) {
        file.parentFile?.mkdirs()
        val json = buildString {
            append("{\n")
            append("  \"base_url\": \"").append(escape(config.baseUrl)).append("\",\n")
            append("  \"bearer_token\": \"").append(escape(config.bearerToken)).append("\"")
            config.uiScale?.let { append(",\n  \"ui_scale\": \"").append(it.toString()).append("\"") }
            append(",\n  \"external_metadata\": ").append(if (config.externalMetadata) "true" else "false")
            append(",\n  \"system_colors\": ").append(if (config.systemColors) "true" else "false")
            config.vaultFolder?.let { append(",\n  \"vault_folder\": \"").append(escape(it)).append("\"") }
            config.vaultSpaceId?.let { append(",\n  \"vault_space_id\": \"").append(escape(it)).append("\"") }
            append("\n}\n")
        }
        file.writeText(json)
        // Best-effort tighten perms — the token is a secret. POSIX-only; ignored elsewhere.
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    companion object {
        /** `$XDG_CONFIG_HOME/heyarr-desktop/config.json`, else `~/.config/heyarr-desktop/config.json`. */
        fun defaultConfigFile(): File {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".config")
            return File(File(base, "heyarr-desktop"), "config.json")
        }
    }
}
