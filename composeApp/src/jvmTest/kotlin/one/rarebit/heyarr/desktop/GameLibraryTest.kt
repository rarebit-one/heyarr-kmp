package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.games.Command
import one.rarebit.heyarr.desktop.games.FileGameSettingsStore
import one.rarebit.heyarr.desktop.games.GameFile
import one.rarebit.heyarr.desktop.games.GameLibrary
import one.rarebit.heyarr.desktop.games.GamePaths
import one.rarebit.heyarr.desktop.games.GameResult
import one.rarebit.heyarr.desktop.games.GameSettings
import one.rarebit.heyarr.desktop.games.ProcessRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The install → launch flow through fakes: no node, no innoextract, no Wine. A fake
 * unpacker writes the tree a GOG installer would leave, so the launcher has to find the
 * game's program the way it will on a real install.
 */
class GameLibraryTest {

    private class FakeRunner(
        private val missing: Set<String> = emptySet(),
        private val unpack: (Command) -> Int = { 0 },
    ) : ProcessRunner {
        val ran = mutableListOf<Command>()
        val started = mutableListOf<Command>()

        override fun run(cmd: Command): Int {
            ran += cmd
            return unpack(cmd)
        }

        override fun start(cmd: Command) {
            started += cmd
        }

        override fun available(program: String): Boolean = program !in missing
    }

    private val files = listOf(
        GameFile("setup_some_game_1.2_(1234).exe", "blake3:a", 3),
        GameFile("setup_some_game_1.2_(1234)-1.bin", "blake3:b", 3),
    )

    /** Writes what `innoextract --gog` leaves: the game under app/, with GOG's .info. */
    private fun gogTree(cmd: Command): Int {
        val dir = File(cmd.argv[cmd.argv.indexOf("-d") + 1], "app")
        File(dir, "bin").mkdirs()
        File(dir, "bin/game.exe").writeText("")
        File(dir, "goggame-1234.info").writeText(
            """{"playTasks":[{"type":"FileTask","path":"bin\\game.exe","workingDir":"bin","isPrimary":true}]}""",
        )
        return 0
    }

    private fun library(root: File, runner: ProcessRunner, arch: String = "amd64"): GameLibrary {
        val paths = GamePaths(root)
        return GameLibrary(paths, runner, FileGameSettingsStore(paths), arch)
    }

