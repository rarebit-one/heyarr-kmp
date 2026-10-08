package one.rarebit.heyarr.vault

import com.sun.net.httpserver.HttpServer
import one.rarebit.heyarr.core.auth.Credential
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class UrlConnectionVaultBlobStoreTest {
    private val hash = "blake3:" + "a".repeat(64)
    private var body = ByteArray(0)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            ex.requestBody.use { it.readBytes() }
            // Chunked (length 0): the client cannot know the size up front, as with a hostile node.
            ex.sendResponseHeaders(if (ex.requestMethod == "PUT") 201 else 200, 0)
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun aWholeBlobWithinTheLimitIsReturned() {
        body = ByteArray(VaultBlobStore.MAX_WHOLE_BLOB_BYTES) { it.toByte() }
        assertContentEquals(body, UrlConnectionVaultBlobStore().fetchAll(baseUrl, hash, Credential.Guest))
    }

    @Test
    fun aWholeBlobPastTheLimitIsRefusedNotBuffered() {
        body = ByteArray(VaultBlobStore.MAX_WHOLE_BLOB_BYTES + 1)
        assertFailsWith<VaultFrame.IntegrityException> {
            UrlConnectionVaultBlobStore().fetchAll(baseUrl, hash, Credential.Guest)
        }
    }

    @Test
    fun anEndlessUploadAcknowledgementIsAFailureNotBuffered() {
        body = ByteArray(VaultBlobStore.MAX_WHOLE_BLOB_BYTES + 1)
        val r = UrlConnectionVaultBlobStore().putBlob(baseUrl, hash, ByteArray(4), Credential.Guest)
        assertIs<PutResult.Failed>(r)
    }
}
