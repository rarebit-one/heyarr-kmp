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
     * UI scale (1.0 = 96 dpi). Null means "detect": `HEYARR_UI_SCALE`, else `GDK_SCALE`,
     * else 1.0. Explicit because a JVM under XWayland on a HiDPI Wayland compositor
     * (Hyprland, sway) reports 1x and ignores `sun.java2d.uiScale` for Compose.
     */
    val uiScale: Float? = null,
    /**
     * Fetch cover art and synopses from public, keyless sources (TVmaze, Wikipedia,
     * Open Library, iTunes, Cover Art Archive, a feed's own image) when the node holds
     * none. Titles are sent to those services; off means nothing leaves but the node's URL.
     */
    val externalMetadata: Boolean = true,
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
    /** The scale to render at: the saved value, else the environment, else 1x. */
    fun effectiveUiScale(env: (String) -> String? = System::getenv): Float =
        uiScale ?: env("HEYARR_UI_SCALE")?.toFloatOrNull()?.takeIf { it in 0.5f..4f }
            ?: env("GDK_SCALE")?.toFloatOrNull()?.takeIf { it in 0.5f..4f }
            ?: 1f

    companion object {
        const val DEFAULT_BASE_URL = "https://heyarr.br.thesim.family:7777"
        val UI_SCALES = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
    }
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
class FileSettingsStore(
    private val file: File = defaultConfigFile(),
) : SettingsStore {

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
