package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.playback.InMemoryPlayerSnapshotStore
import one.rarebit.heyarr.core.playback.InMemoryResumeStore
import one.rarebit.heyarr.core.playback.PlayerRestore
import one.rarebit.heyarr.core.playback.ResumeItem
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.core.playback.ResumePoints
import one.rarebit.heyarr.core.playback.ResumePolicy
import one.rarebit.heyarr.core.playback.ResumeRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumePointsTest {
    private var now = 1_000_000L
    private val store = InMemoryResumeStore()
    private val recorder = ResumeRecorder(store) { now }
    private val film = ResumeItem("as-uhd", workId = "w-film", title = "Film", kind = "movie", blobHash = "b1")
    private val filmHd = film.copy(assetId = "as-hd", blobHash = "b2")
    private val e1 = ResumeItem("as-e1", workId = "w-show", title = "Show — S01E01", kind = "series")
    private val e2 = ResumeItem("as-e2", workId = "w-show", title = "Show — S01E02", kind = "series")

    @Test fun aPositionRoundTripsThroughItsJson() {
        val p =
            ResumePoint(film.copy(mime = "video/x-matroska", artworkUrl = "https://n/a?x=\"1\""), 1439.5, 6960.0, 42)
        assertEquals(p, ResumePoint.fromJson(p.toJson()))
        val bare = ResumePoint(ResumeItem("as-1", title = "t"), 60.0, null, 7)
        assertEquals(bare, ResumePoint.fromJson(bare.toJson()))
        assertNull(ResumePoint.fromJson("""{"asset_id":"as-1"}"""), "no position, not a point")
    }

    @Test fun theOpeningAndTheCreditsAreNotResumable() {
        assertTrue(!ResumePolicy.resumable(29.0, 6000.0), "a peek starts over")
        assertTrue(ResumePolicy.resumable(30.0, 6000.0))
        assertTrue(ResumePolicy.resumable(5399.0, 6000.0))
        assertTrue(!ResumePolicy.resumable(5400.0, 6000.0), "into the credits: watched")
        assertTrue(ResumePolicy.resumable(100_000.0, null), "an unknown runtime never finishes by position")
    }

    @Test fun ticksAreWrittenEveryTenSecondsAndAStopAtOnce() {
        recorder.progress(film, 100.0, 6000.0)
        assertEquals(100.0, store.get("as-uhd")?.positionSeconds)
        now += 5_000
        recorder.progress(film, 105.0, 6000.0)
        assertEquals(100.0, store.get("as-uhd")?.positionSeconds, "throttled")
        now += 5_000
        recorder.progress(film, 110.0, 6000.0)
        assertEquals(110.0, store.get("as-uhd")?.positionSeconds)
        now += 1_000
        recorder.checkpoint(film, 111.0, 6000.0)
        assertEquals(111.0, store.get("as-uhd")?.positionSeconds, "a pause, stop or background is written at once")
        assertEquals(now, store.get("as-uhd")?.updatedAtMs)
    }

    @Test fun startingOverAndFinishingDropThePoint() {
        recorder.checkpoint(film, 1200.0, 6000.0)
        recorder.checkpoint(film, 12.0, 6000.0)
        assertNull(store.get("as-uhd"), "Start over, stopped early: no resume next time")
        recorder.checkpoint(film, 1200.0, 6000.0)
        recorder.checkpoint(film, 5500.0, 6000.0)
        assertNull(store.get("as-uhd"), "stopped in the credits")
        recorder.checkpoint(film, 1200.0, 6000.0)
        recorder.ended(film)
        assertNull(store.get("as-uhd"))
    }

    @Test fun aFilmResumesAcrossItsCopiesButAnEpisodeOnlyFromItsOwn() {
        recorder.checkpoint(film, 1439.0, 6000.0)
        assertEquals(1439.0, ResumePoints.startFor(store, "as-uhd"))
        assertEquals(1439.0, ResumePoints.startFor(store, "as-hd", listOf("as-uhd", "as-hd")), "same film, other copy")
        now += 1
        recorder.checkpoint(filmHd, 2000.0, 6000.0)
        assertEquals(2000.0, ResumePoints.startFor(store, "as-uhd", listOf("as-uhd", "as-hd")), "the newest wins")
        recorder.checkpoint(e1, 900.0, 2700.0)
        assertNull(ResumePoints.startFor(store, "as-e2"), "another episode starts at the beginning")
        assertNull(ResumePoints.startFor(store, "as-none"))
    }

    @Test fun continueWatchingShowsTheNewestPointPerWorkNewestFirst() {
        recorder.checkpoint(e1, 900.0, 2700.0)
        now += 1
        recorder.checkpoint(film, 1439.0, 6000.0)
        now += 1
        recorder.checkpoint(e2, 300.0, 2700.0)
        val row = ResumePoints.continueWatching(store.all())
        assertEquals(listOf("as-e2", "as-uhd"), row.map { it.assetId }, "the show's later episode, then the film")
        assertEquals("as-e2", ResumePoints.forWork(store.all(), "w-show")?.assetId)
        assertEquals(0.5f, store.get("as-uhd")!!.copy(positionSeconds = 3000.0).fraction)
    }

    @Test fun onlyTheNewestPointsAreKept() {
        for (i in 0 until ResumePolicy.KEEP + 5) {
            now += 1
            recorder.checkpoint(ResumeItem("as-$i", title = "$i"), 60.0, null)
        }
        assertEquals(ResumePolicy.KEEP, store.all().size)
        assertNull(store.get("as-0"), "the oldest went first")
        assertEquals(60.0, store.get("as-${ResumePolicy.KEEP + 4}")?.positionSeconds)
    }

    @Test fun theSnapshotFollowsEveryWriteAtAnyPositionAndStopClearsIt() {
        val snapshots = InMemoryPlayerSnapshotStore()
        val r = ResumeRecorder(store, snapshots) { now }
        r.checkpoint(film, 12.0, 6000.0)
        assertNull(store.get("as-uhd"), "too early for a resume point")
        assertEquals(12.0, snapshots.read()?.positionSeconds, "but the player is restored where it was")
        now += 20_000
        r.progress(e1, 300.0, 2700.0)
        assertEquals("as-e1", snapshots.read()?.assetId, "the item in the player now")
        r.stopped()
        assertNull(snapshots.read(), "Stop: nothing to restore")
        r.checkpoint(film, 1200.0, 6000.0)
        r.ended(film)
        assertNull(snapshots.read(), "the end: nothing to restore")
    }

    @Test fun aSnapshotIsRestoredForAWeekWhenItCanBePlayed() {
        val p = ResumePoint(film, 100.0, 6000.0, now)
        assertTrue(PlayerRestore.restorable(p, now + 60_000))
        assertTrue(PlayerRestore.restorable(p, now + PlayerRestore.MAX_AGE_MS))
        assertTrue(
            !PlayerRestore.restorable(p, now + PlayerRestore.MAX_AGE_MS + 1),
            "a week on: Continue watching only",
        )
        assertTrue(!PlayerRestore.restorable(p.copy(item = film.copy(blobHash = null)), now), "nothing to play")
    }
}