    @Test
    fun aGogGameInstallsAndLaunchesUnderUmu() {
        val root = Files.createTempDirectory("games").toFile()
        val runner = FakeRunner(unpack = ::gogTree)
        val lib = library(root, runner)
        val fetched = mutableListOf<String>()

        val installed = lib.install("work-1", files, { f, target ->
            fetched += f.name
            target.writeText("abc")
            null
        })
        assertEquals(GameResult.Done, installed)
        assertTrue(lib.isInstalled("work-1"))
        assertEquals(files.map { it.name }, fetched)
        assertEquals("innoextract", runner.ran.single().argv.first())

        assertEquals(GameResult.Done, lib.launch("work-1"))
        val launch = runner.started.single()
        assertEquals(listOf("umu-run", File(root, "work-1/install/app/bin/game.exe").absolutePath), launch.argv)
        assertEquals(File(root, "work-1/prefix").absolutePath, launch.env["WINEPREFIX"])
        assertTrue(File(root, "work-1/prefix").isDirectory, "the prefix is made on first launch")

        // A reinstall reuses installer files already held at their full size.
        fetched.clear()
        assertEquals(GameResult.Done, lib.install("work-1", files, { f, _ -> "should not fetch ${f.name}" }))
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aMissingToolIsNamedBeforeAnythingIsDownloaded() {
        val root = Files.createTempDirectory("games").toFile()
        val lib = library(root, FakeRunner(missing = setOf("innoextract")))
        var fetched = false
        val result = lib.install("work-1", files, { _, _ ->
            fetched = true
            null
        })
        assertIs<GameResult.Failed>(result)
        assertTrue(result.message.contains("innoextract"), result.message)
        assertFalse(fetched)
    }

    @Test
    fun aFailedDownloadOrUnpackLeavesTheGameUninstalled() {
        val root = Files.createTempDirectory("games").toFile()
        val failedFetch = library(root, FakeRunner(unpack = ::gogTree))
            .install("work-1", files, { _, _ -> "Downloading failed: HTTP 404." })
        assertEquals(GameResult.Failed("Downloading failed: HTTP 404."), failedFetch)

        val lib = library(root, FakeRunner(unpack = { 2 }))
        val failedUnpack = lib.install("work-2", files, { _, t ->
            t.writeText("abc")
            null
        })
        assertIs<GameResult.Failed>(failedUnpack)
        assertFalse(lib.isInstalled("work-2"))
        assertIs<GameResult.Failed>(lib.launch("work-2"))
    }

    @Test
    fun umuMissingIsReportedAtLaunch() {
        val root = Files.createTempDirectory("games").toFile()
        val ok = FakeRunner(unpack = ::gogTree)
        library(root, ok).install("work-1", files, { _, t ->
            t.writeText("abc")
            null
        })
        val result = library(root, FakeRunner(missing = setOf("umu-run"))).launch("work-1")
        assertIs<GameResult.Failed>(result)
        assertTrue(result.message.contains("umu-run"), result.message)
    }

    @Test
    fun aServerFileNameCannotClimbOutOfTheGameDirectory() {
        assertEquals("setup.exe", GameLibrary.safeName("../../etc/setup.exe"))
        assertEquals("x.bin", GameLibrary.safeName("dlc\\x.bin"))
        assertEquals(null, GameLibrary.safeName(".."))
        assertEquals("_etc_passwd", GamePaths.safeId("/etc/passwd"))
    }

    @Test
    fun settingsRoundTripAndDefaultWhenAbsentOrDamaged() {
        val root = Files.createTempDirectory("games").toFile()
        val store = FileGameSettingsStore(GamePaths(root))
        assertEquals(GameSettings(), store.load("work-1"))

        val custom = GameSettings(
            protonPath = "GE-Proton9-20",
            umuGameId = "umu-123",
            exeOverride = "bin/other.exe",
            extraArgs = "-w \"a b\"",
            gamemode = true,
            mangohud = true,
            gamescope = true,
            gamescopeArgs = "-f",
        )
        store.save("work-1", custom)
        assertEquals(custom, store.load("work-1"))

        GamePaths(root).settingsFile("work-2").apply {
            parentFile.mkdirs()
            writeText("{ not json")
        }
        assertEquals(GameSettings(), store.load("work-2"))
    }
    private fun zipOf(vararg entries: Pair<String, Int>): (Command) -> Int = { cmd ->
        val dir = File(cmd.argv[cmd.argv.indexOf("-d") + 1])
        for ((path, size) in entries) {
            File(dir, path).apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(size))
                if (!path.contains('.') || path.endsWith(".sh") || path.endsWith(".x86_64")) setExecutable(true)
            }
        }
        0
    }

    private val zip = listOf(GameFile("Some Game.zip", "blake3:z", 3))

    private fun installZip(root: File, runner: FakeRunner): GameLibrary {
        val lib = library(root, runner)
        val r = lib.install("work-z", zip, { _, t ->
            t.writeText("abc")
            null
        })
        assertEquals(GameResult.Done, r)
        return lib
    }

    @Test
    fun aPlainZipOfAWindowsGameRunsItsGameNotItsHelpers() {
        val root = Files.createTempDirectory("games").toFile()
        val runner = FakeRunner(
            unpack = zipOf(
                "Some Game/SomeGame.exe" to 4096,
                "Some Game/unins000.exe" to 8192,
                "Some Game/redist/vc_redist.x64.exe" to 16384,
                "Some Game/UnityCrashHandler64.exe" to 9000,
            ),
        )
        val lib = installZip(root, runner)
        assertEquals(GameResult.Done, lib.launch("work-z"))
        val launch = runner.started.single()
        assertEquals(listOf("umu-run", File(root, "work-z/install/Some Game/SomeGame.exe").absolutePath), launch.argv)
        assertEquals(null, launch.env["STORE"], "a plain zip is not a GOG install")
    }

    @Test
    fun aPlainZipOfANativeGameRunsItsStartScript() {
        val root = Files.createTempDirectory("games").toFile()
        val runner = FakeRunner(unpack = zipOf("game/start.sh" to 10, "game/lib/libfoo.so" to 10))
        val lib = installZip(root, runner)
        assertEquals(GameResult.Done, lib.launch("work-z"))
        assertEquals(
            listOf("bash", File(root, "work-z/install/game/start.sh").absolutePath),
            runner.started.single().argv,
        )
    }

    @Test
    fun aFailedReinstallIsNotLeftLookingInstalled() {
        val root = Files.createTempDirectory("games").toFile()
        val lib = library(root, FakeRunner(unpack = ::gogTree))
        lib.install("work-1", files, { _, t ->
            t.writeText("abc")
            null
        })
        assertTrue(lib.isInstalled("work-1"))
        val broken = library(root, FakeRunner(unpack = { 2 }))
        assertIs<GameResult.Failed>(broken.install("work-1", files, { _, _ -> null }))
        assertFalse(broken.isInstalled("work-1"), "the old tree is gone; Play must not be offered over it")
    }

    @Test
    fun aPlainZipOfANativeBinaryWithAConventionalSuffixRuns() {
        val root = Files.createTempDirectory("games").toFile()
        val runner = FakeRunner(
            unpack = zipOf("game/Game.x86_64" to 10, "game/Game_Data/globalgamemanagers.assets" to 10),
        )
        val lib = installZip(root, runner)
        assertEquals(GameResult.Done, lib.launch("work-z"))
        assertEquals(listOf(File(root, "work-z/install/game/Game.x86_64").absolutePath), runner.started.single().argv)
    }

    @Test
    fun aReuploadedInstallerOfTheSameSizeIsFetchedAgain() {
        val root = Files.createTempDirectory("games").toFile()
        val lib = library(root, FakeRunner(unpack = ::gogTree))
        val write: (GameFile, File) -> String? = { _, t ->
            t.writeText("abc")
            null
        }
        assertEquals(GameResult.Done, lib.install("work-1", files, write))
        val fetched = mutableListOf<String>()
        val reuploaded = files.map { it.copy(blobHash = it.blobHash + "-new") }
        assertEquals(
            GameResult.Done,
            lib.install("work-1", reuploaded, { f, t ->
                fetched += f.name
                t.writeText("xyz")
                null
            }),
        )
        assertEquals(files.map { it.name }, fetched, "same name and size, different blob: not the held file")
    }

    @Test
    fun aNativeGameIsChosenOverItsHelpers() {
        val root = Files.createTempDirectory("games").toFile()
        val runner = FakeRunner(
            unpack = zipOf("game/Game.x86_64" to 10, "game/UnityCrashHandler64" to 20),
        )
        val lib = installZip(root, runner)
        assertEquals(GameResult.Done, lib.launch("work-z"))
        assertEquals(listOf(File(root, "work-z/install/game/Game.x86_64").absolutePath), runner.started.single().argv)
    }
}
