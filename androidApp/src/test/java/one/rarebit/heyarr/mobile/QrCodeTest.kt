package one.rarebit.heyarr.mobile

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import one.rarebit.heyarr.mobile.login.LoginTuple
import one.rarebit.heyarr.mobile.login.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The QR module matrix round-trips the login tuple byte-for-byte (pure JVM half). */
class QrCodeTest {

    @Test fun loginTupleRoundTripsThroughTheQrMatrix() {
        val tuple = LoginTuple.encode(rp = "http://192.0.2.10:7777", id = "9f1c2a3b")
        val m = QrCode.matrix(tuple, sizePx = 240)
        assertTrue(m.width >= 240 && m.height >= 240)

        // Decode the matrix as if it were a scanned image.
        val pixels = IntArray(m.width * m.height) { i ->
            if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(m.width, m.height, pixels)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))

        assertEquals(tuple, result.text)
        val decoded = LoginTuple.decode(result.text)
        assertEquals("http://192.0.2.10:7777", decoded.rp)
        assertEquals("9f1c2a3b", decoded.id)
    }
}
