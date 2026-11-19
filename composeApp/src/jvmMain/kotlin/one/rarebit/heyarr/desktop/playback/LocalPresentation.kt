package one.rarebit.heyarr.desktop.playback

import one.rarebit.heyarr.desktop.heyarr.PlaybackTarget
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What mpv is handed for a plan: the node's HLS presentation when it has one (ADR-0126,
 * heyarr-core#908), else the live stream or the blob, each through the loopback proxy.
 *
 * mpv plays the presentation as any VOD playlist: the whole film's duration, and a seek
 * anywhere is a native one (the target has no [PlaybackTarget.streamBaseUrl], so the player's
 * restart-seek machinery stays out of it). The audio track is still the node's choice, which
 * [PlaybackTarget.nodeMixesAudio] keeps saying.
 *
 * The plan offers the presentation whenever the node can segment, but a source it cannot cut
 * answers the playlist with 404, and mpv would then fail where the live stream plays. So the
 * playlist is asked for once first, and anything but a playlist falls back to the stream.
 * That first ask is also what the node waits on for a copied picture's keyframe pass (started
 * at planning time), so it costs mpv nothing it would not have waited for itself.
 */
internal object LocalPresentation {
    /** As long as a cold keyframe pass over a large remux may take; past it, the stream plays. */
    private const val PROBE_SECONDS = 60L
    private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(PROBE_SECONDS)
    private const val HTTP_OK = 200
    private const val PLAYLIST_TAG = "#EXTM3U"

    fun choose(target: PlaybackTarget, local: (String) -> String, probe: (String) -> Boolean): PlaybackTarget {
        val stream = target.copy(
            url = local(target.url),
            streamBaseUrl = target.streamBaseUrl?.let(local),
            hlsUrl = null,
            hlsMime = null,
        )
        val playlist = target.hlsUrl?.let(local) ?: return stream
        return if (probe(playlist)) {
            target.copy(url = playlist, streamBaseUrl = null, mime = target.hlsMime, hlsUrl = playlist)
        } else {
            stream
        }
    }

    /** Whether [url] answers with an HLS playlist. Blocking; call off the UI thread. */
    fun probe(url: String): Boolean = try {
        HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build().use { client ->
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(PROBE_TIMEOUT).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == HTTP_OK && response.body().trimStart().startsWith(PLAYLIST_TAG)
        }
    } catch (_: IOException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
