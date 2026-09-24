package one.rarebit.heyarr.mobile.net
import one.rarebit.heyarr.core.net.JsonEscapes

/**
 * Reads the human-facing message out of a heyarr error body. heyarr renders API
 * errors as RFC 9457 problem details (`{ "type", "title", "status", "detail" }` —
 * heyarr-core `internal/api/problem`), and the **`detail`** is the contract: it is
 * where a server refusal explains itself ("following this source is not implemented
 * yet — Phase 1 follows tv_series only …", "removing the archive is not implemented
 * yet …", "a followed source needs a feed identity …").
 *
 * Surfacing that string — rather than a bare "HTTP 400" — is what turns a Phase-1
 * refusal into a *user-visible state*, per the M12 wiring brief. Dependency-free
 * (no `org.json`, which is stubbed in unit tests), mirroring the tolerant scanning
 * in [one.rarebit.heyarr.mobile.search.SearchResultsJson].
 */
object ProblemDetail {

    /** The `detail` string from a problem-details body, or null when absent/empty/non-JSON. */
    fun of(body: String): String? {
        val needle = "\"detail\""
        var i = body.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        while (i < body.length && (body[i] == ' ' || body[i] == '\t' || body[i] == ':')) i++
        if (i >= body.length || body[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    i = JsonEscapes.append(sb, body, i)
                }

                c == '"' -> return sb.toString().ifBlank { null }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /**
     * A message for a failed request: the server's [of] detail when present, else a
     * terse fallback naming [status]. [fallbackLabel] prefixes the fallback ("follow",
     * "search", "unfollow") so the surfaced text reads as an action outcome.
     */
    fun message(body: String, status: Int, fallbackLabel: String): String =
        of(body) ?: "$fallbackLabel failed: HTTP $status"
}
