package one.rarebit.heyarr.core.playback

/**
 * The node's seekable HLS presentation of a `stream` plan (ADR-0126, heyarr-core#908), as a
 * client proxies or relays it. The plan's `hls_url` names a VOD playlist,
 * `/render/hls/{capability}/index.m3u8`, whose entries are relative to it: `init.mp4` and
 * `0.m4s`, `1.m4s`, … So a relay cannot register one URL as it does for the live stream: it
 * forwards the whole capability prefix, and these are the only names under it that it lets
 * through. Nothing under the prefix takes a query (there is no `?start=`; a player seeks by
 * fetching another segment), and no credential: the capability in the path is the authority.
 */
object HlsPresentation {
    /** The playlist's name under its capability, and what a relay's own URL ends in. */
    const val PLAYLIST = "index.m3u8"

    /**
     * The LOAD content type the Default Media Receiver was seen to play fMP4 HLS with, over
     * plain HTTP from a LAN host, with the whole film's duration and native seeks. The plan's
     * `hls_mime` (`application/vnd.apple.mpegurl`) names the same format; a Cast sender sends
     * the spelling proven on hardware.
     */
    const val CAST_CONTENT_TYPE = "application/x-mpegURL"

    /** `hlsSegmentFormat` and `hlsVideoSegmentFormat` for those segments: fragmented MP4, not TS. */
    const val CAST_SEGMENT_FORMAT = "fmp4"

    private val ENTRY = Regex("index\\.m3u8|init\\.mp4|(?:0|[1-9][0-9]*)\\.m4s")
    private val PLAYLIST_PATH = Regex("/render/hls/[^/?#]+/index\\.m3u8")

    /** Whether [name], one path segment under the capability, is the playlist, the init or a segment. */
    fun isEntry(name: String): Boolean = ENTRY.matches(name)

    /** Whether [path] (a URL's raw path) is a presentation's playlist. */
    fun isPlaylistPath(path: String): Boolean = PLAYLIST_PATH.matches(path)

    /** The capability prefix a playlist URL's entries resolve against, with its trailing slash. */
    fun prefixOf(playlistUrl: String): String {
        require(playlistUrl.endsWith("/$PLAYLIST")) { "Not an HLS playlist URL" }
        return playlistUrl.removeSuffix(PLAYLIST)
    }
}
