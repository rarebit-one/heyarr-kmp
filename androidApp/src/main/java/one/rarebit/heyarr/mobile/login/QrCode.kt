package one.rarebit.heyarr.mobile.login

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders the `void-which-binds:login?rp=&id=` tuple as a scannable QR code. The ZXing
 * [BitMatrix] half is pure JVM (unit-tested by round-trip decoding); only [bitmap]
 * touches Android's [Bitmap].
 */
object QrCode {
    /** Encode [text] into a QR module matrix with a quiet zone. */
    fun matrix(text: String, sizePx: Int = 0): BitMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** Draw [text] as a black-on-white [sizePx]×[sizePx] bitmap (no anti-aliasing). */
    fun bitmap(text: String, sizePx: Int): Bitmap {
        val m = matrix(text, sizePx)
        val w = m.width
        val h = m.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
