package one.rarebit.heyarr.desktop.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal data class CastFrame(val source: String, val destination: String, val namespace: String, val payload: String)

/** Cast v2's length-prefixed CastMessage; only the UTF-8 payload form is used. */
@Suppress("MagicNumber") // Protobuf field tags, wire types, and varint masks are protocol constants.
internal object CastWire {
    private const val MAX_FRAME = 65_536

    fun write(output: OutputStream, frame: CastFrame) {
        val bytes = ByteArrayOutputStream().apply {
            field(1, 0)
            field(2, frame.source)
            field(3, frame.destination)
            field(4, frame.namespace)
            field(5, 0)
            field(6, frame.payload)
        }.toByteArray()
        require(bytes.size <= MAX_FRAME) { "Cast message is too large" }
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun read(input: InputStream): CastFrame {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        require(size in 1..MAX_FRAME) { "Invalid Cast frame length" }
        return decode(stream.readNBytes(size).also { require(it.size == size) { "Truncated Cast frame" } })
    }

    internal fun decode(bytes: ByteArray): CastFrame {
        var pos = 0
        val fields = mutableMapOf<Int, String>()
        while (pos < bytes.size) {
            val rawTag = varint(bytes, pos).also { pos = it.second }.first
            require(rawTag in 1..Int.MAX_VALUE.toLong()) { "Invalid Cast field tag" }
            val tag = rawTag.toInt()
            val number = tag ushr 3
            when (tag and 7) {
                0 -> pos = varint(bytes, pos).second

                2 -> {
                    val (length, next) = varint(bytes, pos)
                    pos = next
                    require(length >= 0 && length <= bytes.size - pos && length <= MAX_FRAME) {
                        "Invalid Cast field length"
                    }
                    if (number in 2..4 || number == 6) {
                        fields[number] = String(bytes, pos, length.toInt(), StandardCharsets.UTF_8)
                    }
                    pos += length.toInt()
                }

                else -> error("Unsupported Cast wire field")
            }
        }
        return CastFrame(
            fields[2] ?: error("Cast source missing"),
            fields[3] ?: error("Cast destination missing"),
            fields[4] ?: error("Cast namespace missing"),
            fields[6] ?: error("Cast payload missing"),
        )
    }

    private fun ByteArrayOutputStream.field(number: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        variable((number shl 3 or 2).toLong())
        variable(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.field(number: Int, value: Int) {
        variable((number shl 3).toLong())
        variable(value.toLong())
    }

    private fun ByteArrayOutputStream.variable(value: Long) {
        var remaining = value
        while (remaining >= 128) {
            write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        write(remaining.toInt())
    }

    private fun varint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var pos = start
        for (shift in 0..63 step 7) {
            require(pos < bytes.size) { "Truncated Cast varint" }
            val byte = bytes[pos++].toInt() and 255
            require(shift != 63 || byte and 127 <= 1) { "Invalid Cast varint" }
            value = value or ((byte and 127).toLong() shl shift)
            if (byte and 128 == 0) return value to pos
        }
        error("Invalid Cast varint")
    }
}
