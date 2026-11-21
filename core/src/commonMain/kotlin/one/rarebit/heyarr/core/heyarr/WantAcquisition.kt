package one.rarebit.heyarr.core.heyarr

import one.rarebit.heyarr.core.net.JsonScan

/**
 * The two-site half of a want's acquisition status (`GET /api/v1/desired/{id}/acquisition`,
 * heyarr-core ADR-0119): where a want a sibling's choice created came from and what this site
 * did with it ([fanOut]), and, at the origin, what came back from the siblings ([published]).
 * Both are absent on a single-site node, and so is everything this shows.
 *
 * Only the fan-out blocks are read here; the phase and transfer the same response carries are
 * already on the want ([DesiredItem]).
 */
data class WantAcquisition(val desiredItemId: String, val fanOut: FanOut?, val published: Published?) {
    /** The sibling's acquire op behind a want, and what this site did with it. */
    data class FanOut(
        val originSite: String,
        val releaseName: String?,
        /** applied, held, deferred, tombstoned, scope_unresolved or no_site_source, as sent. */
        val outcome: String,
        val detail: String?,
        /** This site told the origin it can source nothing, so the origin holds it to pull. */
        val reportedUnsourced: Boolean,
    )

    /** The acquire op this site published, and what each sibling did with it. */
    data class Published(
        val releaseName: String?,
        /** The sibling sites that reported they could not source it. */
        val unsourcedBy: List<String>,
        /** Each sibling's own answer, asked when the status was read (heyarr-core#924). */
        val siblings: List<Sibling>,
    )

    /** One sibling's outcome for the published op. */
    data class Sibling(
        /** The node's name for the sibling. */
        val peer: String,
        /** The site the sibling says it is; null when it did not answer. */
        val site: String?,
        val reachable: Boolean,
        /** The sibling's outcome, `pending` before it has evaluated the op, `unknown` when it could not be asked. */
        val outcome: String,
        val detail: String?,
        /** The §64 state of the sibling's want for it (`DOWNLOADING`, …), when the op reached one. */
        val state: String?,
    )

    /**
     * The lines a detail screen shows under the want, worded once for both apps. Outcome codes
     * stay as sent (with `_` read as a space) and the node's detail sentence is verbatim; nothing
     * is inferred. Empty for a want with neither block.
     */
    fun lines(): List<String> = buildList {
        fanOut?.let { f ->
            add("From site ${f.originSite}'s choice" + (f.releaseName?.let { ": $it" } ?: ""))
            add("Here: ${words(f.outcome)}" + (f.detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
            if (f.reportedUnsourced) add("Reported to site ${f.originSite}: held there for this site to pull")
        }
        published?.let { p ->
            add("Published to siblings" + (p.releaseName?.let { ": $it" } ?: ""))
            for (s in p.siblings) add(s.line())
            for (site in p.unsourcedBy) add("Site $site could not source this; held for it to pull")
        }
    }

    private fun Sibling.line(): String {
        val who = site?.let { "Site $it" } ?: peer
        if (outcome == "unknown") return "$who: unknown" + (if (reachable) "" else " (unreachable)")
        return "$who: ${words(outcome)}" +
            (state?.let { " · ${words(it.lowercase())}" } ?: "") +
            (detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
    }

    private fun words(code: String) = code.replace('_', ' ')
}

object WantAcquisitionJson {
    fun parse(body: String): WantAcquisition? = JsonScan.rootObject(body)?.let { root ->
        JsonScan.stringField(root, "desired_item_id")?.let { id ->
            WantAcquisition(id, fanOut(root), published(root))
        }
    }

    private fun fanOut(root: String): WantAcquisition.FanOut? = JsonScan.objectAt(root, "fan_out")?.let { f ->
        JsonScan.stringField(f, "origin_site")?.let { origin ->
            WantAcquisition.FanOut(
                originSite = origin,
                releaseName = JsonScan.stringField(f, "release_name"),
                outcome = JsonScan.stringField(f, "outcome") ?: "unknown",
                detail = JsonScan.stringField(f, "detail"),
                reportedUnsourced = JsonScan.boolField(f, "reported_unsourced") ?: false,
            )
        }
    }

    private fun published(root: String): WantAcquisition.Published? = JsonScan.objectAt(root, "published")?.let { p ->
        WantAcquisition.Published(
            releaseName = JsonScan.stringField(p, "release_name"),
            unsourcedBy = JsonScan.objectsOf(p, listOf("unsourced_by")).mapNotNull {
                JsonScan.stringField(it, "site")
            },
            siblings = JsonScan.objectsOf(p, listOf("siblings")).mapNotNull { s ->
                (JsonScan.stringField(s, "peer") ?: JsonScan.stringField(s, "peer_id"))?.let { peer ->
                    WantAcquisition.Sibling(
                        peer = peer,
                        site = JsonScan.stringField(s, "site"),
                        reachable = JsonScan.boolField(s, "reachable") ?: false,
                        outcome = JsonScan.stringField(s, "outcome") ?: "unknown",
                        detail = JsonScan.stringField(s, "detail"),
                        state = JsonScan.stringField(s, "state"),
                    )
                }
            },
        )
    }
}

/**
 * The fan-out lines for [want]: its acquisition status's when that has loaded, else just where it
 * came from, which the want list already carries (`origin_site`), so a sibling's want is never
 * unexplained while the status read is in flight or failed.
 */
fun fanOutLines(want: DesiredItem, acquisition: WantAcquisition?): List<String> {
    val lines = acquisition?.lines().orEmpty()
    if (acquisition?.fanOut != null) return lines
    return listOfNotNull(want.originSite?.let { "From site $it's choice" }) + lines
}
