package one.rarebit.heyarr.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.AudioPlayer
import one.rarebit.heyarr.mobile.playback.AudioState

/**
 * A transport that answers by the first route whose key is a SUBSTRING of the URL
 * (method-prefixed), so a query-string URL matches without spelling the whole thing.
 */
internal class SubstringTransport(private val routes: List<Pair<String, HttpResponse>>) : HttpTransport {
    val calls = ArrayList<String>()
    private fun answer(method: String, url: String, body: String?): HttpResponse {
        calls.add("$method $url")
        val path = url.substringAfter("/api/v1")
        return routes.firstOrNull { (k, _) -> "$method $path".contains(k) }?.second ?: HttpResponse(404, """{"detail":"no such route in the fake"}""")
    }
    override fun get(url: String, headers: Map<String, String>) = answer("GET", url, null)
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = answer("POST", url, body)
    override fun delete(url: String, headers: Map<String, String>) = answer("DELETE", url, null)
    override fun patch(url: String, body: String?, contentType: String?, headers: Map<String, String>) = answer("PATCH", url, body)
}

/** An AudioPlayer whose state a test drives by hand. */
internal class FakeAudioPlayer : AudioPlayer {
    val flow = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = flow
    val commands = ArrayList<String>()
    override fun playQueue(items: List<AudioItem>, startIndex: Int) {
        commands.add("queue:${items.size}@$startIndex")
        flow.value = AudioState(queue = items, index = startIndex, playing = true)
    }
    override fun play() {
        commands.add("play")
    }
    override fun pause() {
        commands.add("pause")
    }
    override fun togglePlayPause() {
        commands.add("toggle")
    }
    override fun next() {
        commands.add("next")
    }
    override fun previous() {
        commands.add("previous")
    }
    override fun seekTo(positionMs: Long) {
        commands.add("seek:$positionMs")
    }
    override fun skipTo(index: Int) {
        commands.add("skip:$index")
    }
    override fun stop() {
        commands.add("stop")
        flow.value = AudioState()
    }
}

internal fun worksPage(vararg items: String, next: String? = null): String =
    """{"items":[${items.joinToString(",")}]${next?.let { ""","next_cursor":"$it"""" } ?: ""}}"""

internal fun workJson(id: String, title: String, ct: String, created: String = "2026-08-01T00:00:00Z", extra: String = ""): String =
    """{"id":"$id","title":"$title","content_type":"$ct","created_at":"$created"$extra}"""
