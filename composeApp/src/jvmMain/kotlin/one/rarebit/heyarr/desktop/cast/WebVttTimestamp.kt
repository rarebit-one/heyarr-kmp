package one.rarebit.heyarr.desktop.cast

import java.util.Locale
import kotlin.math.roundToLong

/** A restarted progressive stream plays film time [offsetSeconds] at its own time zero. */
internal object WebVttTimestamp {
    private const val MILLIS = 1000
    private const val SECONDS = 60
    private val time = Regex("""(?:(?<hours>\d+):)?(?<minutes>\d{2}):(?<seconds>\d{2})[.,](?<ms>\d{3})""")
    private val cue = Regex("""^(?<from>\S+)\s+-->\s+(?<to>\S+)(?<settings>.*)$""")
    private val gap = Regex("""\n[ \t]*\n""")

    fun convert(text: String, offsetSeconds: Double): String {
        val offset = (offsetSeconds * MILLIS).roundToLong()
        val input = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (offset == 0L && input.startsWith("WEBVTT")) return input
        return buildString {
            append("WEBVTT\n\n")
            for (block in input.trim().split(gap)) {
                shifted(block, offset)?.let { append(it).append("\n\n") }
            }
        }
    }

    @Suppress("ReturnCount") // A malformed cue or one before the cut contributes no text.
    private fun shifted(block: String, offset: Long): String? {
        if (block.startsWith("STYLE\n") || block.startsWith("REGION\n")) return block
        val lines = block.lines()
        val index = lines.indexOfFirst { cue.matches(it) }
        val match = lines.getOrNull(index)?.let(cue::matchEntire) ?: return null
        val from = millis(match.groups["from"]!!.value) ?: return null
        val to = millis(match.groups["to"]!!.value) ?: return null
        if (to <= offset || to <= from) return null
        val times =
            stamp((from - offset).coerceAtLeast(0)) + " --> " + stamp(to - offset) + match.groups["settings"]!!.value
        return (listOf(times) + lines.drop(index + 1)).joinToString("\n")
    }

    private fun millis(value: String): Long? = time.matchEntire(value)?.groups?.let {
        val hours = it["hours"]?.value?.toLongOrNull() ?: 0
        val minutes = it["minutes"]!!.value.toLong()
        val seconds = it["seconds"]!!.value.toLong()
        ((hours * SECONDS + minutes) * SECONDS + seconds) * MILLIS + it["ms"]!!.value.toLong()
    }

    private fun stamp(value: Long): String {
        val seconds = value / MILLIS
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d.%03d",
            seconds / SECONDS / SECONDS,
            seconds / SECONDS % SECONDS,
            seconds % SECONDS,
            value % MILLIS,
        )
    }
}
