package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.games.Command
import one.rarebit.heyarr.desktop.games.GameFile
import one.rarebit.heyarr.desktop.games.GamePlan
import one.rarebit.heyarr.desktop.games.GameSettings
import one.rarebit.heyarr.desktop.games.InstallerKind
import one.rarebit.heyarr.desktop.games.LaunchTarget
import one.rarebit.heyarr.desktop.games.PlayTask
import one.rarebit.heyarr.desktop.games.ShellArgs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launcher's decisions, proven without a game, a prefix or a process: which installer a
 * set of files is, the exact command that unpacks it, which program GOG says is the game,
 * and the exact launch line umu is given.
 */
class GamePlanTest {

    private fun f(name: String) = GameFile(name, "blake3:x", 1)

    private val gogWindows = listOf(
        f("setup_some_game_1.2_(1234).exe"),
        f("setup_some_game_1.2_(1234)-1.bin"),
    )

    @Test
    fun installerKindsAreRecognised() {
        assertEquals(InstallerKind.GOG_WINDOWS, GamePlan.kindOf(gogWindows))
        assertEquals(InstallerKind.GOG_LINUX, GamePlan.kindOf(listOf(f("some_game_1_2_1234.sh"))))
        assertEquals(InstallerKind.ZIP, GamePlan.kindOf(listOf(f("Some Game.zip"))))
        assertEquals(InstallerKind.UNKNOWN, GamePlan.kindOf(listOf(f("manual.pdf"))))
        // A GOG set wins over a stray archive beside it.
        assertEquals(InstallerKind.GOG_WINDOWS, GamePlan.kindOf(gogWindows + f("soundtrack.zip")))
    }

    @Test
    fun theEditionToInstallSuitsTheMachine() {
        val editions = mapOf(
            "e-win" to gogWindows,
            "e-linux" to listOf(f("some_game_1_2_1234.sh")),
            "e-zip" to listOf(f("Some Game.zip")),
        )
        // A native build needs no Wine at all on x86…
        assertEquals("some_game_1_2_1234.sh", GamePlan.chooseEdition(editions, "amd64")?.single()?.name)
        // …but on aarch64 everything runs in the x86 VM, where the Windows build is the tuned path.
        assertEquals(gogWindows, GamePlan.chooseEdition(editions, "aarch64"))
        assertEquals(
            "Some Game.zip",
            GamePlan.chooseEdition(mapOf("e-zip" to editions["e-zip"]!!), "amd64")?.single()?.name,
        )
        assertNull(GamePlan.chooseEdition(mapOf("e" to listOf(f("manual.pdf"))), "amd64"))
    }

    @Test
    fun aGogWindowsSetIsUnpackedWithInnoextractNotRunUnderWine() {
        val cmd = GamePlan.installCommand(gogWindows, File("/g/installer"), File("/g/install"))
        assertEquals(
            Command(
                listOf(
                    "innoextract",
                    "--gog",
                    "--silent",
                    "-d",
                    "/g/install",
                    "/g/installer/setup_some_game_1.2_(1234).exe",
                ),
            ),
            cmd,
        )
    }

    @Test
    fun aGogLinuxInstallerIsUnzippedNotRun() {
        val cmd = GamePlan.installCommand(listOf(f("game.sh")), File("/g/installer"), File("/g/install"))
        assertEquals(
            listOf("unzip", "-o", "-qq", "/g/installer/game.sh", "data/noarch/*", "-d", "/g/install"),
            cmd?.argv,
        )
        // MojoSetup's shell prefix makes unzip warn (exit 1); that is success.
        assertTrue(GamePlan.installSucceeded(InstallerKind.GOG_LINUX, 1))
        assertTrue(!GamePlan.installSucceeded(InstallerKind.GOG_WINDOWS, 1))
        assertNull(GamePlan.installCommand(listOf(f("manual.pdf")), File("/i"), File("/o")))
    }

