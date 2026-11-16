package one.rarebit.heyarr.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.desktop.open.DownloadResult
import one.rarebit.heyarr.desktop.open.JdkBlobDownloader
import one.rarebit.heyarr.desktop.state.ArtworkLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Media reads outside the player (open-externally, artwork) present the session's live
 * [Credential], not the saved bearer string: a desktop enrolled only through Voidbind has no
 * bearer token, and a guest sends no header at all (heyarr-kmp#208).
 */
class MediaCredentialTest {
    /** The Authorization header each request arrived with ("" when it had none). */
    private val seen = LinkedBlockingQueue<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                seen.add(it.requestHeaders.getFirst("Authorization") ?: "")
                val body = "bytes".toByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        start()
    }
    private val origin = "http://127.0.0.1:${server.address.port}"
    private val tmp = Files.createTempDirectory("heyarr-media-cred").toFile()
    private val hash = "blake3:" + "a".repeat(64)

    @AfterTest fun cleanup() {
        server.stop(0)
        tmp.deleteRecursively()
    }

    @Test fun open_externally_downloads_with_the_device_credential() {
        val result = JdkBlobDownloader(tempDir = tmp).download(origin, hash, Credential.Device("cert", "proof"), "epub")

        assertIs<DownloadResult.Downloaded>(result)
        assertEquals("Device cert~proof", seen.poll(5, TimeUnit.SECONDS))
    }

    @Test fun a_guest_download_sends_no_authorization_header() {
        JdkBlobDownloader(tempDir = tmp).download(origin, hash, Credential.Guest, "pdf")

        assertEquals("", seen.poll(5, TimeUnit.SECONDS), "a guest presents nothing, not an empty bearer")
    }

    @Test fun artwork_reads_the_credential_per_request() = runBlocking {
        var proof = "first"
        val art = ArtworkLoader({ origin }, { Credential.Device("cert", proof) }, cacheDir = tmp)

        // The bytes are not an image, so neither load decodes; only the headers matter here.
        assertNull(art.load("/api/v1/blobs/$hash/content"))
        proof = "renewed"
        assertNull(art.load("/api/v1/blobs/blake3:${"b".repeat(64)}/content"))

        assertEquals("Device cert~first", seen.poll(5, TimeUnit.SECONDS))
        assertEquals(
            "Device cert~renewed",
            seen.poll(5, TimeUnit.SECONDS),
            "a fresh proof per fetch, not one frozen at construction",
        )
    }

    @Test fun external_artwork_never_carries_the_credential() = runBlocking {
        // An absolute URL is public cover art: nothing of the session's may travel there.
        val art = ArtworkLoader({ "https://node.example" }, { Credential.Device("cert", "proof") }, cacheDir = tmp)

        art.load("$origin/cover.jpg")

        assertEquals("", seen.poll(5, TimeUnit.SECONDS))
    }
}
