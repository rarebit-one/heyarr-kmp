package one.rarebit.heyarr.core.playback

import one.rarebit.heyarr.core.library.Bytes
import one.rarebit.heyarr.core.library.EpisodeFile

/**
 * What [Copies] needs to know about one of a work's files, on top of [EpisodeFile]. Each
 * app's asset model (the desktop's `Track`, the phone's `WorkAsset`) implements it.
 *
 * The `probed*` facts are what the node measured on the blob (heyarr-core#885 puts them on
 * `WorkAsset`). A node without them leaves them null and [Copies] reads the edition label
 * and filename instead — never both: a probe beats a release name.
 */
interface CopyFile : EpisodeFile {
    val sizeBytes: Long?
    val probedWidth: Int? get() = null
    val probedHeight: Int? get() = null
    val probedHdr: Boolean? get() = null
}

/**
 * The device-local "Prefer:" setting (heyarr-kmp#225). Device-local on purpose: what fits
 * is a fact about this screen and this connection, not about the person; a synced per-user
 * preference is a later issue.
 */
enum class QualityPreference(val key: String, val label: String) {
    /** The tallest copy held, whatever the screen. */
    BEST("best", "Best quality"),

    /** The tallest copy that fits this screen — the default. */
    FIT("fit", "Fit this screen"),

    /** The tallest copy at or under 720p, to spare a metered connection. */
    DATA_SAVER("data_saver", "Data saver (≤720p)"),
    ;

