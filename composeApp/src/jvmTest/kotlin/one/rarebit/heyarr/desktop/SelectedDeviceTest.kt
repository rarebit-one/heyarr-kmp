package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.ui.screens.SelectedDevice
import one.rarebit.heyarr.desktop.ui.screens.castReceiver
import one.rarebit.heyarr.desktop.ui.screens.dlnaRenderer
import one.rarebit.heyarr.desktop.ui.screens.following
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Now Playing's selection used to be two nullable fields (a DLNA renderer and a Cast receiver)
 * kept mutually exclusive across several effects. These pin the rules that juggling encoded,
 * now that it is one [SelectedDevice].
 */
class SelectedDeviceTest {
    private val address = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 9)) as Inet4Address
    private val lounge = CastDevice("lounge", "Lounge", address, 8009)
    private val den = CastDevice("den", "Den", address, 8010)
    private val speaker = Renderer("uuid:speaker", "Speaker", null, null)
    private val tv = Renderer("uuid:tv", "TV", null, null)

    @Test
    fun theFirstPickPrefersTheReceiverBeingCastToThenARendererThenAnyReceiver() {
        assertEquals(SelectedDevice.Cast(den), SelectedDevice.initial(den, listOf(speaker), listOf(lounge, den)))
        assertEquals(SelectedDevice.Dlna(speaker), SelectedDevice.initial(null, listOf(speaker, tv), listOf(lounge)))
        assertEquals(SelectedDevice.Cast(lounge), SelectedDevice.initial(null, emptyList(), listOf(lounge, den)))
        assertNull(SelectedDevice.initial(null, emptyList(), emptyList()))
    }

    @Test
    fun castingFromAnywhereBringsThatReceiverForward() {
        assertEquals(SelectedDevice.Cast(lounge), null.following(lounge))
        assertEquals(SelectedDevice.Cast(lounge), SelectedDevice.Dlna(speaker).following(lounge))
        assertEquals(SelectedDevice.Cast(lounge), SelectedDevice.Cast(den).following(lounge))
    }

    @Test
    fun releasingTheReceiverDropsACastSelectionButKeepsAPickedRenderer() {
        assertNull(SelectedDevice.Cast(lounge).following(null))
        assertEquals(SelectedDevice.Dlna(speaker), SelectedDevice.Dlna(speaker).following(null))
        assertNull(null.following(null))
    }

    @Test
    fun eachPollingPathSeesOnlyItsOwnHalf() {
        val renderer: SelectedDevice? = SelectedDevice.Dlna(tv)
        val receiver: SelectedDevice? = SelectedDevice.Cast(den)
        val nothing: SelectedDevice? = null
        assertEquals(tv, renderer.dlnaRenderer)
        assertNull(renderer.castReceiver)
        assertEquals(den, receiver.castReceiver)
        assertNull(receiver.dlnaRenderer)
        assertNull(nothing.dlnaRenderer)
        assertNull(nothing.castReceiver)
    }
}
