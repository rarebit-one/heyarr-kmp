package one.rarebit.heyarr.desktop.vault.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.desktop.vault.SyncChanges
import one.rarebit.heyarr.desktop.vault.VaultSync
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The daemon lifecycle end to end, isolated from custody/crypto/HTTP/disk: a fake [VaultSync] pass
 * and a fake [SyncChanges], with the REAL status-file writer and REAL unix control socket. Proves
 * the two machine contracts the plugin depends on — the status JSON and the socket protocol — plus
 * that the loop actually drives `syncOnce` and that pause/resume gate it.
 */
class VaultSyncDaemonTest {

    private val noStats = VaultSyncEngine.Stats(0, 0, 0, 0)

    private class FakeSync(private val body: () -> VaultSyncEngine.Stats) : VaultSync {
        val calls = AtomicInteger(0)
        override fun syncOnce(): VaultSyncEngine.Stats {
            calls.incrementAndGet()
            return body()
        }
    }

    private class FakeChanges : SyncChanges {
        private val ch = Channel<Unit>(Channel.UNLIMITED)
        fun signal() {
            ch.trySend(Unit)
        }
        override suspend fun awaitChange(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
            ch.receive()
            true
        } ?: false
    }

    private fun scope() = CoroutineScope(Dispatchers.Default + Job())

    private fun configIn(dir: Path, folder: String? = "/tmp/vault") = DaemonConfig(
        folder = folder,
        statusFile = dir.resolve("vault-sync.json"),
        socketPath = dir.resolve("vault-sync.sock"),
        pollMs = 600_000, // disarm the periodic tick; passes are explicit
        retryMs = 50,
    )

