package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.desktop.games.GameFile
import one.rarebit.heyarr.desktop.games.GameLibrary
import one.rarebit.heyarr.desktop.games.GamePlan
import one.rarebit.heyarr.desktop.games.GameResult
import one.rarebit.heyarr.desktop.games.GameSettings
import one.rarebit.heyarr.desktop.games.JdkGameFileFetcher
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.music.Track
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * A game's title page block: install it from the node onto this machine and start it
 * (heyarr-core ADR-0118/0119). Install downloads the chosen edition's installer files and
 * unpacks them; Play runs the game under umu (or its own start script for a native
 * build), with the wrappers toggled here saved per game.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GameBlock(session: AppSession, detail: WorkDetail, state: DetailState) {
    val ui = remember(detail.work.id) { GameUi(session, detail.work.id, detail.work.title, GameLibrary()) }
    val scope = rememberCoroutineScope()
    val files = installerFiles(state.assets.orEmpty())
    Panel("Play") {
        Text(ui.statusLine(files), style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.installed) {
                PrimaryButton("Play", { scope.launch { ui.play() } }, icon = HeyarrIcons.Play, enabled = !ui.busy)
            }
            if (files != null) {
                SecondaryButton(
                    if (ui.installed) "Reinstall" else "Install",
                    { scope.launch { ui.install(files) } },
                    icon = HeyarrIcons.Download,
                    enabled = !ui.busy,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("GameMode", ui.settings.gamemode, { ui.toggle { it.copy(gamemode = !it.gamemode) } })
            FilterChip("MangoHud", ui.settings.mangohud, { ui.toggle { it.copy(mangohud = !it.mangohud) } })
            FilterChip("Gamescope", ui.settings.gamescope, { ui.toggle { it.copy(gamescope = !it.gamescope) } })
        }
    }
}

/** The block's state and its actions, so the composable only lays them out. */
private class GameUi(
    private val session: AppSession,
    private val workId: String,
    private val title: String,
    private val library: GameLibrary,
) {
    var installed by mutableStateOf(library.isInstalled(workId))
    var busy by mutableStateOf(false)
    var settings by mutableStateOf(library.settings.load(workId))
    private var status by mutableStateOf<String?>(null)

    fun statusLine(files: List<GameFile>?): String = status ?: when {
        installed -> "Installed on this machine."
        files == null -> "No installer this launcher can install is held for this game yet."
        else -> "Not installed here. ${files.size} installer file(s) on the node."
    }

    suspend fun play() {
        // Busy while the game is being started, so a double click cannot start two
        // processes on one Wine prefix.
        busy = true
        val r = local { library.launch(workId) }
            .getOrElse { GameResult.Failed("Launch failed: ${it.javaClass.simpleName}.") }
        busy = false
        if (r is GameResult.Failed) session.toast(Toast.Kind.ERROR, "Couldn't start the game", r.message)
    }

    suspend fun install(files: List<GameFile>) {
        busy = true
        val fetcher = JdkGameFileFetcher(session.config.baseUrl, credentialHeaders = { session.credential.asHeader() })
        val r = local { library.install(workId, files, fetcher) { step -> status = step } }
            .getOrElse { GameResult.Failed("Install failed: ${it.javaClass.simpleName}.") }
        busy = false
        status = null
        // From the library, not the result: a failed reinstall has already removed the
        // old tree and its marker, so "installed" must fall back to false with it.
        installed = library.isInstalled(workId)
        when (r) {
            is GameResult.Failed -> session.toast(Toast.Kind.ERROR, "Couldn't install the game", r.message)
            GameResult.Done -> session.toast(Toast.Kind.SUCCESS, "Installed $title")
        }
    }

    /**
     * Blocking local work off the UI thread. Not `session.io`: that records a reachable
     * node on every return, and starting a game or unpacking one says nothing about it.
     */
    private suspend fun <T> local(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

    fun toggle(change: (GameSettings) -> GameSettings) {
        settings = change(settings)
        library.settings.save(workId, settings)
    }
}

/**
 * The installer set to install: the work's primary, held files grouped by edition (one
 * edition is one store build), with [GamePlan.chooseEdition] picking among them.
 */
private fun installerFiles(assets: List<Track>): List<GameFile>? {
    // An edition with any part missing is not installable: innoextract needs every
    // -N.bin beside the .exe, so an incomplete set is left out rather than chosen.
    val byEdition = assets
        .filter { it.isPrimaryRole }
        .groupBy { it.editionId }
        .filterValues { tracks -> tracks.all { it.isPlayable } }
        .mapValues { (_, tracks) ->
            tracks.map { GameFile(it.filename ?: it.id, it.blobHash!!, it.sizeBytes) }
        }
    return GamePlan.chooseEdition(byEdition, System.getProperty("os.arch").orEmpty())
}
