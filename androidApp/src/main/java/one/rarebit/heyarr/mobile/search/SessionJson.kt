package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.core.net.JsonEscapes

/**
 * Dependency-free parser for heyarr's `GET /api/v1/session` response
 * (`{ kind, principal_id?, device_key?, scopes:[…], can_write, management_authorized }`,
 * heyarr-core `internal/api/resources/session.go` `SessionView`).
 *
 * Kept JVM-testable (no `org.json`, which is stubbed in unit tests) for the same
 * reason as [SearchResultsJson], and using the same primitives. `can_write` is read
 * directly rather than re-derived from `scopes`, so this client never has to know the
 * server's scope vocabulary to answer "can I follow?".
 */
object SessionJson {

    /** Parse a session-introspection body into a [SessionAuthority], or null if unparseable. */
    fun parse(body: String): SessionAuthority? {
        val obj = body.trim()
        if (!obj.startsWith("{")) return null
        return SessionAuthority(
            kind = stringField(obj, "kind") ?: "",
            principalId = stringField(obj, "principal_id") ?: "",
            deviceKey = stringField(obj, "device_key") ?: "",
            scopes = stringArrayField(obj, "scopes"),
            canWrite = boolField(obj, "can_write") ?: false,
            managementAuthorized = boolField(obj, "management_authorized") ?: false,
        )
    }

    private fun stringField(json: String, key: String): String? {
        var i = valueStart(json, key) ?: return null
        if (json.startsWith("null", i)) return null
        if (json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    i = JsonEscapes.append(sb, json, i)
                }

                c == '"' -> return sb.toString()

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun boolField(json: String, key: String): Boolean? {
        val i = valueStart(json, key) ?: return null
        return when {
            json.startsWith("true", i) -> true
            json.startsWith("false", i) -> false
            else -> null
        }
    }

    /** Read a flat array of JSON strings (`["a","b"]`) into a list; empty when absent. */
    private fun stringArrayField(json: String, key: String): List<String> {
        val needle = "\"$key\""
        var at = json.indexOf(needle)
        if (at < 0) return emptyList()
        val open = json.indexOf('[', at)
        if (open < 0) return emptyList()
        val out = ArrayList<String>()
        var i = open + 1
        while (i < json.length) {
            when (json[i]) {
                ']' -> return out

                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < json.length && json[i] != '"') {
                        if (json[i] == '\\' && i + 1 < json.length) {
                            sb.append(json[i + 1])
                            i += 2
                        } else {
                            sb.append(json[i])
                            i++
                        }
                    }
                    out.add(sb.toString())
                    i++
                }

                else -> i++
            }
        }
        return out
    }

    /** Index just past `"key" :` whitespace, at the first char of the value; null if absent. */
    private fun valueStart(json: String, key: String): Int? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        return if (i >= json.length) null else i
    }
}
