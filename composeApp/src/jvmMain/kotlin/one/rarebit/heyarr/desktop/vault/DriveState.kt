package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.vault.Drive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What a restarted [VaultSyncEngine] resumes from: the folded remote [drive] and the [cursor] it
 * was folded up to, so a daemon restart pulls only the tail instead of the whole change log (#73).
 *
 * The pair is one value on purpose. A cursor stored apart from the drive it describes could be
 * ahead of it, and resuming from that cursor would silently skip the changes in between. A drive
 * AHEAD of its cursor is harmless, since re-folding a change is a no-op, but the engine never
 * writes one: it saves straight after a fold, when the drive is exactly the log up to the cursor.
 */
class DriveState(val cursor: Long, val drive: Drive)

/**
 * Where [DriveState] survives a restart. [load] answers null whenever it cannot vouch for what it
 * holds (missing, unreadable, corrupt, or written for another controller or space), and the
 * engine then starts from cursor 0. It never hands back a partial drive.
 */
interface DriveStateStore {
    fun load(controller: String, spaceId: String): DriveState?
    fun save(controller: String, spaceId: String, state: DriveState)
}

/** Keeps nothing, so every start is a cold start: the behaviour before #73, and the test default. */
object NoDriveStateStore : DriveStateStore {
    override fun load(controller: String, spaceId: String): DriveState? = null
    override fun save(controller: String, spaceId: String, state: DriveState) = Unit
}

/**
 * The on-disk store: one small JSON file, written atomically (a sibling temp file, then an atomic
 * rename), so a torn file can never be loaded.
 *
 * ```
 * {"version":1,"controller":"…","space_id":"…","cursor":42,"drive":{…Drive.snapshot()…}}
 * ```
 *
 * The file is bound to its controller and space. The cursor is that peer's opaque arrival
 * position and means nothing against another controller, so a repointed daemon restarts from 0
 * rather than silently desyncing. The drive is stored as [Drive.snapshot], which is deterministic,
 * so [load] can check the whole drive by re-snapshotting it and comparing bytes. A file that parses
 * but does not round-trip is refused like any other corrupt one.
 *
 * It holds plaintext paths, the same device-local trust level as `vault-index.json` beside it,
 * and is created owner-only.
 */
class FileDriveStateStore(private val file: File) : DriveStateStore {

    override fun load(controller: String, spaceId: String): DriveState? = runCatching {
        if (!file.exists()) return null
        val body = file.readText()
        val root = JsonScan.rootObject(body) ?: return null
        if (JsonScan.longField(root, "version") != VERSION) return null
        if (JsonScan.stringField(root, "controller") != controller) return null
        if (JsonScan.stringField(root, "space_id") != spaceId) return null
        val cursor = JsonScan.longField(root, "cursor")?.takeIf { it >= 0 } ?: return null
        val snapshot = JsonScan.objectAt(root, "drive") ?: return null
        val drive = Drive.fromSnapshot(snapshot)
        if (drive.snapshot() != snapshot) return null
        DriveState(cursor, drive)
    }.getOrNull()

    override fun save(controller: String, spaceId: String, state: DriveState) {
        val header = JsonWrite.obj(
            linkedMapOf(
                "version" to VERSION,
                "controller" to controller,
                "space_id" to spaceId,
                "cursor" to state.cursor,
            ),
        )
        // The snapshot is already JSON; splice it in as the last field rather than re-encode it.
        val json = header.dropLast(1) + ",\"drive\":" + state.drive.snapshot() + "}"

        val target = file.toPath().toAbsolutePath()
        val parent = target.parent.also { Files.createDirectories(it) }
        // createTempFile makes the file owner-only on POSIX, and the rename carries that over.
        val tmp = Files.createTempFile(parent, target.fileName.toString(), ".tmp")
        try {
            Files.write(tmp, json.toByteArray(Charsets.UTF_8))
            runCatching {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    companion object {
        const val VERSION = 1L

        /** The state file that belongs beside [indexFile]: `vault-index.json` → `vault-index.drive.json`. */
        fun besideIndex(indexFile: File): File =
            File(indexFile.parentFile, indexFile.name.removeSuffix(".json") + ".drive.json")
    }
}
