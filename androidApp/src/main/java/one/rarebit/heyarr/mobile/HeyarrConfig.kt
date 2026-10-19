package one.rarebit.heyarr.mobile

import java.net.URI

/**
 * Where this client points: the base URL of a self-hosted heyarr server that serves
 * the weblogin broker (`/login`), the library/playback APIs (`/api/v1/…`) and the
 * personal-state sync surface. Override per install (homelab vs. LAN vs. Tailscale).
 *
 * Resolution order (see [resolve]): a runtime override the user saved in Settings
 * ([one.rarebit.heyarr.mobile.settings.SettingsStore]) wins; otherwise the build-time
 * default (`BuildConfig.HEYARR_BASE_URL`, gradle property `heyarrBaseUrl`).
 */
data class HeyarrConfig(
    /** e.g. `https://heyarr.example.test:7777` (no trailing slash needed). */
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * The quality profile a Get-once want / Follow is created against, named (not by
     * id) as heyarr's `WantContentRequest` expects ("living-room" is heyarr's fixture
     * default). The Settings screen can override per install.
     */
    val defaultQualityProfile: String = DEFAULT_QUALITY_PROFILE,
    /**
     * The Voidbind pairing relay **base** this device enrols through. voidbind-client's
     * `RelayClient` appends the voidbind-go relay wire itself (`POST {base}/v1/sessions`,
     * `PUT|GET {base}/v1/sessions/{id}/{role}/{type}`), so this is the segment *before*
     * `/v1` — NOT the `/v1` mount. Null means the node's own mount, [DEFAULT_RELAY_PATH]
     * under [baseUrl]: heyarr-core mounts the voidbind-go relay at `/pair/v1/...`
     * (heyarr-core #421, ADR-0066), so the base is `<baseUrl>/pair` and the composed
     * session URL is `<baseUrl>/pair/v1/sessions`. (A base of `…/pair/v1` would double
     * up to `/pair/v1/v1/sessions` → 404.) Note the node's legacy
     * `/pair/sessions/{s}/slots/{slot}` relay speaks heyarr's OLD pairflow, which
     * voidbind-client's `DevicePairing` does not.
     */
    val relayBaseUrl: String? = null,
) {
    /**
     * The relay base actually used: the override, else `<baseUrl>/pair`. `RelayClient`
     * composes `<this>/v1/sessions` on top; see [relaySessionsUrl].
     */
    val effectiveRelayBase: String
        get() = relayBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: (baseUrl.trimEnd('/') + DEFAULT_RELAY_PATH)

    /**
     * The exact URL "Start pairing" POSTs to open a session — what voidbind-client's
     * `RelayClient.createSession(base)` composes from [effectiveRelayBase]. Exposed so
     * the wire path is unit-testable against the node's mount (`/pair/v1/sessions`).
     */
    val relaySessionsUrl: String
        get() = effectiveRelayBase + RELAY_SESSIONS_SUFFIX

    companion object {
        /**
         * Where the node mounts the voidbind-go relay, relative to [baseUrl] — the base
         * *before* the `/v1` the relay wire adds (heyarr-core #421: `/pair/v1/...`).
         */
        const val DEFAULT_RELAY_PATH = "/pair"

        /** What `RelayClient` appends to the base to create a session (voidbind-go relay wire). */
        const val RELAY_SESSIONS_SUFFIX = "/v1/sessions"

        /**
         * The build-time default origin: the `heyarrBaseUrl` Gradle property, or a reserved
         * placeholder in a build made without it (androidApp/build.gradle.kts). The Settings
         * screen can override it at runtime.
         */
        const val DEFAULT_BASE_URL: String = BuildConfig.HEYARR_BASE_URL
        const val DEFAULT_QUALITY_PROFILE = "living-room"

        /**
         * Resolve the effective config from the build default plus optional runtime
         * overrides. A blank/invalid base-URL override falls back to [DEFAULT_BASE_URL];
         * a blank profile override falls back to [DEFAULT_QUALITY_PROFILE]. Pure, so
         * default→override precedence is unit-tested.
         */
        fun resolve(
            baseUrlOverride: String?,
            qualityProfileOverride: String?,
            defaultBaseUrl: String = DEFAULT_BASE_URL,
            defaultQualityProfile: String = DEFAULT_QUALITY_PROFILE,
        ): HeyarrConfig = HeyarrConfig(
            baseUrl = normalizeBaseUrl(baseUrlOverride) ?: normalizeBaseUrl(defaultBaseUrl) ?: defaultBaseUrl,
            defaultQualityProfile = qualityProfileOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaultQualityProfile,
        )

        /**
         * Canonicalise a user-typed base URL: trim whitespace, drop trailing slashes,
         * and require an absolute `http`/`https` URL with a host. Returns null when the
         * input is blank or not usable as an origin (so the caller can fall back or
         * show a validation error).
         */
        fun normalizeBaseUrl(raw: String?): String? {
            val s = raw?.trim()?.trimEnd('/') ?: return null
            if (s.isEmpty()) return null
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrEmpty()) return null
            return s
        }
    }
}
