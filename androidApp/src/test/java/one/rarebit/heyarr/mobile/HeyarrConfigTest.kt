package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.settings.InMemorySettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Config resolution: build default → runtime override, with URL normalisation. */
class HeyarrConfigTest {

    @Test fun buildDefaultIsTheLiveNodeOverInternalTls() {
        // The gradle default (`heyarrBaseUrl` unset) is the Bartley Ridge node, now
        // served over internal TLS (valid Let's Encrypt cert on the .br.thesim.family name).
        assertEquals("https://heyarr.br.thesim.family:7777", HeyarrConfig.DEFAULT_BASE_URL)
        assertEquals(HeyarrConfig.DEFAULT_BASE_URL, HeyarrConfig().baseUrl)
        assertEquals("living-room", HeyarrConfig().defaultQualityProfile)
    }

    @Test fun noOverridesResolveToDefaults() {
        val c = HeyarrConfig.resolve(baseUrlOverride = null, qualityProfileOverride = null)
        assertEquals(HeyarrConfig.DEFAULT_BASE_URL, c.baseUrl)
        assertEquals(HeyarrConfig.DEFAULT_QUALITY_PROFILE, c.defaultQualityProfile)
    }

    @Test fun overridesWinOverDefaults() {
        val c = HeyarrConfig.resolve(
            baseUrlOverride = "https://heyarr.example.thesim.family",
            qualityProfileOverride = "bedroom",
            defaultBaseUrl = "http://192.168.16.224:7777",
            defaultQualityProfile = "living-room",
        )
        assertEquals("https://heyarr.example.thesim.family", c.baseUrl)
        assertEquals("bedroom", c.defaultQualityProfile)
    }

    @Test fun blankOrInvalidOverridesFallBackToDefaults() {
        assertEquals(HeyarrConfig.DEFAULT_BASE_URL, HeyarrConfig.resolve("   ", "  ").baseUrl)
        assertEquals(HeyarrConfig.DEFAULT_QUALITY_PROFILE, HeyarrConfig.resolve("   ", "  ").defaultQualityProfile)
        assertEquals(HeyarrConfig.DEFAULT_BASE_URL, HeyarrConfig.resolve("not a url", null).baseUrl)
        assertEquals(HeyarrConfig.DEFAULT_BASE_URL, HeyarrConfig.resolve("ftp://x.y", null).baseUrl)
    }

    @Test fun normalisesTrailingSlashAndWhitespace() {
        assertEquals("http://10.0.0.5:7777", HeyarrConfig.normalizeBaseUrl("  http://10.0.0.5:7777/  "))
        assertEquals("https://a.b", HeyarrConfig.normalizeBaseUrl("https://a.b///"))
        assertEquals("http://100.64.0.9:7777", HeyarrConfig.resolve("http://100.64.0.9:7777/", null).baseUrl)
    }

    @Test fun rejectsNonOriginInput() {
        assertNull(HeyarrConfig.normalizeBaseUrl(null))
        assertNull(HeyarrConfig.normalizeBaseUrl(""))
        assertNull(HeyarrConfig.normalizeBaseUrl("192.168.16.224:7777")) // no scheme
        assertNull(HeyarrConfig.normalizeBaseUrl("http://")) // no host
        assertNull(HeyarrConfig.normalizeBaseUrl("http://bad host/")) // unparsable
    }

    @Test fun resolvesFromASettingsStoreDefaultThenOverride() {
        val store = InMemorySettingsStore()
        fun resolve() = HeyarrConfig.resolve(store.baseUrlOverride, store.qualityProfileOverride)

        assertEquals(HeyarrConfig.DEFAULT_BASE_URL, resolve().baseUrl)

        store.baseUrlOverride = "http://hyperion-1.tail.ts.net:7777/"
        store.qualityProfileOverride = "4k"
        assertEquals("http://hyperion-1.tail.ts.net:7777", resolve().baseUrl)
        assertEquals("4k", resolve().defaultQualityProfile)

        store.baseUrlOverride = null
        store.qualityProfileOverride = null
        assertEquals(HeyarrConfig.DEFAULT_BASE_URL, resolve().baseUrl)
        assertEquals(HeyarrConfig.DEFAULT_QUALITY_PROFILE, resolve().defaultQualityProfile)
    }

    @Test fun sessionSubtitleReportsIdentityAndScope() {
        val ro = one.rarebit.heyarr.mobile.search.SessionAuthority(
            kind = "session",
            principalId = "ed25519:0123456789abcdef",
            deviceKey = "",
            scopes = listOf("read"),
            canWrite = false,
            managementAuthorized = false,
        )
        assertEquals(
            "Signed in as ed25519:01234567… · read-only · http://n:1",
            sessionSubtitle(user = null, authority = ro, baseUrl = "http://n:1"),
        )
        assertEquals(
            "Signed in as jaryl · can write · http://n:1",
            sessionSubtitle(user = "jaryl", authority = ro.copy(canWrite = true), baseUrl = "http://n:1"),
        )
        assertEquals(
            "Signed in · read-only (session unverified) · http://n:1",
            sessionSubtitle(user = null, authority = null, baseUrl = "http://n:1"),
        )
    }
}