    companion object {
        val DEFAULT = FIT

        /** The stored [key] back to a preference; anything unknown (or nothing stored) is [DEFAULT]. */
        fun of(key: String?): QualityPreference = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * What this device can show: the tallest frame its screen has pixels for, and whether the
 * screen renders HDR. [height] is the panel's SHORT edge — video plays landscape, so a
 * 1080×2412 phone shows a 1080-line picture at full resolution and nothing taller.
 */
data class DisplayFit(val height: Int, val supportsHdr: Boolean = false)

/** One playable video copy of a work, read for choosing between copies. */
data class Copy<A : CopyFile>(
    val asset: A,
    /** The copy's nominal line count (`1080` for 1920×800 scope too), null when nothing says. */
    val height: Int?,
    val hdr: Boolean,
    /** True when [height] / [hdr] came from the node's probe rather than a release name. */
    val probed: Boolean,
) {
    val sizeBytes: Long? get() = asset.sizeBytes

    /** `2160p HDR`, `1080p`, `HDR`, or null when nothing is known of the picture. */
    val picture: String?
        get() = when {
            height != null && hdr -> "${height}p HDR"
            height != null -> "${height}p"
            hdr -> "HDR"
            else -> null
        }

    /** `2160p HDR · 46.6 GB` — what a Version menu and the detail screen list. */
    val label: String
        get() = listOfNotNull(picture, sizeBytes?.let { Bytes.format(it) }).joinToString(" · ")
            .ifBlank { asset.filename ?: asset.id }
}

/**
 * Choosing which copy of a film to play on this device (heyarr-kmp#225).
 *
 * A work can hold several copies — a 2160p HDR remux kept by one want, a 1080p encode kept
 * by another. The node's `primary_asset` is the oldest, so a phone was handed the 4K HDR
 * file and the node re-encoded it on the fly. Until the node plans by work
 * (heyarr-core#885), and against any node that does not, the client lists the work's
 * assets and chooses the same way the node will:
 *
 *  1. a copy the person chose for this work on this device wins outright;
 *  2. else the tallest copy at or under the cap ([QualityPreference] over [DisplayFit]) —
 *     on a screen without HDR an SDR copy first, because the node's re-encode does not
 *     tone-map (heyarr-core#885 leaves that out of scope) and HDR shown as SDR is washed
 *     out; on an HDR screen the HDR copy wins a tie;
 *  3. else a copy whose height nothing states (the work's default first): it may well fit,
 *     and it is what played before this existed;
 *  4. else the smallest copy above the cap, which the node downscales as it always has.
 *
 * Pure; both apps' players call it, and `CopiesTest` pins it as a table.
 */
object Copies {
    /** The data saver's ceiling, in lines. */
    const val DATA_SAVER_HEIGHT = ReleaseName.HD

    /**
     * The work's playable video copies, tallest first (then largest). Sidecars, artwork,
     * audio and missing files are not copies. A series is not handled here: its files are
     * episodes, and `Series` folds an episode's copies itself.
     */
    fun <A : CopyFile> of(assets: List<A>): List<Copy<A>> = assets
        .filter { it.isPlayable && isPrimary(it.role) && isVideo(it.mime, it.filename) }
        .map { read(it) }
        .sortedWith(compareByDescending<Copy<A>> { it.height ?: -1 }.thenByDescending { it.sizeBytes ?: -1L })

    /** One file read as a copy: the probe when the node sent one, else its release name. */
    fun <A : CopyFile> read(asset: A): Copy<A> {
        val probedHeight = asset.probedHeight?.takeIf { it > 0 }
        if (probedHeight != null || asset.probedHdr != null) {
            return Copy(
                asset = asset,
                height = probedHeight?.let { nominalHeight(asset.probedWidth, it) },
                hdr = asset.probedHdr ?: false,
                probed = true,
            )
        }
        val names = listOfNotNull(asset.editionLabel, asset.filename)
        return Copy(
            asset = asset,
            height = names.firstNotNullOfOrNull { ReleaseName.height(it) },
            hdr = names.firstNotNullOfOrNull { ReleaseName.hdr(it) } ?: false,
            probed = false,
        )
    }

    /** The tallest line count [preference] allows on [display]; [Int.MAX_VALUE] for best quality. */
    fun cap(display: DisplayFit, preference: QualityPreference): Int = when (preference) {
        QualityPreference.BEST -> Int.MAX_VALUE
        QualityPreference.FIT -> display.height
        QualityPreference.DATA_SAVER -> minOf(display.height, DATA_SAVER_HEIGHT)
    }

    /**
     * The copy to play, or null when the work has no video copies (the caller plays its
     * primary asset as before). [chosenId] is the copy the person picked for this work on
     * this device, [defaultId] the node's `primary_asset` — see the class doc for the order.
     */
    fun <A : CopyFile> pick(
        copies: List<Copy<A>>,
        display: DisplayFit,
        preference: QualityPreference = QualityPreference.DEFAULT,
        chosenId: String? = null,
        defaultId: String? = null,
    ): Copy<A>? = when {
        copies.size <= 1 -> copies.firstOrNull()
        else -> copies.firstOrNull { it.asset.id == chosenId } ?: byRule(copies, display, preference, defaultId)
    }

    /** Rules 2–4 of the class doc, over at least two copies. */
    private fun <A : CopyFile> byRule(
        copies: List<Copy<A>>,
        display: DisplayFit,
        preference: QualityPreference,
        defaultId: String?,
    ): Copy<A> {
        val cap = cap(display, preference)
        // An SDR copy first on an SDR screen; on an HDR screen HDR only breaks a tie.
        val hdrMismatch: (Copy<A>) -> Int = { c -> if (c.hdr == display.supportsHdr) 0 else 1 }
        val fits = copies.filter { it.height != null && it.height <= cap }
        val unknown = copies.filter { it.height == null }
        val bySize: Comparator<Copy<A>> = if (preference == QualityPreference.DATA_SAVER) {
            compareBy { it.sizeBytes ?: Long.MAX_VALUE }
        } else {
            compareByDescending { it.sizeBytes ?: -1L }
        }
        val fitOrder = if (display.supportsHdr) {
            compareByDescending<Copy<A>> { it.height }.thenBy(hdrMismatch)
        } else {
            compareBy(hdrMismatch).thenByDescending { it.height }
        }
        return when {
            fits.isNotEmpty() -> fits.sortedWith(fitOrder.then(bySize)).first()

            unknown.isNotEmpty() -> unknown.firstOrNull { it.asset.id == defaultId } ?: unknown.first()

            else -> copies.sortedWith(
                compareBy<Copy<A>> { it.height }.thenBy(hdrMismatch).thenBy { it.sizeBytes ?: Long.MAX_VALUE },
            ).first()
        }
    }

    /**
     * The `max_height` to declare to `POST /playback/plan` for [copy] — a fact about the
     * screen, capped by the preference. Two cases lift it to the copy's own height, so the
     * node does not downscale what was asked for: best quality, and a copy the person
     * [chosen] by hand. [ceiling] is the app's own bound on what it will have re-encoded
     * for it (the desktop's software-decode profile stops at 1080).
     */
    fun declaredMaxHeight(
        copy: Copy<*>?,
        display: DisplayFit,
        preference: QualityPreference = QualityPreference.DEFAULT,
        chosen: Boolean = false,
        ceiling: Int = Int.MAX_VALUE,
    ): Int {
        val height = if (preference == QualityPreference.BEST || chosen) {
            maxOf(display.height, copy?.height ?: display.height)
        } else {
            cap(display, preference)
        }
        return minOf(height, ceiling)
    }

    /**
     * A probed frame as the line class people call it: `1920×800` scope is "1080p", not
     * "800p", and DCI `4096×2160` is "2160p". The frame's 16:9 equivalent height, snapped to
     * the nearest standard class within 8%.
     */
    fun nominalHeight(width: Int?, height: Int): Int {
        val fromWidth = width?.takeIf { it > 0 }?.let { it * WIDE_H / WIDE_W } ?: 0
        val lines = maxOf(height, fromWidth)
        return ReleaseName.CLASSES.firstOrNull { c -> kotlin.math.abs(lines - c) * PERCENT <= c * SNAP_PERCENT }
            ?: lines
    }

    private fun isPrimary(role: String?): Boolean = role.isNullOrBlank() || role.equals("primary", ignoreCase = true)

    private fun isVideo(mime: String?, filename: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        return when {
            m != null && m.startsWith("video/") -> true

            // A blob sniffed as octet-stream is still a film when its name says so.
            !m.isNullOrEmpty() && m != "application/octet-stream" -> false

            else -> filename?.substringAfterLast('.', "")?.lowercase() in VIDEO_EXTENSIONS
        }
    }

    private const val WIDE_W = 16
    private const val WIDE_H = 9
    private const val PERCENT = 100
    private const val SNAP_PERCENT = 8
    private val VIDEO_EXTENSIONS =
        setOf("mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "ogv")
}

/**
 * What a release name or edition label says about the picture — the fallback [Copies]
 * reads when the node has not probed the blob. Pure.
 */
object ReleaseName {
    const val SD = 480
    const val PAL = 576
    const val HD = 720
    const val FULL_HD = 1080
    const val QHD = 1440
    const val UHD = 2160
    const val UHD_8K = 4320

    /** The standard line classes, shortest first. */
    val CLASSES = listOf(SD, PAL, HD, FULL_HD, QHD, UHD, UHD_8K)

    /** `2160p` / `1080i` / `4K` / `UHD` as lines; null when the name states none. */
    fun height(name: String): Int? {
        val lines = RE_LINES.find(name)?.groupValues?.get(1)?.toInt()?.takeIf { it in MIN_LINES..UHD_8K }
        val tokens = tokens(name)
        return when {
            lines != null -> lines
            "8k" in tokens -> UHD_8K
            "4k" in tokens || "uhd" in tokens -> UHD
            else -> null
        }
    }

    /** True for `HDR`, `HDR10(+)`, `DV`, `DoVi`, `Dolby Vision`, `HLG`; false for an explicit `SDR`; else null. */
    fun hdr(name: String): Boolean? {
        val tokens = tokens(name)
        return when {
            tokens.any { it in HDR_TOKENS } -> true
            "dolby" in tokens && "vision" in tokens -> true
            "sdr" in tokens -> false
            else -> null
        }
    }

    private fun tokens(name: String): Set<String> =
        name.lowercase().split(RE_NOT_ALNUM).filter { it.isNotEmpty() }.toSet()

    private const val MIN_LINES = 240
    private val RE_LINES = Regex("""(?i)(?<![0-9])(\d{3,4})[pi](?![a-z0-9])""")

    // `+` is a separator, so `HDR10+` reads as `hdr10`.
    private val RE_NOT_ALNUM = Regex("""[^a-z0-9]+""")
    private val HDR_TOKENS = setOf("hdr", "hdr10", "hdr10plus", "dv", "dovi", "hlg", "dolbyvision")
}
