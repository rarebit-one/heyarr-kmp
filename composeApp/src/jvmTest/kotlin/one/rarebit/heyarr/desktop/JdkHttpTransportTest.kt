package one.rarebit.heyarr.desktop

import com.sun.net.httpserver.HttpServer
import one.rarebit.heyarr.desktop.net.JdkHttpTransport
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class JdkHttpTransportTest {
    @Test
    fun requestsAndDecodesGzipJson() {
        val body = """{"title":"Café"}"""
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/works") { exchange ->
            assertEquals("gzip", exchange.requestHeaders.getFirst("Accept-Encoding"))
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        server.start()
        try {
            val response = JdkHttpTransport().get("http://127.0.0.1:${server.address.port}/works", emptyMap())
            assertEquals(200, response.status)
            assertEquals(body, response.body)
        } finally {
            server.stop(0)
        }
    }
}
