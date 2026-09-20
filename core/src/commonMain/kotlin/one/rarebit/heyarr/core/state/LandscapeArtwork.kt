package one.rarebit.heyarr.core.state

import one.rarebit.heyarr.core.net.JsonScan
import kotlin.math.abs

/** Select real backgrounds, never banners or portrait posters stretched into a wide frame. */
object LandscapeArtwork {
    fun tvmaze(body: String): String? = JsonScan.objectsOf(body, emptyList()).mapNotNull { image ->
        if (JsonScan.stringField(image, "type") != "background") return@mapNotNull null
        val resolutions = JsonScan.objectAt(image, "resolutions") ?: return@mapNotNull null
        val original = JsonScan.objectAt(resolutions, "original") ?: return@mapNotNull null
        val width = JsonScan.intField(original, "width") ?: return@mapNotNull null
        val height = JsonScan.intField(original, "height") ?: return@mapNotNull null
        val url = JsonScan.stringField(original, "url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
        if (width < 640 || height <= 0) return@mapNotNull null
        val ratio = width.toDouble() / height
        if (ratio !in 1.5..2.0) return@mapNotNull null
        Triple(url, abs(ratio - 16.0 / 9.0), width.toLong() * height)
    }.sortedWith(compareBy<Triple<String, Double, Long>> { it.second }.thenByDescending { it.third }).firstOrNull()?.first
}
