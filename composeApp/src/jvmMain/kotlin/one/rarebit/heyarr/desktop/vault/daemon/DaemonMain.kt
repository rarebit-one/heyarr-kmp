package one.rarebit.heyarr.desktop.vault.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.net.JdkHttpTransport
import one.rarebit.heyarr.desktop.vault.FileSyncIndexStore
import one.rarebit.heyarr.desktop.vault.JdkVaultBlobStore
import one.rarebit.heyarr.desktop.vault.PeriodicOnlyChanges
import one.rarebit.heyarr.desktop.vault.RealVaultFolder
import one.rarebit.heyarr.desktop.vault.SyncChanges
import one.rarebit.heyarr.desktop.vault.VaultSpaceClient
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine
import one.rarebit.heyarr.desktop.vault.WatchedFolder
import java.io.File
import java.nio.file.Path
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
 * # Two credentials, two jobs: the CUSTODY key vs the API-WRITE credential
 *
 * Custody and API auth are independent, and the daemon uses a different key for each:
 *
 *  - **Custody (unwrap the space key)** — the Go voidbind store. This box was enrolled with the Go
 *    `voidbind pair-join` CLI and the CLI-created space is wrapped to that store's X25519 enc key,
 *    so [resolveCustody] unwraps the space key from the plaintext-hex seed ([GoDeviceStore] +
 *    [GoStoreCustody]) rather than minting a second Kotlin identity. This part is unconditional.
 *  - **API auth (read/write the encrypted state)** — a WRITE-scoped bearer token when one is
 *    configured, else the device credential. A headless WRITER needs the token: an enrolled device
 *    credential authenticates only at the READ FLOOR (ADR-0067), so its first `POST …/changes`
 *    403s. [apiCredential] therefore prefers a bearer token ([DaemonConfig.resolveApiToken] —
 *    `token` / `HEYARR_VAULT_TOKEN` / `token_file`, default `~/.config/heyarr/cli.token`) and only
 *    falls back to [VoidbindCliCredential] (device credential, read-only) when none is set.
 *
 * Both couple the daemon to the host (the Go store + CLI, and/or the heyarr CLI's token file). The
 * sync engine itself is the already-merged [VaultSyncEngine] — unchanged.
 */
fun main(args: Array<String>) {
    val config = DaemonConfig.resolve(args)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Built once so a device credential's proof cache survives across passes/retries.
    val transport: HttpTransport = JdkHttpTransport()
    val bearer = bearerCredential(config)
    val credential = bearer ?: VoidbindCliCredential(config.deviceDir).credential()

    val daemon = VaultSyncDaemon(config, scope, resolve = { resolveCustody(config, transport, credential) })

    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { daemon.close() }
            runCatching { scope.cancel() }
            done.countDown()
        },
    )

    log("starting: folder=${config.folder ?: "<none>"} space=${config.spaceId} controller=${config.controller}")
    log("device store: ${config.deviceDir}")
    log(
        "api auth: " + if (bearer != null) "bearer write token" else
            "device credential (read-floor, ADR-0067 — writes will 403; set a write token to sync)",
    )
    log("status file: ${config.statusFile}  control socket: ${config.socketPath}")
    daemon.start()
    done.await() // park the main thread; the daemon runs on [scope] until the process is signalled.
}

/**
 * Resolve custody + build the real engine (called off `Dispatchers.IO` by the daemon, retried on
 * failure). Throws when the box isn't enrolled, the node is unreachable, or this device isn't a
 * recipient of the space yet — the daemon then reports `preparing` with the reason and retries.
 */
private fun resolveCustody(
    config: DaemonConfig,
    transport: HttpTransport,
    credential: Credential,
): VaultSyncDaemon.Resolved {
    val folder = config.folder?.takeIf { it.isNotBlank() } ?: error("no folder configured")
    val store = GoDeviceStore(File(config.deviceDir))

    val custodyClient = VaultSpaceClient(transport, config.controller, credential)
    val opened = GoStoreCustody(store, custodyClient).open(config.spaceId)

    val engine = VaultSyncEngine(
        folder = RealVaultFolder(Path.of(folder)),
        blobs = JdkVaultBlobStore(),
        space = VaultSpaceClient(transport, config.controller, credential),
        // Per-vault sync index: an explicit --index-file / HEYARR_VAULT_INDEX / "index_file" keeps
        // each per-vault daemon instance's index separate without the XDG_CONFIG_HOME hack; null
        // falls back to FileSyncIndexStore's own default.
        indexStore = config.indexFile?.let { FileSyncIndexStore(File(it)) } ?: FileSyncIndexStore(),
        baseUrl = config.controller,
        credential = credential,
        spaceId = opened.spaceId,
        spaceKey = opened.spaceKey,
    )

    // A real filesystem watch when we can get one; degrade to the controller's periodic-only tick
    // (a network mount, no inotify) rather than fail the whole resolve.
    val watched = runCatching { WatchedFolder(Path.of(folder)) as SyncChanges }.getOrNull()
    return VaultSyncDaemon.Resolved(
        engine = engine,
        changes = watched ?: PeriodicOnlyChanges,
        device = store.deviceKeyRef(),
        watching = watched != null,
    )
}

/**
 * A [Credential.Bearer] over the resolved write token, or null when none is configured. Wire-
 * identical to the app's weblogin session (`Authorization: Bearer <token>`) but a long-lived,
 * write-scoped token from the heyarr CLI — the API-WRITE credential a headless writer needs, since
 * device credentials are read-floor (ADR-0067). Custody (the Go-store unwrap) is unaffected.
 */
internal fun bearerCredential(config: DaemonConfig): Credential? =
    config.resolveApiToken()?.let { Credential.Bearer(it) }

private fun log(message: String) {
    println("[vault-sync] ${StatusSnapshot.rfc3339(System.currentTimeMillis())} $message")
}
