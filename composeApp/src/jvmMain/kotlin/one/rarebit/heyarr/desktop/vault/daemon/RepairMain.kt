package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.desktop.net.JdkHttpTransport
import one.rarebit.heyarr.desktop.vault.JdkVaultBlobStore
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * One-shot vault REPAIR: find the drive entries whose blobs the node no longer holds, and with
 * `--apply` re-seal and re-upload the ones this device still has (see [VaultSyncEngine.repair]).
 *
 * ```
 * systemctl --user stop heyarr-vault-sync@personal
 * java -cp "$JARS" one.rarebit.heyarr.desktop.vault.daemon.RepairMainKt \
 *     --config ~/.config/heyarr-vault-sync/personal.json            # report only
 * java -cp "$JARS" one.rarebit.heyarr.desktop.vault.daemon.RepairMainKt --config … --apply
 * systemctl --user start heyarr-vault-sync@personal
 * ```
 *
 * It takes the daemon's config (same file, env and flags, see [DaemonConfig]) and writes the same
 * sync index and drive state, so it refuses to run while that vault's daemon answers on its control
 * socket. Exit status: 0 when nothing is left missing, 2 when entries remain that this device
 * cannot repair, 1 on an error.
 */
@Suppress("TooGenericExceptionCaught") // the process boundary: any failure becomes a message and exit 1
fun main(args: Array<String>) {
    exitProcess(
        try {
            repairMain(args)
        } catch (e: Exception) {
            System.err.println("vault-repair: ${e.message ?: e::class.simpleName}")
            1
        },
    )
}

internal fun repairMain(args: Array<String>): Int {
    val config = DaemonConfig.resolve(args)
    val apply = args.any { it == "--apply" || it == "--apply=true" }
    check(!daemonAnswers(config.socketPath)) {
        "the vault-sync daemon is running (control socket ${config.socketPath} answers); stop it first"
    }

    val bearer = bearerCredential(config)
    check(!apply || bearer != null) {
        "--apply needs a write token: a device credential is read-floor (ADR-0067) and cannot push changes"
    }
    val credential = bearer ?: VoidbindCliCredential(config.deviceDir).credential()
    val blobs = JdkVaultBlobStore()
    val engine = buildEngine(config, JdkHttpTransport(), credential, GoDeviceStore(File(config.deviceDir)), blobs)

    println("vault-repair: checking space ${config.spaceId} against ${config.controller}")
    val report = engine.repair(blobs.presence(config.controller, credential), apply)
    print(formatRepairReport(report, apply))
    // Left over = what neither this run nor a re-run with --apply would fix.
    val left = report.changedLocally.size + report.unrecoverable.size +
        if (apply) report.repairable.size - report.repaired.size else 0
    return if (left == 0) 0 else 2
}

/** The report as printed: counts first, then each non-empty path list under its heading. */
internal fun formatRepairReport(r: VaultSyncEngine.RepairReport, apply: Boolean): String = buildString {
    appendLine("checked ${r.checked} entries; ${r.missing.size} missing blobs")
    appendLine("  repairable from this device: ${r.repairable.size}")
    if (apply) appendLine("  repaired: ${r.repaired.size}")
    appendLine("  changed locally since sync (left alone): ${r.changedLocally.size}")
    appendLine("  no local copy (unrecoverable here): ${r.unrecoverable.size}")
    if (!apply && r.repairable.isNotEmpty()) appendLine("re-run with --apply to re-upload the repairable entries")
    fun section(title: String, paths: List<String>) {
        if (paths.isEmpty()) return
        appendLine()
        appendLine("$title:")
        paths.sorted().forEach { appendLine("  $it") }
    }
    section(if (apply) "repaired" else "repairable", if (apply) r.repaired else r.repairable)
    section("changed locally", r.changedLocally)
    section("unrecoverable here", r.unrecoverable)
}

/** Whether a daemon is listening on [socket]: a stale socket file left by a crash does not count. */
internal fun daemonAnswers(socket: Path): Boolean {
    if (!Files.exists(socket)) return false
    return runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
    }.isSuccess
}
