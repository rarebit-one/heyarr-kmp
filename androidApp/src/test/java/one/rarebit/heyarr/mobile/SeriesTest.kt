package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.mobile.catalog.ContinueClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailJson
import one.rarebit.heyarr.mobile.preview.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A series' files → seasons → episodes, against the shapes a real scan leaves in the
 * catalog: one edition per season labelled by the node, the episode number only in the
 * filename, and sidecars (`-thumb.jpg`, `.srt`, `.nfo`) attached to their episode
 * rather than listed as rows. Shared with the desktop's SeriesTest.
 */
class SeriesTest {

    private fun file(id: String, name: String, label: String? = null, role: String? = "primary", mime: String? = "video/mp4", hash: String? = "blake3:$id", path: String? = null, missing: String? = null) =
        WorkAsset(id = id, editionId = "e-${label ?: "none"}", role = role, filename = name, mime = mime, blobHash = hash, sourcePath = path ?: "/media/shows/Yellowstone (2018)/${label ?: "."}/$name", missingSince = missing, sizeBytes = 1_500_000_000L, editionLabel = label)

    private val yellowstone = listOf(
        file("s5e2", "Yellowstone.2018.S05E02.The.Sting.of.Wisdom.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 05"),
        file("s4e2", "Yellowstone.2018.S04E02.Phantom.Pain.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 04"),
        file("s4e1", "Yellowstone.2018.S04E01.Half.the.Money.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 04"),
        file("s4e1-nfo", "Yellowstone.2018.S04E01.Half.the.Money.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.nfo", "Season 04", mime = null, hash = "blake3:nfo"),
        file("s4e1-thumb", "Yellowstone.2018.S04E01.Half.the.Money.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb-thumb.jpg", "Season 04", role = "artwork", mime = "image/jpeg"),
        file("s4e1-srt", "Yellowstone.2018.S04E01.Half.the.Money.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.en.srt", "Season 04", role = "subtitle", mime = "text/plain"),
        file("poster", "poster.jpg", "Season 04", role = "artwork", mime = "image/jpeg"),
        file("s5e1", "Yellowstone.2018.S05E01.One.Hundred.Years.Is.Nothing.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mp4", "Season 05", missing = "2026-09-01T00:00:00Z"),
        file("sp1", "Yellowstone.S00E01.Behind.the.Story.720p.mp4", "Specials"),
    )

    @Test fun groupsAShowIntoSeasonsThenEpisodesInOrderWithSidecarsAttached() {
        val seasons = Series.seasons(yellowstone)
        assertEquals(listOf("Season 4", "Season 5", "Specials"), seasons.map { it.label })
        val s4 = seasons[0]
        assertEquals("sidecars are not episodes", listOf("s4e1", "s4e2"), s4.episodes.map { it.asset.id })
        assertEquals("S04E01", s4.episodes[0].code)
        assertEquals("Half the Money", s4.episodes[0].title)
        assertEquals("the -thumb sidecar attaches to its episode", "s4e1-thumb", s4.episodes[0].thumbnail?.id)
        assertEquals("the .en.srt sidecar attaches too", listOf("s4e1-srt"), s4.episodes[0].subtitles.map { it.id })
        assertNull(s4.episodes[1].thumbnail)
        assertEquals("/api/v1/blobs/blake3:s4e1-thumb/content", s4.episodes[0].thumbnailPath)
        val s5 = seasons[1]
        assertFalse("a missing file is listed but cannot play", s5.episodes[0].isPlayable)
        assertEquals("Behind the Story", seasons[2].episodes.single().title)
    }

    @Test fun gapsAreTheNumbersNothingCovers() {
        val seasons = Series.seasons(listOf(file("a", "Show.S01E01.mkv", "Season 01"), file("b", "Show.S01E04.mkv", "Season 01")))
        assertEquals(listOf(2, 3), seasons[0].gaps)
        assertEquals(2, seasons[0].held)
    }

    @Test fun theHeaderPlayIsTheFirstPlayableEpisodeInSeasonOrder() {
        val seasons = Series.seasons(yellowstone)
        assertEquals("s4e1", Series.firstPlayable(seasons)?.asset?.id)
        assertEquals("Yellowstone — S04E01 Half the Money", Series.playTitle(Work(id = "w", title = "Yellowstone", kind = "series"), Series.firstPlayable(seasons)!!))
    }

    @Test fun readsTheOtherFilenameShapesTheNodeAccepts() {
        val dirSeason = Series.episode(file("a", "05 - Home.mkv", label = "Season 02"))!!
        assertEquals(2, dirSeason.season)
        assertEquals(5, dirSeason.number)
        assertEquals("Home", dirSeason.title)
        val nxnn = Series.episode(file("b", "The Expanse - 2x05 - Home.mkv", label = null, path = "/m/The Expanse (2015)/Season 02/The Expanse - 2x05 - Home.mkv"))!!
        assertEquals(2, nxnn.season)
        assertEquals(5, nxnn.number)
        val multi = Series.episode(file("d", "Show.s02e05e06.1080p.mkv", label = "Season 02"))!!
        assertEquals(5, multi.number)
        assertNull(multi.title)
        assertEquals("S02E05", multi.label)
        assertEquals("Pilot", Series.episodeTitle(".Pilot.[1080p].x265-GRP"))
        assertNull(Series.episodeTitle(".PROPER.1080p.WEB.H264-GROUP"))
        assertEquals(listOf("HDTV-1080P", "AC3", "X264"), Series.qualityTags(file("q", "Yellowstone (2018) - S04E02 - Phantom Pain [HDTV-1080p][AC3 5.1][x264].mp4")))
        assertTrue(Series.isSeries("tv_series"))
        assertTrue(Series.isSeries("series"))
        assertFalse(Series.isSeries("movie"))
    }

    @Test fun theLiveFixtureShapeParsesIntoTwoSeasonsWithThumbnails() {
        val assets = WorkDetailJson.parseAssets(Fixtures.assets(Fixtures.YELLOWSTONE))
        val seasons = Series.seasons(assets)
        assertEquals(listOf(4, 5), seasons.map { it.number })
        assertEquals(10, seasons[0].episodes.size)
        assertTrue(seasons[0].episodes.all { it.thumbnail != null })
        assertEquals(listOf(5, 6), seasons[1].gaps)
        assertEquals(1, seasons[0].episodes[0].subtitles.size)
    }

    @Test fun continueRailReadsSessionWorkAndAsset() {
        val rows = ContinueClient.parse(Fixtures.continueRail)
        assertEquals(1, rows.size)
        val e = rows[0]
        assertEquals(Fixtures.YELLOWSTONE, e.workId)
        assertEquals("Season 04", e.editionLabel)
        assertEquals("S04E03", e.subtitle)
        assertEquals("paused", e.state)
        assertEquals(1425.0 / 3312.5, e.fraction!!.toDouble(), 0.001)
        assertEquals("23:45 / 55:12", e.progressLabel)
        assertTrue(ContinueClient.parse("""{"items":[]}""").isEmpty())
    }
}
