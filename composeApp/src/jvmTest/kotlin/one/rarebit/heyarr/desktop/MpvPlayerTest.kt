package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.playback.BlobStream
import one.rarebit.heyarr.desktop.playback.MpvPlayer
import one.rarebit.heyarr.desktop.playback.PlayResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The playback seam, proven WITHOUT launching mpv: the blob-stream URL shape, and the
 * exact argv [MpvPlayer] would spawn (bearer header + URL) captured through an injected
 * fake spawner. Same "pure, no process" stance as heyarr-mobile's PlaybackUrl tests.
 */
class MpvPlayerTest {

    private val HASH = "blake3:" + "0123456789abcdef".repeat(4)
    private val TOKEN = "heyarr_1_supersecret"

    @Test
    fun blobUrlPutsHashInPathVerbatim() {
        assertEquals(
            "https://h.example/api/v1/blobs/$HASH/content",
            BlobStream.contentUrl("https://h.example/", HASH),
        )
    }

    @Test
    fun blobUrlRejectsNonBlobHash() {
        assertFailsWith<IllegalArgumentException> { BlobStream.contentUrl("https://h.example", "not-a-hash") }
        // A percent-encoded colon is the exact shape a live node answers 400 to.
        assertFailsWith<IllegalArgumentException> {
            BlobStream.contentUrl(
                "https://h.example",
                "blake3%3A" + "a".repeat(64),
            )
        }
    }

    @Test
    fun spawnsExactArgvWithAuthHeaderAndUrl() {
        val captured = ArrayList<List<String>>()
        val player = MpvPlayer(
            command = "mpv",
            available = { true },
            spawn = { argv -> captured += argv },
        )

        val result = player.play("https://h.example", HASH, TOKEN)

        assertEquals(PlayResult.Launched, result)
        assertEquals(
            listOf(
                "mpv",
                "--force-window=yes",
                "--http-header-fields=Authorization: Bearer $TOKEN",
                "https://h.example/api/v1/blobs/$HASH/content",
            ),
            captured.single(),
        )
    }

    @Test
    fun mpvNotFoundIsReportedNotSpawned() {
        var spawned = false
        val player = MpvPlayer(available = { false }, spawn = { spawned = true })

        val result = player.play("https://h.example", HASH, TOKEN)

        assertTrue(result is PlayResult.Failed)
        assertTrue(result.message.contains("mpv"))
        assertTrue(!spawned)
    }

    @Test
    fun ioExceptionOnSpawnFailsCleanly() {
        val player = MpvPlayer(available = { true }, spawn = { throw IOException("no such file") })

        val result = player.play("https://h.example", HASH, TOKEN)

        assertTrue(result is PlayResult.Failed)
        // The token must never leak into a user-facing message.
        assertTrue(!result.message.contains(TOKEN))
    }

    @Test
    fun playAllQueuesEveryUrlUnderOneSharedAuthHeader() {
        val hashes = listOf(
            "blake3:" + "a".repeat(64),
            "blake3:" + "b".repeat(64),
            "blake3:" + "c".repeat(64),
        )
        val captured = ArrayList<List<String>>()
        val player = MpvPlayer(command = "mpv", available = { true }, spawn = { captured += it })

        val result = player.playAll("https://h.example", hashes, TOKEN)

        assertEquals(PlayResult.Launched, result)
        val argv = captured.single()
        // One shared header, then the three ordered URLs as trailing positional args.
        assertEquals("mpv", argv[0])
        assertEquals("--force-window=yes", argv[1])
        assertEquals("--http-header-fields=Authorization: Bearer $TOKEN", argv[2])
        assertEquals(hashes.map { "https://h.example/api/v1/blobs/$it/content" }, argv.drop(3))
        assertEquals(1, argv.count { it.startsWith("--http-header-fields=") }) // exactly one auth header
    }

    @Test
    fun playAllEmptyIsFailedNotSpawned() {
        var spawned = false
        val player = MpvPlayer(available = { true }, spawn = { spawned = true })
        val result = player.playAll("https://h.example", emptyList(), TOKEN)
        assertTrue(result is PlayResult.Failed)
        assertTrue(!spawned)
    }

    @Test
    fun badHashFailsBeforeSpawn() {
        var spawned = false
        val player = MpvPlayer(available = { true }, spawn = { spawned = true })

        val result = player.play("https://h.example", "not-a-hash", TOKEN)

        assertTrue(result is PlayResult.Failed)
        assertTrue(!spawned)
    }
}
