package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.desktop.cast.CastConnection
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.cast.CastFrame
import one.rarebit.heyarr.desktop.cast.CastWire
import java.io.DataOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A real TLS socket and Cast framing, confined to localhost and an ephemeral test certificate. */
class CastConnectionIntegrationTest {
    private class Receiver(private val initialStatus: String = "ready") : AutoCloseable {
        private val address = InetAddress.getByName("127.0.0.1") as java.net.Inet4Address
        private val server = context.serverSocketFactory.createServerSocket(0, 1, address)
            as SSLServerSocket
        private val incoming = LinkedBlockingQueue<CastFrame>()
        private val accepted = CompletableFuture<SSLSocket>()
        private val sender = CompletableFuture<String>()
        private val reader = Thread({
            try {
                val socket = server.accept() as SSLSocket
                socket.soTimeout = 8_000
                accepted.complete(socket)
                while (!socket.isClosed) {
                    val frame = CastWire.read(socket.inputStream)
                    sender.complete(frame.source)
                    val type = JsonScan.stringField(frame.payload, "type")
                    if (frame.namespace == RECEIVER_NS && type == "GET_STATUS") {
                        when (initialStatus) {
                            "reject" -> reply(frame, """{"type":"INVALID_REQUEST"}""")
                            "idle" -> reply(frame, """{"type":"RECEIVER_STATUS","status":{"applications":[]}}""")
                            else -> reply(frame, RECEIVER_STATUS)
                        }
                    } else if (frame.namespace == MEDIA && type == "GET_STATUS") {
                        reply(frame, mediaStatus(7))
                    } else if (type != "CONNECT") {
                        incoming.put(frame)
                    }
                }
            } catch (_: Exception) {
                // Test shutdown and malformed-frame tests close the socket here.
            }
        }, "fake-cast-receiver").apply { isDaemon = true }

        val device: CastDevice = CastDevice("test", "Test", address, server.localPort)

        init {
            server.soTimeout = 8_000
            reader.start()
        }

        fun take(type: String): CastFrame {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline) {
                val frame = incoming.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (JsonScan.stringField(frame.payload, "type") == type) return frame
            }
            error("Timed out waiting for $type")
        }

        fun poll(): CastFrame? = incoming.poll(100, TimeUnit.MILLISECONDS)

        fun send(source: String, destination: String, namespace: String, payload: String) {
            val socket = accepted.get(4, TimeUnit.SECONDS)
            synchronized(socket.outputStream) {
                CastWire.write(socket.outputStream, CastFrame(source, destination, namespace, payload))
            }
        }

        fun sender(): String = sender.get(4, TimeUnit.SECONDS)

        fun disconnect() {
            accepted.get(4, TimeUnit.SECONDS).close()
        }

        fun awaitEof() {
            reader.join(4_000)
            assertFalse(reader.isAlive, "Constructor failure left the TLS socket open")
        }

        fun reply(request: CastFrame, body: String) {
            val id = JsonScan.longField(request.payload, "requestId")
            val payload = body.dropLast(1) + ",\"requestId\":$id}"
            send(request.destination, request.source, request.namespace, payload)
        }

        fun malformedFrame() {
            val socket = accepted.get(4, TimeUnit.SECONDS)
            synchronized(socket.outputStream) {
                DataOutputStream(socket.outputStream).apply {
                    writeInt(11)
                    write(byteArrayOf(0x3a, 0xf5.toByte()) + ByteArray(8) { 0xff.toByte() } + byteArrayOf(0x01))
                    flush()
                }
            }
        }

