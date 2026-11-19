package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.cast.CastConnection
import one.rarebit.heyarr.desktop.cast.CastDiscovery
import one.rarebit.heyarr.desktop.cast.CastFrame
import one.rarebit.heyarr.desktop.cast.CastManager
import one.rarebit.heyarr.desktop.cast.CastStatus
import one.rarebit.heyarr.desktop.cast.CastWire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class CastProtocolTest {
    @Test
    fun fragmentedLengthPrefixedFrameAndUnknownFields() {
        val frame = CastFrame("sender-7", "receiver-0", "urn:x-cast:test", """{"type":"PING"}""")
        val output = ByteArrayOutputStream()
        CastWire.write(output, frame)
        // Encoded independently with the CastMessage protobuf schema, not this codec.
        val golden = (
            "0000003c0800120873656e6465722d371a0a72656365697665722d30" +
                "220f75726e3a782d636173743a746573742800320f7b2274797065223a2250494e47227d"
            )
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(golden, output.toByteArray())
        val input = object : ByteArrayInputStream(golden) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertEquals(frame, CastWire.read(input))
        // A future sender may add an unknown varint field. Field 15 has value 1.
        val extended = golden.copyOf().apply { this[3] = 62 } + byteArrayOf(0x78, 0x01)
        assertEquals(frame, CastWire.read(ByteArrayInputStream(extended)))
        assertFails { CastWire.read(ByteArrayInputStream(golden.dropLast(1).toByteArray())) }
        assertFails { CastWire.read(ByteArrayInputStream(byteArrayOf(0, 1, 0, 1))) }
        // Field 7's length overflows a signed Long in the tenth varint byte.
        // The decoder must reject it before advancing the cursor backwards.
        val overflow = byteArrayOf(0x3a) + byteArrayOf(
            0xf5.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01,
        )
        assertFails { CastWire.decode(overflow) }
        assertFails {
            CastWire.decode(
                byteArrayOf(
                    0x3a, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                    0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x02,
                ),
            )
        }
    }

    @Test
    fun receiverAndMediaStatusParsingKeepsSessionIdentity() {
        val receiver = """{"type":"RECEIVER_STATUS","status":{"applications":[""" +
            """{"appId":"CC1AD845","transportId":"web-4"}]}}"""
        assertEquals("CC1AD845" to "web-4", CastConnection.application(receiver))
        val first = CastConnection.parseStatus(
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":91,"playerState":"PLAYING",""" +
                """"currentTime":5.5,"media":{"duration":120,"metadata":{"title":"Example"}}}]}""",
        )
        val update = CastConnection.parseStatus(
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":91,"playerState":"PAUSED","currentTime":6.0}]}""",
        )
        assertEquals("Example", CastConnection.mergeStatus(first, update)?.title)
        assertEquals(120.0, CastConnection.mergeStatus(first, update)?.duration)
        val ended = CastConnection.parseStatus("""{"type":"MEDIA_STATUS","status":[]}""")
        assertNull(CastConnection.mergeStatus(first, ended))
        assertFails { CastConnection.requireResponse("""{"type":"INVALID_PLAYER_STATE"}""", "MEDIA_STATUS") }
        assertFails { CastConnection.requireResponse("""{"type":"LOAD_CANCELLED"}""", "MEDIA_STATUS") }
        val frame = CastFrame("web-4", "sender-7", "urn:x-cast:com.google.cast.media", "{}")
        assertEquals(true, CastConnection.matchesReply(frame, frame.namespace, "web-4", "sender-7"))
        assertEquals(false, CastConnection.matchesReply(frame, frame.namespace, "receiver-0", "sender-7"))
        assertEquals(false, CastConnection.matchesReply(frame, "other", "web-4", "sender-7"))
    }

    @Test
    fun discoveryRecordUsesStableIdAndRejectsInvalidPort() {
        val address = InetAddress.getByName("192.0.2.7") as java.net.Inet4Address
        val found = CastDiscovery.record("stable-id", "Living room", "service", address, 8009)
        assertEquals("stable-id", found?.id)
        assertEquals("Living room", found?.name)
        assertNull(CastDiscovery.record("id", "name", "service", address, 0))
    }

    @Test
    fun restartSeekPreservesPauseIntentAndBoundsThePosition() {
        assertEquals(CastManager.Companion.RestartSeek(30.0, false), CastManager.restartSeek(30.0, 120.0, false))
        assertEquals(CastManager.Companion.RestartSeek(120.0, true), CastManager.restartSeek(140.0, 120.0, true))
        assertFails { CastManager.restartSeek(Double.NaN, 120.0, true) }
    }

    @Test
    fun unsolicitedReceiverFailureReplacesBufferingStatus() {
        val buffering = CastConnection.parseStatus(
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":7,"playerState":"BUFFERING","currentTime":0}]}""",
        )
        val failed = CastConnection.parseStatus(
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":7,"playerState":"IDLE","idleReason":"ERROR",""" +
                """"currentTime":0}]}""",
        )
        val merged = CastConnection.mergeStatus(buffering, failed)
        assertEquals("IDLE", merged?.state)
        assertEquals("ERROR", merged?.error)
    }

    @Test
    fun receiverSessionIsReadForAReceiverLevelStop() {
        val receiver = """{"type":"RECEIVER_STATUS","status":{"applications":[""" +
            """{"appId":"CC1AD845","transportId":"web-4","sessionId":"abc-1"}]}}"""
        assertEquals("abc-1", CastConnection.sessionOf(receiver))
        assertNull(CastConnection.sessionOf("""{"type":"RECEIVER_STATUS","status":{"applications":[]}}"""))
    }

    @Test
    fun aForwardSeekWhilePausedIsFollowedOnlyOnceTheReceiverWaitsOnIt() {
        val second = TimeUnit.SECONDS.toNanos(1)
        fun report(state: String, at: Double) = CastStatus(state, at, null, null, 3)
        var judged = CastManager.judge(null, report("PAUSED", 10.0), 0, wantsPlay = false)
        judged = CastManager.judge(judged.progress, report("PAUSED", 900.0), 60 * second, wantsPlay = false)
        assertEquals(true, judged.progress.jumped)
        assertEquals(CastManager.Companion.Verdict.STEADY, judged.verdict, "Paused time never counts")
        judged = CastManager.judge(judged.progress, report("BUFFERING", 900.0), 62 * second, wantsPlay = true)
        assertEquals(CastManager.Companion.Verdict.STEADY, judged.verdict)
        judged = CastManager.judge(judged.progress, report("BUFFERING", 900.0), 66 * second, wantsPlay = true)
        assertEquals(CastManager.Companion.Verdict.UNREACHED_SEEK, judged.verdict)
        // Had it played on from there, the jump was served from its buffer: nothing to do.
        val played = CastManager.judge(judged.progress, report("PLAYING", 902.0), 67 * second, wantsPlay = true)
        assertEquals(CastManager.Companion.Verdict.STEADY, played.verdict)
        assertEquals(true, played.played)
        assertEquals(false, played.progress.jumped)
    }
}
