package one.rarebit.heyarr.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Archive line icons: a 24-unit grid, 1.5-unit strokes, square caps and mitred joins, so
 * they read as drawn instruments rather than the filled Material set. Transport controls (play,
 * pause, skip, stop) are solid shapes, because they have to read instantly at small sizes.
 *
 * Each icon is SVG path data, so the set can be reviewed outside the app. They are original
 * drawings; tint them with `Icon(tint = …)` like any [ImageVector].
 */
@Suppress("TooManyFunctions") // One property per icon is the point of the set.
object HeyarrIcons {
    // ── navigation and media kinds ───────────────────────────────────────────────────────
    val Watch by lazy { line("watch", "M3 5h18v14H3z", "M10 9v6l5-3z") }
    val Listen by lazy { line("listen", "M4 14v-2a8 8 0 0 1 16 0v2", "M4 14h3v6H4z", "M17 14h3v6h-3z") }
    val Read by lazy {
        line(
            "read",
            "M12 7v13",
            "M12 7c-2-1.5-5.5-1.5-9-.5v13c3.5-1 7-1 9 .5",
            "M12 7c2-1.5 5.5-1.5 9-.5v13c-3.5-1-7-1-9 .5",
        )
    }
    val Search by lazy { line("search", "M10.5 4.5a6 6 0 1 0 0 12a6 6 0 1 0 0-12z", "M15 15l5 5") }
    val Library by lazy { line("library", "M3 4h18v4H3z", "M5 8v12h14V8", "M10 12h4") }
    val Missing by lazy { line("missing", "M12 4l9 16H3z", "M12 10v4", "M12 16.5v1") }
    val Sources by lazy {
        line(
            "sources",
            "M12 3.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z",
            "M6 15.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z",
            "M18 15.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z",
            "M10.8 8.2L7.2 15.8",
            "M13.2 8.2l3.6 7.6",
        )
    }
    val Settings by lazy { line("settings", "M4 6h16", "M4 12h16", "M4 18h16", "M8 4.5v3", "M15 10.5v3", "M10 16.5v3") }
    val Home by lazy { line("home", "M4 11l8-7l8 7v9H4z", "M10 20v-5h4v5") }
    val Cast by lazy {
        line(
            "cast",
            "M3 8V5h18v14h-6",
            "M3 11.5a7.5 7.5 0 0 1 7.5 7.5",
            "M3 15a4 4 0 0 1 4 4",
            "M3 18.5h.5v.5H3z",
        )
    }
    val Film by lazy {
        line("film", "M4 3h16v18H4z", "M8 3v18", "M16 3v18", "M4 8h4", "M4 16h4", "M16 8h4", "M16 16h4")
    }
    val Series by lazy { line("series", "M3 6h18v11H3z", "M8 20h8", "M12 17v3") }
    val Book by lazy { line("book", "M6 3h13v18H6z", "M9 3v18", "M12 8h4") }
    val Music by lazy {
        line("music", "M9 17V6l10-2v11", "M7 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M17 13a2 2 0 1 0 0 4a2 2 0 1 0 0-4z")
    }
    val Podcast by lazy { line("podcast", "M9 3h6v10H9z", "M5 11a7 7 0 0 0 14 0", "M12 18v3", "M8 21h8") }
    val Feed by lazy { line("feed", "M5 19h1v1H5z", "M5 12a7 7 0 0 1 7 7", "M5 5a14 14 0 0 1 14 14") }
    val Document by lazy { line("document", "M6 3h8l4 4v14H6z", "M14 3v4h4", "M9 12h6", "M9 16h6") }
    val Discover by lazy { line("discover", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M15.5 8.5L13 13l-4.5 2.5L11 11z") }
    val Image by lazy { line("image", "M3 5h18v14H3z", "M3 16l5-5l5 5", "M12 15l3-3l6 6", "M15 8.5h1v1h-1z") }

    // ── transport (solid) ────────────────────────────────────────────────────────────────
    val Play by lazy { solid("play", "M8 5l11 7l-11 7z") }
    val Pause by lazy { solid("pause", "M7 5h3.5v14H7z", "M13.5 5H17v14h-3.5z") }
    val Stop by lazy { solid("stop", "M6 6h12v12H6z") }
    val SkipNext by lazy { solid("skip_next", "M6 6l9 6l-9 6z", "M16 6h2v12h-2z") }
    val SkipPrevious by lazy { solid("skip_previous", "M18 6l-9 6l9 6z", "M6 6h2v12H6z") }
    val Rewind by lazy { line("rewind", "M11 7l-6 5l6 5z", "M19 7l-6 5l6 5z") }
    val FastForward by lazy { line("fast_forward", "M13 7l6 5l-6 5z", "M5 7l6 5l-6 5z") }

    /** Seek back / forward ten seconds: the circling arrow carries a "10", so the step is visible. */
    val Back10 by lazy { line("back_10", "M5 12a7 7 0 1 0 2.05-4.95", "M5 4v4h4", "M10 10v5", "M13 10h2.5v5H13z") }
    val Forward10 by lazy {
        line(
            "forward_10",
            "M19 12a7 7 0 1 1-2.05-4.95",
            "M19 4v4h-4",
            "M9.5 10v5",
            "M12.5 10H15v5h-2.5z",
        )
    }

    // ── actions ──────────────────────────────────────────────────────────────────────────
    val Add by lazy { line("add", "M12 5v14", "M5 12h14") }
    val Close by lazy { line("close", "M6 6l12 12", "M18 6L6 18") }
    val Back by lazy { line("back", "M19 12H5", "M11 6l-6 6l6 6") }
    val Forward by lazy { line("forward", "M5 12h14", "M13 6l6 6l-6 6") }
    val ExpandMore by lazy { line("expand_more", "M6 9l6 6l6-6") }
    val ExpandLess by lazy { line("expand_less", "M6 15l6-6l6 6") }
    val More by lazy { solid("more", "M4 11h2.5v2.5H4z", "M10.75 11h2.5v2.5h-2.5z", "M17.5 11H20v2.5h-2.5z") }
    val Refresh by lazy { line("refresh", "M19 12a7 7 0 1 1-2-4.95", "M19 4v4h-4") }
    val Check by lazy { line("check", "M5 12.5l5 5L19 8") }
    val Info by lazy { line("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 11v6", "M12 7.5v1") }
    val Warning by lazy { line("warning", "M12 4l9 16H3z", "M12 10v4", "M12 16.5v1") }
    val Error by lazy { line("error", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 7.5v6", "M12 16v1") }
    val Help by lazy {
        line(
            "help",
            "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z",
            "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6",
            "M12 16.5v1",
        )
    }
    val Verified by lazy { line("verified", "M12 3l7 3v5c0 4.5-3 8-7 10c-4-2-7-5.5-7-10V6z", "M8.5 12l2.5 2.5l4.5-5") }
    val Download by lazy { line("download", "M12 4v11", "M7 10l5 5l5-5", "M5 20h14") }
    val Save by lazy { line("save", "M4 4h13l3 3v13H4z", "M8 4v5h7V4", "M8 20v-6h8v6") }
    val Delete by lazy { line("delete", "M5 7h14", "M9 7V4h6v3", "M7 7l1 13h8l1-13", "M10.5 11v5", "M13.5 11v5") }
    val Sync by lazy {
        line("sync", "M5 11a7 7 0 0 1 12-4l2 2", "M19 4v5h-5", "M19 13a7 7 0 0 1-12 4l-2-2", "M5 20v-5h5")
    }
    val History by lazy { line("history", "M4 12a8 8 0 1 0 2.3-5.6L4 8.7", "M4 4v4.7h4.7", "M12 8v4l3 2") }
    val Build by lazy { line("build", "M14.5 4.5a4 4 0 0 0-5 5L4 15v5h5l5.5-5.5a4 4 0 0 0 5-5L17 12l-5-5z") }
    val OpenExternal by lazy { line("open_external", "M14 4h6v6", "M20 4l-9 9", "M18 14v6H4V6h6") }
    val Expand by lazy { line("expand", "M14 4h6v6", "M20 4l-6 6", "M10 20H4v-6", "M4 20l6-6") }
    val Fullscreen by lazy { line("fullscreen", "M4 9V4h5", "M15 4h5v5", "M20 15v5h-5", "M9 20H4v-5") }
    val FullscreenExit by lazy { line("fullscreen_exit", "M9 4v5H4", "M20 9h-5V4", "M15 20v-5h5", "M4 15h5v5") }
    val Captions by lazy { line("captions", "M3 6h18v12H3z", "M7 11h4", "M13 11h4", "M7 14h6", "M15 14h2") }
    val Volume by lazy {
        line("volume", "M4 9h4l5-4v14l-5-4H4z", "M16 9.5a3.5 3.5 0 0 1 0 5", "M18.5 7a7 7 0 0 1 0 10")
    }
    val VolumeOff by lazy { line("volume_off", "M4 9h4l5-4v14l-5-4H4z", "M16 9.5l5 5", "M21 9.5l-5 5") }
    val Offline by lazy {
        line(
            "offline",
            "M2 9a15 15 0 0 1 4-2.5",
            "M9.5 5.6A15 15 0 0 1 22 9",
            "M5.5 12.5a10 10 0 0 1 3-1.8",
            "M12 17.5h.5v.5H12z",
            "M3 3l18 18",
        )
    }
    val GridView by lazy { line("grid_view", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z") }
    val ListView by lazy {
        line("list_view", "M9 6h11", "M9 12h11", "M9 18h11", "M4 5h2v2H4z", "M4 11h2v2H4z", "M4 17h2v2H4z")
    }
    val CheckBox by lazy { line("check_box", "M4 4h16v16H4z", "M8 12l3 3l5-6") }
    val CheckBoxBlank by lazy { line("check_box_blank", "M4 4h16v16H4z") }
    val TextLarger by lazy { line("text_larger", "M3 19l5-14l5 14", "M5 14h6", "M18 9v6", "M15 12h6") }
    val TextSmaller by lazy { line("text_smaller", "M3 19l5-14l5 14", "M5 14h6", "M15 12h6") }

    private fun line(name: String, vararg paths: String): ImageVector = build(name, paths, solid = false)

    private fun solid(name: String, vararg paths: String): ImageVector = build(name, paths, solid = true)

    private fun build(name: String, paths: Array<out String>, solid: Boolean): ImageVector {
        val builder = ImageVector.Builder("heyarr.$name", 24.dp, 24.dp, VIEWPORT, VIEWPORT)
        for (d in paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = if (solid) INK else null,
                stroke = if (solid) null else INK,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
        return builder.build()
    }

    private const val VIEWPORT = 24f
    private const val STROKE = 1.5f

    // Any opaque colour: Icon() tints the whole vector.
    private val INK = SolidColor(Color.Black)
}
