package one.rarebit.heyarr.core.playback

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan

/**
 * What a resume point is about: one file (an episode, one copy of a film), the work it
 * belongs to, and enough to play it again from a Continue watching card without asking the
 * node first: its title, kind, blob, MIME and artwork, as the player had them.
 */
data class ResumeItem(
    val assetId: String,
    val workId: String? = null,
    val title: String,
    val kind: String? = null,
    val blobHash: String? = null,
    val mime: String? = null,
    val artworkUrl: String? = null,
)

/**
 * Where playback of [item] was left on this device (heyarr-kmp#232): the source position,
 * the runtime when the player knew it, and when it was written. Device-local for now;
 * syncing it as personal state is a later issue.
 */
data class ResumePoint(
    val item: ResumeItem,
    val positionSeconds: Double,
    val durationSeconds: Double? = null,
    val updatedAtMs: Long,
) {
    val assetId: String get() = item.assetId
    val workId: String? get() = item.workId

    /** 0..1 for a progress bar, when the runtime is known. */
    val fraction: Float? get() {
        val d = durationSeconds?.takeIf { it > 0 } ?: return null
        return (positionSeconds / d).coerceIn(0.0, 1.0).toFloat()
    }

    /** One line of JSON, for a store that keeps strings (the phone's SharedPreferences). */
    fun toJson(): String = JsonWrite.obj(
        mapOf(
            "asset_id" to item.assetId,
            "work_id" to item.workId,
            "title" to item.title,
            "kind" to item.kind,
            "blob_hash" to item.blobHash,
            "mime" to item.mime,
            "artwork_url" to item.artworkUrl,
            "position_s" to positionSeconds,
            "duration_s" to durationSeconds,
            "updated_at_ms" to updatedAtMs,
        ),
    )

    companion object {
        /** The [toJson] form back; null for anything that is not one (a store written by an older build). */
        fun fromJson(json: String): ResumePoint? {
            val assetId = JsonScan.stringField(json, "asset_id")
            val position = JsonScan.doubleField(json, "position_s")
            val updated = JsonScan.longField(json, "updated_at_ms")
            if (assetId == null || position == null || updated == null) return null
            return ResumePoint(
                ResumeItem(
                    assetId = assetId,
                    workId = JsonScan.stringField(json, "work_id"),
                    title = JsonScan.stringField(json, "title") ?: "",
                    kind = JsonScan.stringField(json, "kind"),
                    blobHash = JsonScan.stringField(json, "blob_hash"),
                    mime = JsonScan.stringField(json, "mime"),
                    artworkUrl = JsonScan.stringField(json, "artwork_url"),
                ),
                positionSeconds = position,
                durationSeconds = JsonScan.doubleField(json, "duration_s"),
                updatedAtMs = updated,
            )
        }
    }
}

/**
 * Where resume points are kept. One per asset: a later write for the same file replaces
 * the earlier. The phone's is SharedPreferences (`PrefsResumeStore`); tests use
 * [InMemoryResumeStore].
 */
interface ResumeStore {
    fun get(assetId: String): ResumePoint?
    fun put(point: ResumePoint)
    fun remove(assetId: String)

    /** Every point held, in no particular order. */
    fun all(): List<ResumePoint>
}

/** Non-persistent store for tests and previews. */
class InMemoryResumeStore : ResumeStore {
    private val points = LinkedHashMap<String, ResumePoint>()
    override fun get(assetId: String) = points[assetId]
    override fun put(point: ResumePoint) {
        points[point.assetId] = point
    }
    override fun remove(assetId: String) {
        points.remove(assetId)
    }
    override fun all(): List<ResumePoint> = points.values.toList()
}

/**
 * When a position is worth resuming from, and which one a play starts at. Pure, so the
 * rules are unit-tested. The thresholds are the usual media-server ones (Jellyfin's
 * defaults are 5 % and 90 %; Plex and Netflix behave alike): the first moments of a film
 * are not worth a "Resume" prompt, and its credits mean it was watched.
 */
object ResumePolicy {
    /** Below this a play starts from the beginning: a peek, or a Start over that stopped early. */
    const val MIN_SECONDS = 30.0

    /** At or past this share of the runtime the item counts as watched, and its point is dropped. */
    const val FINISHED_FRACTION = 0.9

