package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.music.MusicClient
import one.rarebit.heyarr.mobile.music.MusicJson
import one.rarebit.heyarr.mobile.music.Tracks
import one.rarebit.heyarr.mobile.music.trackTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicJsonTest {

    @Test fun parsesArtists() {
        val body = """{"items":[{"name":"Artist A","work_count":2,"artwork":{"asset_id":"a10","blob_hash":"blake3:99","content_url":"/api/v1/blobs/blake3:99/content"}},
                                {"name":"Artist B","work_count":1,"artwork":null},{"work_count":3}],"next_cursor":"c"}"""
        val artists = MusicJson.parseArtists(body)
        assertEquals(2, artists.size)
        assertEquals("/api/v1/blobs/blake3:99/content", artists[0].artworkPath)
        assertEquals(1, artists[1].workCount)
        assertNull(artists[1].artworkPath)
    }

    @Test fun groupsAlbumsByArtistLikeTheServerWould() {
        val works = listOf(
            Work(id = "w5", title = "Album Two", kind = "music", year = 2004, artist = "Artist A", artworkPath = "/p2"),
            Work(id = "w4", title = "Album One", kind = "music", year = 2001, artist = "Artist A"),
            Work(id = "w6", title = "Album Three", kind = "music", year = 2010, artist = "Artist B"),
            Work(id = "w9", title = "Untagged", kind = "music"),
            Work(id = "w7", title = "Lower", kind = "music", artist = "artist c"),
        )
        val artists = MusicJson.groupByArtist(works)
        assertEquals(listOf("Artist A", "Artist B", "artist c", MusicJson.UNKNOWN), artists.map { it.name })
        assertEquals(2, artists[0].workCount)
        // The first album by year has no cover, so the first WITH one stands in.
        assertEquals("/p2", artists[0].artworkPath)
        assertEquals("w5", artists[0].artworkWorkId)
        assertNull(artists[1].artworkPath)
    }

    @Test fun artistsUrl() {
        assertEquals("https://h/api/v1/artists?limit=200", MusicClient.artistsUrl("https://h/", null))
        assertEquals("https://h/api/v1/artists?limit=200&cursor=a%2Fb", MusicClient.artistsUrl("https://h", "a/b"))
    }

    @Test fun tracksAreThePlayableAudioFilesInFilenameOrder() {
        val assets = listOf(
            WorkAsset(id = "a2", editionId = "e", filename = "02 - Two.flac", mime = "audio/flac", blobHash = "blake3:2"),
            WorkAsset(id = "a1", editionId = "e", filename = "01 - One.flac", mime = "audio/flac", blobHash = "blake3:1"),
            WorkAsset(id = "a3", editionId = "e", filename = "cover.jpg", mime = "image/jpeg", blobHash = "blake3:3", role = "artwork"),
            WorkAsset(id = "a4", editionId = "e", filename = "03 - Gone.flac", mime = "audio/flac", blobHash = "blake3:4", missingSince = "2026-01-01T00:00:00Z"),
            WorkAsset(id = "a5", editionId = "e", filename = "linked.mp3", mime = "audio/mpeg", blobHash = null),
        )
        assertEquals(listOf("a1", "a2"), Tracks.playable(assets).map { it.id })
    }

    @Test fun trackTitleDropsTheNumberAndExtension() {
        assertEquals("One", trackTitle(WorkAsset(id = "x", editionId = "e", filename = "01 - One.flac")))
        assertEquals("Two", trackTitle(WorkAsset(id = "x", editionId = "e", filename = "2. Two.mp3")))
        assertEquals("Plain", trackTitle(WorkAsset(id = "x", editionId = "e", filename = "Plain.ogg")))
        assertEquals("x", trackTitle(WorkAsset(id = "x", editionId = "e", filename = null)))
    }
}
