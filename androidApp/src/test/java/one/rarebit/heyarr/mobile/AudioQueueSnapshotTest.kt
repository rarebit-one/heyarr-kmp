package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.playback.PlayerRestore
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.AudioQueueSnapshot
import one.rarebit.heyarr.mobile.playback.InMemoryAudioQueueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music queue kept for the restore after process death (heyarr-kmp#232). */
class AudioQueueSnapshotTest {
    private val album = listOf(
        AudioItem(
            "t1",
            "alb",
            "One",
            "Artist",
            "Album",
            "https://n/art?x=\"1\"",
            "https://n/api/v1/blob/b1",
            "audio/flac",
        ),
        AudioItem("t2", "alb", "Two — the \"long\" one", null, null, null, "https://n/api/v1/blob/b2", null),
    )

    @Test fun aQueueRoundTripsThroughItsJson() {
        val s = AudioQueueSnapshot(album, index = 1, positionMs = 754_000, updatedAtMs = 42)
        assertEquals(s, AudioQueueSnapshot.fromJson(s.toJson()))
    }

    @Test fun aBrokenOrEmptySnapshotIsNotRestored() {
        assertNull(AudioQueueSnapshot.fromJson("""{"index":0,"updated_at_ms":1,"items":[]}"""))
        assertNull(
            "the track it was on is not in the queue",
            AudioQueueSnapshot.fromJson(
                AudioQueueSnapshot(album, 0, 0, 1).toJson().replace("\"index\":0", "\"index\":5"),
            ),
        )
        assertNull(AudioQueueSnapshot.fromJson("not json"))
    }

    @Test fun theStoreKeepsOneQueueUntilStopClearsIt() {
        val store = InMemoryAudioQueueStore()
        store.write(AudioQueueSnapshot(album, 0, 10_000, 1))
        store.write(AudioQueueSnapshot(album, 1, 3_000, 2))
        assertEquals(1, store.read()?.index)
        store.clear()
        assertNull(store.read())
    }

    @Test fun aQueueIsPutBackForAWeek() {
        assertTrue(PlayerRestore.fresh(updatedAtMs = 0, nowMs = PlayerRestore.MAX_AGE_MS))
        assertFalse(PlayerRestore.fresh(updatedAtMs = 0, nowMs = PlayerRestore.MAX_AGE_MS + 1))
        assertFalse("a clock that went backwards", PlayerRestore.fresh(updatedAtMs = 10, nowMs = 0))
    }
}