        override fun close() {
            accepted.getNow(null)?.close()
            server.close()
            reader.join(4_000)
            assertFalse(reader.isAlive, "Fake receiver reader did not stop")
        }
    }

    @Test
    fun acknowledgementsErrorsAndUnrelatedReplies() {
        Receiver().use { receiver ->
            CastConnection(receiver.device).use { cast ->
                assertEquals(7, cast.status?.mediaSessionId)
                val load = CompletableFuture.runAsync { cast.load("http://127.0.0.1/example", "Example") }
                val request = receiver.take("LOAD")
                val id = JsonScan.longField(request.payload, "requestId")
                receiver.send(
                    "foreign",
                    request.source,
                    MEDIA,
                    """{"type":"MEDIA_STATUS","requestId":$id,"status":[]}""",
                )
                receiver.send("web-4", "wrong-sender", MEDIA, """{"type":"MEDIA_STATUS","requestId":$id,"status":[]}""")
                receiver.send("receiver-0", request.source, HEARTBEAT, """{"type":"PING"}""")
                receiver.take("PONG") // Read-loop barrier after both unrelated replies.
                assertFalse(load.isDone)
                receiver.reply(request, mediaStatus(8))
                load.get(4, TimeUnit.SECONDS)
                assertEquals(8, cast.status?.mediaSessionId)

                val pause = CompletableFuture.runAsync { cast.control("pause") }
                val control = receiver.take("PAUSE")
                assertEquals(8, JsonScan.longField(control.payload, "mediaSessionId"))
                receiver.reply(control, """{"type":"INVALID_PLAYER_STATE"}""")
                assertFailsWith<ExecutionException> { pause.get(4, TimeUnit.SECONDS) }
            }
        }
    }

    @Test
    fun foreignStatusAndCloseCannotChangeSessionOrKillConnection() {
        Receiver().use { receiver ->
            CastConnection(receiver.device).use { cast ->
                val sender = receiver.sender()
                receiver.send("foreign", sender, MEDIA, mediaStatus(99))
                receiver.send("web-4", "other-sender", MEDIA, mediaStatus(99))
                receiver.send("foreign", sender, CONNECTION, """{"type":"CLOSE"}""")
                receiver.send("receiver-0", "other-sender", CONNECTION, """{"type":"CLOSE"}""")
                receiver.send("receiver-0", sender, HEARTBEAT, """{"type":"PING"}""")
                receiver.take("PONG")
                assertEquals(7, cast.status?.mediaSessionId)
                val pause = CompletableFuture.runAsync { cast.control("pause") }
                val control = receiver.take("PAUSE")
                assertEquals(7, JsonScan.longField(control.payload, "mediaSessionId"))
                receiver.reply(control, mediaStatus(7))
                pause.get(4, TimeUnit.SECONDS)
                receiver.send("web-4", "*", MEDIA, mediaStatus(9))
                receiver.send("receiver-0", sender, HEARTBEAT, """{"type":"PING"}""")
                receiver.take("PONG")
                assertEquals(9, cast.status?.mediaSessionId)
            }
        }
    }

    @Test
    fun validCloseAndMalformedFrameUnblockPendingRequests() {
        for (closure in listOf("close", "malformed", "disconnect")) assertPendingFailure(closure)
    }

    private fun assertPendingFailure(closure: String) {
        Receiver().use { receiver ->
            CastConnection(receiver.device).use { cast ->
                val pending = CompletableFuture.runAsync { cast.load("http://127.0.0.1/example", "Example") }
                val request = receiver.take("LOAD")
                when (closure) {
                    "malformed" -> receiver.malformedFrame()
                    "disconnect" -> receiver.disconnect()
                    else -> receiver.send("receiver-0", request.source, CONNECTION, """{"type":"CLOSE"}""")
                }
                assertFailsWith<ExecutionException> { pending.get(4, TimeUnit.SECONDS) }
                assertTrue(cast.error != null)
            }
        }
    }

    @Test
    fun idleAttachDoesNotLaunchUntilLoad() {
        Receiver("idle").use { receiver ->
            CastConnection(receiver.device).use { cast ->
                assertEquals(null, cast.status)
                assertEquals(null, receiver.poll())
                val load = CompletableFuture.runAsync { cast.load("http://127.0.0.1/example", "Example") }
                val launch = receiver.take("LAUNCH")
                receiver.reply(launch, RECEIVER_STATUS)
                val media = receiver.take("LOAD")
                receiver.reply(media, mediaStatus(7))
                load.get(4, TimeUnit.SECONDS)
                assertEquals(7, cast.status?.mediaSessionId)
            }
        }
    }

    @Test
    fun loadDeclaresTheDurationAndAReceiverStopForcesAFreshLaunch() {
        Receiver().use { receiver ->
            CastConnection(receiver.device).use { cast ->
                val sender = receiver.sender()
                val load = CompletableFuture.runAsync {
                    cast.load("http://127.0.0.1/example", "Example", duration = 5400.0)
                }
                val request = receiver.take("LOAD")
                val media = JsonScan.objectAt(request.payload, "media")!!
                assertEquals(5400.0, JsonScan.doubleField(media, "duration"))
                receiver.reply(request, mediaStatus(8))
                load.get(4, TimeUnit.SECONDS)

                // heyarr-kmp#248: a wedged Default Media Receiver is cleared only by quitting it.
                val stop = CompletableFuture.runAsync { cast.stopReceiver() }
                val quit = receiver.take("STOP")
                assertEquals(RECEIVER_NS, quit.namespace)
                assertEquals("receiver-0", quit.destination)
                assertEquals("session-1", JsonScan.stringField(quit.payload, "sessionId"))
                receiver.reply(quit, """{"type":"RECEIVER_STATUS","status":{"applications":[]}}""")
                stop.get(4, TimeUnit.SECONDS)
                assertNull(cast.status)

                // A late report from the quit app's transport is no longer ours.
                receiver.send("web-4", "*", MEDIA, mediaStatus(8))
                receiver.send("receiver-0", sender, HEARTBEAT, """{"type":"PING"}""")
                receiver.take("PONG")
                assertNull(cast.status)

                val relaunch = CompletableFuture.runAsync { cast.load("http://127.0.0.1/example", "Example") }
                val launch = receiver.take("LAUNCH")
                receiver.reply(
                    launch,
                    """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"CC1AD845",""" +
                        """"transportId":"web-5","sessionId":"session-2"}]}}""",
                )
                val fresh = receiver.take("LOAD")
                assertEquals("web-5", fresh.destination)
                assertNull(JsonScan.objectAt(fresh.payload, "media")?.let { JsonScan.doubleField(it, "duration") })
                receiver.reply(fresh, mediaStatus(9))
                relaunch.get(4, TimeUnit.SECONDS)
                assertEquals(9, cast.status?.mediaSessionId)
            }
        }
    }

    @Test
    fun rejectedInitialStatusClosesTlsSocket() {
        Receiver("reject").use { receiver ->
            assertFails { CastConnection(receiver.device) }
            receiver.awaitEof()
        }
    }

    companion object {
        private const val MEDIA = "urn:x-cast:com.google.cast.media"
        private const val RECEIVER_NS = "urn:x-cast:com.google.cast.receiver"
        private const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val RECEIVER_STATUS =
            """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"CC1AD845","transportId":"web-4",""" +
                """"sessionId":"session-1"}]}}"""

        private fun mediaStatus(id: Int) =
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":$id,"playerState":"PLAYING","currentTime":1}]}"""

        private val context: SSLContext by lazy {
            val directory = Files.createTempDirectory("cast-tls-test")
            val storePath = directory.resolve("receiver.p12")
            try {
                val command = listOf(
                    "${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "receiver",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-storetype", "PKCS12",
                    "-keystore", storePath.toString(), "-storepass", "testpass", "-keypass", "testpass",
                    "-dname", "CN=localhost", "-noprompt",
                )
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(4, TimeUnit.SECONDS)
                    error("keytool timed out")
                }
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
                val store = KeyStore.getInstance("PKCS12")
                Files.newInputStream(storePath).use { store.load(it, "testpass".toCharArray()) }
                val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keys.init(store, "testpass".toCharArray())
                SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            } finally {
                Files.deleteIfExists(storePath)
                Files.deleteIfExists(directory)
            }
        }
    }
}
