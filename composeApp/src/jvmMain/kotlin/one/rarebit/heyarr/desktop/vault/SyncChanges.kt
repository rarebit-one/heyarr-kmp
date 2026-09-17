package one.rarebit.heyarr.desktop.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * How the sync daemon learns the designated folder changed. A seam so [VaultSyncController]
 * is tested with a controllable fake instead of a real filesystem watcher, and so a folder
 * that cannot be watched (a network mount, a platform without inotify) degrades to the
 * periodic-scan safety net rather than failing.
 *
 * [awaitChange] blocks until a local change is seen or the timeout elapses, coalescing a
 * burst of edits into one wake-up. It must be interruptible: the controller cancels its
 * loop by cancelling the coroutine that is parked here.
 */
interface SyncChanges : AutoCloseable {
    /** Suspend until a change is observed (→ true) or [timeoutMs] elapses (→ false). */
    suspend fun awaitChange(timeoutMs: Long): Boolean

    override fun close() {}
}

/** The periodic-only degrade: never reports a change, so the controller falls back to its tick. */
object PeriodicOnlyChanges : SyncChanges {
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        kotlinx.coroutines.delay(timeoutMs)
        return false
    }
}

/**
 * A [SyncChanges] over a JDK [WatchService] rooted at the vault folder. The service is not
 * recursive, so [registerAll] walks the tree once and every newly-created directory is
 * registered as it appears — best-effort, because the controller's periodic scan catches
 * anything the watch misses (a rename storm, an OVERFLOW, an unwatchable subtree). The
 * daemon's own scratch dirs ([RealVaultFolder]'s `.sync-tmp` / `.sync-trash`) are skipped
 * so a download or a local-delete never wakes the loop to chase its own writes.
 *
 * Construction registers the initial tree and can throw [IOException]; the caller falls
 * back to [PeriodicOnlyChanges] when it does.
 */
class WatchedFolder(private val root: Path) : SyncChanges {
    private val watcher: WatchService = FileSystems.getDefault().newWatchService()

    init {
        registerAll(root)
    }

    override suspend fun awaitChange(timeoutMs: Long): Boolean = runInterruptible(Dispatchers.IO) {
        val first: WatchKey = watcher.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return@runInterruptible false
        // Drain this key and any others that are already queued, so a burst of edits is one wake-up.
        var key: WatchKey? = first
        while (key != null) {
            drain(key)
            key.reset()
            key = watcher.poll() // non-blocking: only keys ready right now
        }
        true
    }

    /** Register any directories created inside this key's dir, so the watch grows with the tree. */
    private fun drain(key: WatchKey) {
        val dir = key.watchable() as? Path ?: return
        for (event in key.pollEvents()) {
            if (event.kind() === OVERFLOW) continue
            val context = event.context() as? Path ?: continue
            val child = dir.resolve(context)
            if (event.kind() === ENTRY_CREATE && runCatching { Files.isDirectory(child) }.getOrDefault(false)) {
                registerAll(child)
            }
        }
    }

    private fun registerAll(start: Path) {
        if (!Files.isDirectory(start)) return
        runCatching {
            Files.walk(start).use { stream ->
                stream.filter { Files.isDirectory(it) }.filter { notScratch(it) }.forEach { dir ->
                    runCatching { dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
                }
            }
        }
    }

    /** The daemon's own in-flight/trash dirs — watching them would chase our own writes. */
    private fun notScratch(dir: Path): Boolean {
        val name = dir.fileName?.toString()
        return name != ".sync-tmp" && name != ".sync-trash"
    }

    override fun close() {
        runCatching { watcher.close() }
    }
}
