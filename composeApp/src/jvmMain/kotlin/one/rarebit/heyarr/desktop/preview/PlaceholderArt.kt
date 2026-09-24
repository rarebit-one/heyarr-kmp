package one.rarebit.heyarr.desktop.preview

import org.jetbrains.skia.Color4f
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface

/** Procedural "artwork" for previews: a two-tone diagonal composition seeded by the path, so cards differ. */
object PlaceholderArt {
    fun bytes(path: String): ByteArray {
        val seed = path.hashCode()
        val hue = ((seed ushr 3) % 360 + 360) % 360
        val surface = Surface.makeRasterN32Premul(400, 600)
        val c = surface.canvas
        c.clear(hsl(hue, 0.45f, 0.42f).toColor())
        val p = Paint().apply { color4f = hsl((hue + 30) % 360, 0.55f, 0.62f) }
        c.drawCircle(120f + (seed % 90), 220f + (seed % 140), 190f, p)
        p.color4f = hsl((hue + 200) % 360, 0.5f, 0.5f)
        c.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, 380f + (seed % 60), 400f, 300f), p)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun hsl(h: Int, s: Float, l: Float): Color4f {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color4f(r + m, g + m, b + m, 1f)
    }
}
