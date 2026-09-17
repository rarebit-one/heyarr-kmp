package one.rarebit.heyarr.desktop.vault.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch

/**
 * The HEADLESS vault-sync daemon entry point — a plain JVM `main()` that NEVER starts Compose.
 *
 * Run it with the class on the classpath (the GUI app's own Main-Class stays the Compose window):
 *
 * ```
 * java -cp heyarr-vault-sync-all.jar one.rarebit.heyarr.desktop.vault.daemon.DaemonMainKt \
 *     --folder /home/alarm/Vault
 * ```
 *
 * Config comes from (defaults → `~/.config/heyarr-vault-sync/config.json` → env → args); see
 * [DaemonConfig]. It brings up the status file + control socket, then runs the sync loop until the
 * process is signalled (systemd stop / Ctrl-C), on which the shutdown hook tears the daemon down
 * cleanly (stops the loop, closes + removes the socket).
 *
 * # Custody wiring (PR2)
 *
 * This PR1 slice ships the full daemon shell — config, status contract, control socket, the loop
 * wired to the real [VaultSyncController] behind an injected engine/custody seam — but the seam is
 * not yet fed a real engine, so a live run sits in the `preparing` phase reporting that custody is
 * pending. PR2 replaces [pendingCustody] with the Go-store reader + `voidbind identity credential`
 * shim + the real [one.rarebit.heyarr.desktop.vault.VaultSyncEngine]; nothing else here changes.
 */
fun main(args: Array<String>) {
    val config = DaemonConfig.resolve(args)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val daemon = VaultSyncDaemon(config, scope, resolve = ::pendingCustody)

    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { daemon.close() }
            runCatching { scope.cancel() }
            done.countDown()
        },
    )

    log("starting: folder=${config.folder ?: "<none>"} space=${config.spaceId} controller=${config.controller}")
    log("status file: ${config.statusFile}  control socket: ${config.socketPath}")
    daemon.start()
    done.await() // park the main thread; the daemon runs on [scope] until the process is signalled.
}

/**
 * The PR1 custody seam: not yet implemented, so the daemon retries and reports `preparing`. PR2
 * swaps in the real Go-store custody + credential shim + engine here.
 */
private fun pendingCustody(): VaultSyncDaemon.Resolved =
    throw UnsupportedOperationException("custody wiring lands in PR2 (Go device-store reader + voidbind credential shim)")

private fun log(message: String) {
    println("[vault-sync] ${StatusSnapshot.rfc3339(System.currentTimeMillis())} $message")
}
