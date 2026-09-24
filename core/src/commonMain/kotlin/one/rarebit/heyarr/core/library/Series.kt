package one.rarebit.heyarr.core.library

/**
 * What [Series] needs to know about a work's file. Each app's asset model (the desktop's
 * `Track`, the phone's `WorkAsset`) implements it, so the grouping is written once and
 * hands the app its own type back.
 */
interface EpisodeFile {
    val id: String
    val filename: String?
    val mime: String?
    val role: String?
    val sourcePath: String?

    /** The edition's label (`Season 02`, `Specials`, …): the node's own season grouping. */
    val editionLabel: String?
    val blobHash: String?

    /** A blob the client can stream, and the file is present. */
    val isPlayable: Boolean
}

/**
 * One episode of a series: a work's file, read as **season → number → title**.
 *
 * heyarr-core groups an episodic work's files into one edition per season (its
 * `identification/series.go`: the Edition of an episodic Work is its season, labelled
 * `Season 02` / `Specials`), and `GET /works/{id}/assets` (#429) inlines that label on
 * every file. The episode number and title are not on the wire — they live in the
 * filename the scanner matched (`S04E01`, `2x05`, `Episode 5 - Title`), so this reads
 * them back the same way the node did. Pure, and shared by both clients so they read a
 * scan the same way (the sidecar pairing, season gaps and quality tags included); each
 * app's `SeriesTest` pins it against its own asset model.
 */
data class Episode<A : EpisodeFile>(
    val asset: A,
    /** The season, `0` for specials, null when nothing names one. */
    val season: Int?,
    /** The episode number within the season, null when the filename carries none. */
    val number: Int?,
    /** The cleaned remainder of the filename after the episode marker, null when empty. */
    val title: String?,
    /** The `-thumb.jpg` sidecar the scan recorded for this file, when there is one. */
    val thumbnail: A? = null,
    /** Subtitle sidecars that share this episode's stem. */
    val subtitles: List<A> = emptyList(),
) {
    /**
     * `S04E01` when both numbers are known, `E05` with only the number; null with no
     * number at all — under a season heading a bare `S03` would say nothing.
     */
    val code: String?
        get() = when {
            season != null && number != null -> "S${twoDigits(season)}E${twoDigits(number)}"
            number != null -> "E${twoDigits(number)}"
            else -> null
        }

    // `%02d` without java.util.Formatter: the numbers are parsed from digits, never negative.
    private fun twoDigits(n: Int): String = n.toString().padStart(2, '0')

    /** The one line the episode list shows: code + title, else the filename. */
    val label: String
        get() = listOfNotNull(code, title).joinToString(" ").ifBlank { asset.filename ?: asset.id }

    val isPlayable: Boolean get() = asset.isPlayable

    /** The thumbnail's relative content path, for the artwork URL builder. */
    val thumbnailPath: String? get() = thumbnail?.blobHash?.let { "/api/v1/blobs/$it/content" }
}

/** One season of a series, its episodes in order. [number] is `0` for specials, null when unknown. */
data class Season<A : EpisodeFile>(val number: Int?, val episodes: List<Episode<A>>) {
    val label: String get() = Series.seasonLabel(number)

    /** Episode numbers between 1 and the highest held that no file covers — what "missing" means without a provider. */
    val gaps: List<Int>
        get() {
            val nums = episodes.mapNotNull { it.number }.toSet()
            val max = nums.maxOrNull() ?: return emptyList()
            return (1..max).filter { it !in nums }
        }

    val held: Int get() = episodes.count { it.isPlayable }
}

object Series {

    /** The server content types (and client kinds) that mean an episodic work. */
    private val KINDS =
        setOf("series", "show", "tv", "television", "tvshow", "tv_show", "tv_series", "episode", "season")

    /** True for a work whose files are episodes rather than one film. */
    fun isSeries(kind: String?): Boolean = kind?.lowercase()?.trim() in KINDS