    @Test
    fun gogNamesItsPrimaryProgram() {
        val info = """
            {
              "gameId": "1234",
              "name": "Some Game",
              "playTasks": [
                { "category": "tool", "type": "FileTask", "path": "tools\\editor.exe", "isPrimary": false },
                { "type": "URLTask", "link": "https://example.invalid", "isPrimary": false },
                { "type": "FileTask", "path": "bin\\x64\\game.exe", "workingDir": "bin\\x64", "arguments": "-skipintro", "isPrimary": true }
              ]
            }
        """.trimIndent()
        assertEquals(PlayTask("bin/x64/game.exe", "bin/x64", "-skipintro"), GamePlan.primaryPlayTask(info))
        assertNull(GamePlan.primaryPlayTask("""{"playTasks":[]}"""))
    }

    @Test
    fun aWindowsGameRunsUnderUmuInItsOwnPrefix() {
        val cmd = GamePlan.launchCommand(
            InstallerKind.GOG_WINDOWS,
            LaunchTarget(File("/g/install/app"), PlayTask("bin/x64/game.exe", "bin/x64", "-skipintro")),
            GameSettings(protonPath = "GE-Proton", extraArgs = "-windowed"),
            File("/g/prefix"),
            "amd64",
        )
        assertEquals(listOf("umu-run", "/g/install/app/bin/x64/game.exe", "-skipintro", "-windowed"), cmd.argv)
        assertEquals(
            mapOf("WINEPREFIX" to "/g/prefix", "GAMEID" to "0", "STORE" to "gog", "PROTONPATH" to "GE-Proton"),
            cmd.env,
        )
        assertEquals(File("/g/install/app/bin/x64"), cmd.cwd)
    }

    @Test
    fun theWrappersNestTheWayTheirOwnDocsDo() {
        val cmd = GamePlan.launchCommand(
            InstallerKind.GOG_WINDOWS,
            LaunchTarget(File("/g"), PlayTask("game.exe")),
            GameSettings(gamemode = true, gamescope = true, gamescopeArgs = "-W 1920 -H 1080 -f", mangohud = true),
            File("/p"),
            "x86_64",
        )
        assertEquals(
            listOf("gamemoderun", "gamescope", "-W", "1920", "-H", "1080", "-f", "--") +
                listOf("mangohud", "umu-run", "/g/game.exe"),
            cmd.argv,
        )
    }

    @Test
    fun aNativeGameRunsItsOwnStartScript() {
        val cmd = GamePlan.launchCommand(
            InstallerKind.GOG_LINUX,
            LaunchTarget(File("/g/install/data/noarch"), PlayTask("start.sh")),
            GameSettings(),
            File("/p"),
            "amd64",
        )
        // Through bash: a start script unpacked without its executable bit still runs.
        assertEquals(listOf("bash", "/g/install/data/noarch/start.sh"), cmd.argv)
        assertTrue(cmd.env.isEmpty(), "a native game needs no Wine environment")
    }

    @Test
    fun onArmTheWholeLineRunsInTheX86MicroVm() {
        val cmd = GamePlan.launchCommand(
            InstallerKind.GOG_WINDOWS,
            LaunchTarget(File("/g/My Game"), PlayTask("game.exe")),
            GameSettings(),
            File("/p"),
            "aarch64",
        )
        assertEquals(listOf("muvm", "--", "FEXBash", "-c"), cmd.argv.take(4))
        assertEquals(
            "env WINEPREFIX=/p GAMEID=0 STORE=gog umu-run '/g/My Game/game.exe'",
            cmd.argv[4],
        )
        assertTrue(cmd.env.isEmpty(), "the VM does not inherit the environment; it rides in the line")
    }

    @Test
    fun shellQuotingSurvivesQuotesAndSpaces() {
        assertEquals("plain/path.exe", ShellArgs.quote("plain/path.exe"))
        assertEquals("'it'\\''s here'", ShellArgs.quote("it's here"))
        assertEquals("''", ShellArgs.quote(""))
        assertEquals("'\$HOME'", ShellArgs.quote("\$HOME"))
    }

    @Test
    fun argumentsSplitOnSpacesAndHonourQuotes() {
        assertEquals(
            listOf("-w", "1920", "--config", "My Settings.ini"),
            ShellArgs.split("-w 1920  --config \"My Settings.ini\""),
        )
        assertEquals(emptyList(), ShellArgs.split("   "))
    }
}
