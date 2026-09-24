package one.rarebit.heyarr.desktop

import kotlinx.coroutines.runBlocking
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.state.DesktopExternalMetadata
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class LandscapeMetadataTest {
    @Test fun refreshesLegacyCacheAndPersistsBothArtworkRoles() = runBlocking {
        val dir = Files.createTempDirectory("landscape-metadata").toFile()
        try {
            val oldKey = MessageDigest.getInstance("SHA-256").digest("SERIES|example|2025||".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            dir.resolve("$oldKey.json").writeText("""{"image":"https://art/old","source":"TVmaze","fetched_at":${System.currentTimeMillis()}}""")
            val calls = mutableListOf<String>()
            val metadata = DesktopExternalMetadata.create(cacheDir = dir, fetch = { url ->
                calls += url
                if (url.endsWith("/images")) """[{"type":"background","resolutions":{"original":{"width":1920,"height":1080,"url":"https://art/wide"}}}]"""
                else """{"id":123,"image":{"original":"https://art/poster"},"summary":"Example"}"""
            })
            val key = MetaKey(MediaType.SERIES, "Example", 2025)
            val result = metadata.lookup(key)!!
            assertEquals("https://art/wide", result.landscapeImageUrl)
            assertEquals("https://art/poster", result.imageUrl)
            assertEquals(2, calls.size)
            val cached = DesktopExternalMetadata.create(cacheDir = dir, fetch = { error("Fresh disk cache must avoid network") }).lookup(key)!!
            assertEquals(result, cached)
        } finally {
            dir.deleteRecursively()
        }
    }
}
