package one.rarebit.heyarr.desktop.vault.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.mcp.JsonWrite
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The daemon's control surface: a unix-domain stream socket (JDK 16+ `StandardProtocolFamily.UNIX`)
 * at the contract path `~/.cache/vault-sync.sock`, mode 0600, speaking **newline-delimited JSON**,
 * one request → one response. A connection may carry several requests (one per line); each is
 * answered on its own line and the connection stays open until the peer closes it, so a one-shot
 * `nc`/`socat` client and a persistent one both work.
 *
 * The command semantics live in [handler] (the daemon) — this class only owns the wire: bind, set
 * 0600, accept, read a line, write the handler's reply. A handler that throws never kills the
 * socket; it becomes a `{"ok":false,"error":…}` reply, matching the malformed-request contract.
 *
 * Shutdown closes the [ServerSocketChannel], which is what unblocks the blocking `accept()` (a
 * coroutine cancel alone cannot interrupt it) — so [close] both cancels the loop and closes the
 * channel, then removes the socket file.
 */
class ControlSocket(
    private val path: Path,
    private val scope: CoroutineScope,
    private val handler: (String) -> String,
) : AutoCloseable {
    @Volatile private var server: ServerSocketChannel? = null
    private var job: Job? = null

    /** Bind the socket (replacing any stale file), tighten perms to 0600, and start accepting. */
    fun start() {
        path.parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(path)
        val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        ch.bind(UnixDomainSocketAddress.of(path))
        // The socket is a local control channel — owner only. POSIX-only; ignored elsewhere.
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        server = ch
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = runCatching { ch.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { serve(client) }
            }
        }
    }

    private fun serve(client: SocketChannel) {
        client.use {
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8))
            val out = Channels.newOutputStream(client)
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val reply = runCatching { handler(line) }.getOrElse {
                        JsonWrite.obj(linkedMapOf("ok" to false, "error" to (it.message ?: "internal error")))
                    }
                    out.write((reply + "\n").toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                line = reader.readLine()
            }
        }
    }

    override fun close() {
        job?.cancel()
        runCatching { server?.close() }
        runCatching { Files.deleteIfExists(path) }
    }
}