    /** One request → one response over the control socket (fresh connection each time). */
    private fun ask(socket: Path, request: String): String {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socket))
            val out = Channels.newOutputStream(ch)
            out.write((request + "\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))
            return reader.readLine() ?: fail("no response to: $request")
        }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("condition not met within $timeoutMs ms")
    }

    private fun fieldInFile(path: Path, key: String): String? =
        runCatching { JsonScan.stringField(Files.readString(path), key) }.getOrNull()

    private fun phaseInFile(path: Path): String? = fieldInFile(path, "phase")

    private fun awaitSocket(path: Path) = awaitUntil { Files.exists(path) }

    @Test
    fun resolvesRunsAPassAndWritesRunningStatus() {
        val dir = Files.createTempDirectory("vaultd")
        val scope = scope()
        try {
            val sync = FakeSync { VaultSyncEngine.Stats(2, 1, 0, 3) }
            val changes = FakeChanges()
            val daemon = VaultSyncDaemon(
                configIn(dir),
                scope,
                resolve = { VaultSyncDaemon.Resolved(sync, changes, "ed25519:test", watching = true) },
            )
            daemon.start()
            // Wait until a pass has actually been RECORDED (last_sync_at present), not merely started,
            // so the assertions below never race the recorder populating its fields.
            awaitUntil {
                val f = dir.resolve("vault-sync.json")
                sync.calls.get() >= 1 &&
                    phaseInFile(f) == "running" &&
                    runCatching { JsonScan.stringField(Files.readString(f), "last_sync_at") }.getOrNull() != null
            }

            val status = ask(dir.resolve("vault-sync.sock"), """{"cmd":"status"}""")
            assertEquals("running", JsonScan.stringField(status, "phase"))
            assertEquals("ed25519:test", JsonScan.stringField(status, "device"))
            assertEquals(true, JsonScan.boolField(status, "watching"))
            val pass = JsonScan.objectAt(status, "last_pass")!!
            assertEquals(2, JsonScan.longField(pass, "pushed")?.toInt())
            assertEquals(1, JsonScan.longField(pass, "pulled")?.toInt())
            assertEquals(3, JsonScan.longField(pass, "deleted")?.toInt())
            assertTrue(JsonScan.stringField(status, "last_sync_at")!!.endsWith("Z"))
            daemon.close()
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun syncNowTriggersAnotherPass() {
        val dir = Files.createTempDirectory("vaultd")
        val scope = scope()
        try {
            val sync = FakeSync { noStats }
            val daemon = VaultSyncDaemon(
                configIn(dir),
                scope,
                resolve = { VaultSyncDaemon.Resolved(sync, FakeChanges(), "ed25519:test", watching = true) },
            )
            daemon.start()
            awaitUntil { sync.calls.get() == 1 }
            val reply = ask(dir.resolve("vault-sync.sock"), """{"cmd":"sync-now"}""")
            assertEquals(true, JsonScan.boolField(reply, "ok"))
            awaitUntil { sync.calls.get() >= 2 }
            daemon.close()
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun pauseThenResumeMovesThePhase() {
        val dir = Files.createTempDirectory("vaultd")
        val scope = scope()
        try {
            val sync = FakeSync { noStats }
            val daemon = VaultSyncDaemon(
                configIn(dir),
                scope,
                resolve = { VaultSyncDaemon.Resolved(sync, FakeChanges(), "ed25519:test", watching = true) },
            )
            daemon.start()
            awaitUntil { phaseInFile(dir.resolve("vault-sync.json")) == "running" }

            val paused = ask(dir.resolve("vault-sync.sock"), """{"cmd":"pause"}""")
            assertEquals(true, JsonScan.boolField(paused, "ok"))
            assertEquals("paused", JsonScan.stringField(paused, "phase"))
            assertEquals("paused", JsonScan.stringField(ask(dir.resolve("vault-sync.sock"), """{"cmd":"status"}"""), "phase"))

            val resumed = ask(dir.resolve("vault-sync.sock"), """{"cmd":"resume"}""")
            assertEquals(true, JsonScan.boolField(resumed, "ok"))
            assertEquals("running", JsonScan.stringField(resumed, "phase"))
            awaitUntil { phaseInFile(dir.resolve("vault-sync.json")) == "running" }
            daemon.close()
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unknownAndMalformedRequestsAreRejected() {
        val dir = Files.createTempDirectory("vaultd")
        val scope = scope()
        try {
            val daemon = VaultSyncDaemon(
                configIn(dir),
                scope,
                resolve = { VaultSyncDaemon.Resolved(FakeSync { noStats }, FakeChanges(), "ed25519:test", watching = true) },
            )
            daemon.start()
            awaitSocket(dir.resolve("vault-sync.sock"))

            val unknown = ask(dir.resolve("vault-sync.sock"), """{"cmd":"frobnicate"}""")
            assertEquals(false, JsonScan.boolField(unknown, "ok"))
            assertTrue(JsonScan.stringField(unknown, "error")!!.isNotBlank())

            val malformed = ask(dir.resolve("vault-sync.sock"), "this is not json")
            assertEquals(false, JsonScan.boolField(malformed, "ok"))
            daemon.close()
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unresolvedCustodyStaysPreparing() {
        val dir = Files.createTempDirectory("vaultd")
        val scope = scope()
        try {
            val daemon = VaultSyncDaemon(
                configIn(dir),
                scope,
                resolve = { throw IllegalStateException("node unreachable") },
            )
            daemon.start()
            awaitSocket(dir.resolve("vault-sync.sock"))
            // Wait for the ERROR, not the phase. `phase` is already "preparing" the
            // instant start() returns — recorder is null before resolve has even been
            // attempted — so waiting on it races the resolve coroutine that records
            // last_error, and the assertion below would read a null it never settled on.
            awaitUntil { fieldInFile(dir.resolve("vault-sync.json"), "last_error") == "node unreachable" }
            val status = ask(dir.resolve("vault-sync.sock"), """{"cmd":"status"}""")
            assertEquals("preparing", JsonScan.stringField(status, "phase"))
            assertEquals("node unreachable", JsonScan.stringField(status, "last_error"))
            daemon.close()
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun noFolderConfiguredStaysPreparing() {
        val dir = Files.createTempDirectory("vaultd")
        val scope = scope()
        try {
            val daemon = VaultSyncDaemon(
                configIn(dir, folder = null),
                scope,
                resolve = { fail("resolve must not run without a folder") },
            )
            daemon.start()
            awaitSocket(dir.resolve("vault-sync.sock"))
            // Same trap: "preparing" is true from the outset, so waiting on it asserts
            // nothing. The distinguishing fact is WHY it is parked.
            awaitUntil { fieldInFile(dir.resolve("vault-sync.json"), "last_error") == "no folder configured" }
            assertEquals("preparing", phaseInFile(dir.resolve("vault-sync.json")))
            daemon.close()
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }
}