    /** `Season 4` / `Specials` / `Episodes` for a season number. */
    fun seasonLabel(number: Int?): String = when {
        number == null -> "Episodes"
        number == 0 -> "Specials"
        else -> "Season $number"
    }

    /**
     * A series' files as seasons, each with its episodes in order, each episode paired
     * with the thumbnail and subtitle sidecars that share its filename stem. Only
     * episode files take part as rows; sidecars attach to them. Numbered seasons come
     * first in order, then Specials, then whatever named no season; within a season the
     * episodes order by number, the unnumbered last, ties by filename.
     */
    fun <A : EpisodeFile> seasons(assets: List<A>): List<Season<A>> {
        val thumbs = assets.filter { isThumbnail(it) }.associateBy { stemKey(it, stripThumb = true) }
        val subs = assets.filter { isSubtitle(it) }.groupBy { stemKey(it, stripThumb = false, stripLang = true) }
        val episodes = assets.mapNotNull { a ->
            episode(a)?.let { e ->
                val key = stemKey(a, stripThumb = false)
                e.copy(thumbnail = thumbs[key], subtitles = subs[key].orEmpty())
            }
        }
        if (episodes.isEmpty()) return emptyList()
        return episodes.groupBy { it.season }
            .map { (season, eps) ->
                Season(
                    season,
                    eps.sortedWith(
                        compareBy<Episode<A>> {
                            it.number == null
                        }.thenBy { it.number ?: 0 }.thenBy {
                            it.asset.filename
                                ?: it.asset.id
                        },
                    ),
                )
            }
            .sortedWith(compareBy<Season<A>> { seasonRank(it.number) }.thenBy { it.number ?: 0 })
    }

    /** The first episode that can play, in season order — what the header's Play means for a series. */
    fun <A : EpisodeFile> firstPlayable(seasons: List<Season<A>>): Episode<A>? {
        val episodes = seasons.asSequence().flatMap { it.episodes.asSequence() }
        return episodes.firstOrNull { it.isPlayable }
    }

    /** The player's title for an episode: `Yellowstone — S04E01 Half the Money`. */
    fun playTitle(work: CatalogWork, episode: Episode<*>): String = "${work.title} — ${episode.label}"

    /**
     * One file as an episode, or null when it is a sidecar. The season comes from the
     * edition label first (the node's own grouping), else the filename, else a season
     * directory on the source path; the number and title from the filename.
     */
    fun <A : EpisodeFile> episode(asset: A): Episode<A>? {
        if (!isEpisodeFile(asset)) return null
        val stem = (asset.filename ?: asset.sourcePath?.substringAfterLast('/') ?: "").substringBeforeLast('.')
        val marker = markerIn(stem)
        val season = seasonOfLabel(asset.editionLabel) ?: marker?.season ?: seasonOfPath(asset.sourcePath)
        val rest = marker?.let { stem.substring(it.end) } ?: stem
        return Episode(asset = asset, season = season, number = marker?.number, title = episodeTitle(rest))
    }

    /** `Season 02` → 2, `Specials` → 0, anything else → null. */
    fun seasonOfLabel(label: String?): Int? {
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return null
        if (RE_SPECIALS_LABEL.matches(l)) return 0
        return RE_SEASON_LABEL.matchEntire(l)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The season a `…/Season 02/…` or `…/Specials/…` directory on the source path implies. */
    fun seasonOfPath(sourcePath: String?): Int? {
        val dirs =
            sourcePath?.replace('\\', '/')?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() } ?: return null
        for (d in dirs.split('/').asReversed()) {
            val t = d.trim()
            if (RE_SPECIALS_LABEL.matches(t)) return 0
            RE_SEASON_DIR.matchEntire(t)?.let { return it.groupValues[1].toIntOrNull() }
        }
        return null
    }

    /** True for a `…-thumb.jpg` / `.png` artwork sidecar. */
    fun isThumbnail(asset: EpisodeFile): Boolean {
        val name = (asset.filename ?: "").lowercase()
        return (asset.role == "artwork" || name.endsWith("-thumb.jpg") || name.endsWith("-thumb.png")) &&
            name.contains("-thumb.")
    }

