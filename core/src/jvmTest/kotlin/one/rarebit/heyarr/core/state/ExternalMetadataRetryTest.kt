package one.rarebit.heyarr.core.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.rarebit.heyarr.core.theme.MediaType
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalMetadataRetryTest {
    @Test fun a_failed_lookup_can_retry_and_then_persist_its_cover() = runBlocking {
        val cache = Files.createTempDirectory("metadata-retry").toFile()
        try {
            var calls = 0
            val cover = ExternalMeta(imageUrl = "https://image.example/cover.jpg", source = "provider")
            val key = MetaKey(MediaType.MOVIE, "Example", 2020)
            val metadata = ExternalMetadata(cache, fetch = { error("no fallback expected") }, movieLookup = {
                if (++calls == 1) throw IOException("temporary connection failure")
                cover
            })
            var now = 1_000_000L
            metadata.clock = { now }
            assertNull(metadata.lookup(key))
            assertTrue(cache.listFiles().orEmpty().isEmpty(), "failed requests must not persist a negative match")
            // Inside the back-off a failed key is not asked again (no request storm while down).
            assertNull(metadata.lookup(key))
            assertEquals(1, calls)
            now += ExternalMetadata.FAILURE_RETRY_MS
            assertEquals(cover, metadata.lookup(key))
            assertEquals(2, calls)
            val reopened = ExternalMetadata(cache, fetch = { error("cached cover expected") }, movieLookup = {
                error("cached cover expected")
            })
            assertEquals(cover, reopened.lookup(key))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test fun an_unreachable_episode_list_is_empty_not_a_crash() = runBlocking {
        val cache = Files.createTempDirectory("metadata-episodes").toFile()
        try {
            val metadata = ExternalMetadata(cache, fetch = { throw IOException("provider unreachable") })
            assertEquals(emptyList(), metadata.episodes(42))
            assertTrue(cache.listFiles().orEmpty().isEmpty(), "a failed episode fetch is not cached")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test fun cancellation_is_not_cached_or_swallowed() = runBlocking {
        val cache = Files.createTempDirectory("metadata-cancel").toFile()
        try {
            val metadata = ExternalMetadata(cache, fetch = { null }, movieLookup = {
                throw CancellationException("screen closed")
            })
            assertFailsWith<CancellationException> { metadata.lookup(MetaKey(MediaType.MOVIE, "Example")) }
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally {
            cache.deleteRecursively()
        }
    }
}
