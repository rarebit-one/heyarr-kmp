package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.playback.Copies
import one.rarebit.heyarr.core.playback.DisplayFit
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.music.TracksJson
import one.rarebit.heyarr.desktop.playback.PlaybackResume
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.FileSettingsStore
import one.rarebit.heyarr.desktop.state.FilmCopies
import one.rarebit.heyarr.desktop.state.PlaybackSession
import one.rarebit.heyarr.desktop.ui.Route
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** heyarr-kmp#225 on the desktop: a film's copies, the Version switch's hand-over, and what is kept on this machine. */
class FilmCopiesTest {
    private val hash = "blake3:" + "a".repeat(64)

    // Concatenated, so the JSON stays on one line (JsonScan is a field scanner, not a parser).
    private val assets = TracksJson.parse(
        """{"items":[{"id":"uhd","edition_id":"e1","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.2160p.HDR.mkv","blob_mime":"video/x-matroska","blob_size":50000000000},""" +
            """{"id":"fhd","edition_id":"e2","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.1080p.mkv","blob_mime":"video/x-matroska","blob_size":6000000000}]}""",
    )

    private fun film(assetId: String, workId: String = "w1") =
        Route.Player(workId, assetId, hash, "Film", typeHint = MediaType.MOVIE)

    @Test fun theDesktopsTracksAreCopies() {
        val choice = Copies.choose(assets, DisplayFit(1080), QualityPreference.FIT, defaultId = "uhd")!!
        assertEquals("fhd", choice.picked.asset.id)
        assertEquals(listOf("2160p HDR · 46.6 GB", "1080p · 5.6 GB"), choice.copies.map { it.label })
    }

    @Test fun copiesStayWhileTheSameWorkPlaysAndGoWithAnother() {
        val playback = PlaybackSession()
        val choice = Copies.choose(assets, DisplayFit(1080))!!
        playback.filmCopies = FilmCopies("w1", choice.copies)
        playback.play(film("fhd"))
        assertEquals(2, playback.copies.size)
        // The Version switch: another file of the same work, picking up where the last one was.
        playback.pendingResume = PlaybackResume(754.0, paused = false)
        playback.play(film("uhd"))
        assertEquals(2, playback.copies.size, "a switch keeps the menu")
        assertTrue(playback.pendingStart, "a switch is a fresh load")
        assertEquals(754.0, playback.pendingResume?.position)
        playback.play(film("other", workId = "w2"))
        assertTrue(playback.copies.isEmpty(), "another work has none of these copies")
        playback.stop()
        assertNull(playback.filmCopies)
        assertNull(playback.pendingResume)
    }

    @Test fun thePreferenceAndChoicesPersistOnThisMachine() {
        val file = Files.createTempDirectory("heyarr-config").resolve("config.json").toFile()
        val store = FileSettingsStore(file)
        assertNull(store.load().playbackPreference)
        assertTrue(store.load().chosenCopies.isEmpty())
        store.save(DesktopConfig(playbackPreference = "data_saver", chosenCopies = mapOf("w1" to "uhd", "w\"2" to "a")))
        val back = store.load()
        assertEquals(QualityPreference.DATA_SAVER, QualityPreference.of(back.playbackPreference))
        assertEquals(mapOf("w1" to "uhd", "w\"2" to "a"), back.chosenCopies)
    }
}