    /** True for a subtitle sidecar (`.srt`, `.vtt`, `.ass`, `.sub`, `.sup`). */
    fun isSubtitle(asset: EpisodeFile): Boolean {
        val role = asset.role?.lowercase()
        if (role == "subtitle" || role == "subtitles") return true
        val ext = (asset.filename ?: "").substringAfterLast('.', "").lowercase()
        return ext in SUBTITLE_EXTENSIONS
    }

    /** The normalised stem two sidecars share: lowercase, `-thumb` and a trailing language tag stripped. */
    internal fun stemKey(asset: EpisodeFile, stripThumb: Boolean, stripLang: Boolean = false): String {
        var stem = (
            asset.filename ?: asset.sourcePath?.substringAfterLast(
                '/',
            ) ?: asset.id
            ).substringBeforeLast('.').lowercase()
        if (stripThumb) stem = stem.removeSuffix("-thumb")
        if (stripLang) stem = stem.replace(RE_LANG_SUFFIX, "")
        return stem
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private class Marker(val season: Int?, val number: Int, val end: Int)

    /**
     * The episode marker in a filename stem: `S04E01` (also `S04E01E02`), `4x01`,
     * `Episode 5`, `E05`, or a leading `05 -`.
     */
    private fun markerIn(stem: String): Marker? {
        RE_SXXEXX.find(stem)?.let { m ->
            return Marker(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.range.last + 1,
            )
        }
        RE_NXNN.find(stem)?.let { m ->
            return Marker(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.range.last + 1,
            )
        }
        RE_EPISODE_WORD.find(stem)?.let { m -> return Marker(null, m.groupValues[1].toInt(), m.range.last + 1) }
        RE_LEADING_NUMBER.find(stem)?.let { m -> return Marker(null, m.groupValues[1].toInt(), m.range.last + 1) }
        return null
    }

    /**
     * The remainder of a filename after the marker, as a title: separators become
     * spaces, and the text is cut at the first release-metadata token (`1080p`,
     * `WEB-DL`, `x265`, a year, a bracketed tag, …) — a scene name usually *starts*
     * its metadata right after the episode code, and `PROPER` is not an episode title.
     */
    fun episodeTitle(rest: String): String? {
        val cleaned = rest.replace(RE_BRACKETS, " ").replace(RE_SEPARATORS, " ").trim().trimStart('-', '–', ':').trim()
        if (cleaned.isEmpty()) return null
        val kept = ArrayList<String>()
        for (tok in cleaned.split(' ').filter { it.isNotBlank() }) {
            if (isNoise(tok)) break
            kept.add(tok)
        }
        return kept.joinToString(" ").trim().trimEnd('-', '–').trim().ifBlank { null }
    }

    /** The quality tags a scene name carries after the title (`1080p · WEB-DL · x265`), for a small chip row. */
    fun qualityTags(asset: EpisodeFile): List<String> {
        val stem = (asset.filename ?: "").substringBeforeLast('.')
        val cleaned = stem
            .replace(RE_BRACKETS) { " " + it.value.trim('[', ']', '(', ')', '{', '}') + " " }
            .replace(RE_SEPARATORS, " ")
        return cleaned.split(' ')
            .filter { it.isNotBlank() }
            .filter { isNoise(it) && !RE_YEAR.matches(it.lowercase()) }
            .map { it.uppercase() }
            .distinct()
            .take(4)
    }

    private fun isNoise(token: String): Boolean {
        val t = token.lowercase().trimEnd(',', ';')
        // `x265-GRP`, `H264-NTb`: the release group rides on the codec with a hyphen.
        val base = t.substringBefore('-')
        return t in NOISE || base in NOISE ||
            RE_RESOLUTION.matches(t) || RE_RESOLUTION.matches(base) ||
            RE_YEAR.matches(t) ||
            RE_CODEC.matches(t) || RE_CODEC.matches(base)
    }

    private fun isEpisodeFile(asset: EpisodeFile): Boolean {
        val role = asset.role?.lowercase()?.trim()
        if (!role.isNullOrEmpty() && role != "primary") return false
        val mime = asset.mime?.lowercase()?.substringBefore(';')?.trim()
        if (mime != null &&
            (mime.startsWith("image/") || mime.startsWith("text/") || mime == "application/xml")
        ) {
            return false
        }
        val ext = (asset.filename ?: asset.sourcePath ?: "").substringAfterLast('.', "").lowercase()
        if (ext in SIDECAR_EXTENSIONS) return false
        return true
    }

    private fun seasonRank(number: Int?): Int = when {
        number == null -> 2
        number == 0 -> 1
        else -> 0
    }

    private val RE_SXXEXX = Regex("""(?i)(?<![a-z0-9])S(\d{1,3})[ ._-]?E(\d{1,3})(?:[ ._-]?E\d{1,3})*""")
    private val RE_NXNN = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![a-z0-9])""")
    private val RE_EPISODE_WORD = Regex("""(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._-]*(\d{1,3})(?![a-z0-9])""")
    private val RE_LEADING_NUMBER = Regex("""^\s*(\d{1,3})\s*[-._ ]+""")
    private val RE_SEASON_LABEL = Regex("""(?i)^(?:season|series|staffel|saison|s)\s*(\d{1,3})$""")
    private val RE_SEASON_DIR =
        Regex("""(?i)^(?:season|series|staffel|saison|stagione|temporada|seizoen|s)[ ._-]*(\d{1,3})(?:[^0-9].*)?$""")
    private val RE_SPECIALS_LABEL = Regex("""(?i)^specials?$""")

    // Closing `]` and `}` are escaped on purpose: the desktop JVM's regex accepts them
    // bare, Android's (ICU-backed) throws PatternSyntaxException, which surfaced as an
    // ExceptionInInitializerError on every Detail open that touched this object.
    private val RE_BRACKETS = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""")
    private val RE_SEPARATORS = Regex("""[._\s]+""")
    private val RE_RESOLUTION = Regex("""^\d{3,4}[pi]$|^[248]k$""")
    private val RE_YEAR = Regex("""^(19|20)\d{2}$""")
    private val RE_CODEC =
        Regex(
            """^(x|h)\.?26[45]$|^hevc$|^avc$|^av1$|^xvid$|^divx$|^vp9$|^(aac|ac3|eac3|dts|ddp?|truehd|flac|opus)(\d.*)?$""",
        )
    private val RE_LANG_SUFFIX =
        Regex(
            """\.(en|eng|es|spa|fr|fre|fra|de|ger|deu|it|ita|pt|por|ja|jpn|zh|chi|nl|dut|sv|swe|forced|sdh)(\.(forced|sdh))?$""",
        )

    private val NOISE = setOf(
        "web", "web-dl", "webdl", "webrip", "web-rip", "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux",
        "hdtv", "pdtv", "dvdrip", "dvd", "hdr", "hdr10", "hdr10+", "dv", "dovi", "sdr", "uhd",
        "proper", "repack", "rerip", "internal", "limited", "extended", "uncut", "remastered", "multi", "dual",
        "subbed", "dubbed", "hc", "ws", "atmos", "amzn", "nf", "dsnp", "hmax", "pcok", "atvp", "hulu", "itunes",
        "10bit", "8bit", "hi10p", "hi10", "sample", "hdtv-1080p", "hdtv-720p", "web-1080p", "webdl-1080p",
    )

    private val SUBTITLE_EXTENSIONS = setOf("srt", "sub", "idx", "ass", "ssa", "vtt", "sup")

    private val SIDECAR_EXTENSIONS = setOf(
        "nfo", "jpg", "jpeg", "png", "webp", "gif", "bmp", "tbn", "srt", "sub", "idx", "ass", "ssa", "vtt", "sup",
        "txt", "xml", "json", "md5", "sfv", "db", "ini", "url", "torrent",
    )
}