    /** How often a playing item's position is written: the most a crash can lose. */
    const val SAVE_EVERY_MS = 10_000L

    /** How many points are kept; the oldest go first. */
    const val KEEP = 200

    /** Watched to the end, or into its credits. */
    fun finished(positionSeconds: Double, durationSeconds: Double?): Boolean {
        val d = durationSeconds?.takeIf { it > 0 } ?: return false
        return positionSeconds >= d * FINISHED_FRACTION
    }

    /** Worth offering "Resume" for: past the opening moments and not yet finished. */
    fun resumable(positionSeconds: Double, durationSeconds: Double?): Boolean =
        positionSeconds >= MIN_SECONDS && !finished(positionSeconds, durationSeconds)
}

/** The reads the screens and the player make over a [ResumeStore]. Pure. */
object ResumePoints {
    /**
     * Where a play of [assetId] starts when the caller gave no position, or null for the
     * beginning. A film's copies share one timeline, so a point left on another of
     * [sameTimeline] (a copy switched away from, or picked differently on this play) counts
     * too; the newest wins. An episode is its own file and only its own point counts.
     */
    fun startFor(store: ResumeStore, assetId: String, sameTimeline: Collection<String> = emptyList()): Double? {
        val candidates = (sameTimeline + assetId).toSet()
        return store.all()
            .filter { it.assetId in candidates }
            .maxByOrNull { it.updatedAtMs }
            ?.positionSeconds
            ?.takeIf { it >= ResumePolicy.MIN_SECONDS }
    }

    /** The newest point on [workId]: the film's, or the series' episode left part-way. */
    fun forWork(points: List<ResumePoint>, workId: String): ResumePoint? =
        points.filter { it.workId == workId }.maxByOrNull { it.updatedAtMs }

    /**
     * The Continue watching row: the newest point per work (a series shows the episode it
     * was left on), newest first. A point with no work stands alone.
     */
    fun continueWatching(points: List<ResumePoint>, limit: Int = 20): List<ResumePoint> = points
        .sortedByDescending { it.updatedAtMs }
        .distinctBy { it.workId ?: "asset:${it.assetId}" }
        .take(limit)

    /** The asset ids to drop so no more than [keep] remain: the oldest. */
    fun overflow(points: List<ResumePoint>, keep: Int = ResumePolicy.KEEP): List<String> =
        points.sortedByDescending { it.updatedAtMs }.drop(keep).map { it.assetId }
}

/**
 * Writes resume points as the player reports where it is. The player calls [progress] on
 * its ticks (written every [ResumePolicy.SAVE_EVERY_MS]), [checkpoint] whenever it stops
 * moving or leaves (pause, Stop, the app going to the background: written at once), and
 * [ended] when it reaches the end. A position that is not [ResumePolicy.resumable] drops the
 * point instead: Start over and stopping in the first half-minute starts over next time, and
 * a film watched into its credits is done. So the store holds only points worth resuming.
 */
class ResumeRecorder(private val store: ResumeStore, private val clock: () -> Long) {
    private var lastSavedAtMs = Long.MIN_VALUE
    private var lastAssetId: String? = null

    fun progress(item: ResumeItem, positionSeconds: Double, durationSeconds: Double?) {
        val now = clock()
        if (item.assetId == lastAssetId && now - lastSavedAtMs < ResumePolicy.SAVE_EVERY_MS) return
        write(item, positionSeconds, durationSeconds, now)
    }

    fun checkpoint(item: ResumeItem, positionSeconds: Double, durationSeconds: Double?) =
        write(item, positionSeconds, durationSeconds, clock())

    fun ended(item: ResumeItem) {
        store.remove(item.assetId)
        lastAssetId = item.assetId
        lastSavedAtMs = clock()
    }

    private fun write(item: ResumeItem, positionSeconds: Double, durationSeconds: Double?, now: Long) {
        lastAssetId = item.assetId
        lastSavedAtMs = now
        if (!ResumePolicy.resumable(positionSeconds, durationSeconds)) {
            store.remove(item.assetId)
            return
        }
        store.put(ResumePoint(item, positionSeconds, durationSeconds?.takeIf { it > 0 }, now))
        ResumePoints.overflow(store.all()).forEach(store::remove)
    }
}
