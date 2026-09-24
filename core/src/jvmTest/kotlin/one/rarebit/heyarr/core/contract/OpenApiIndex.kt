package one.rarebit.heyarr.core.contract

/**
 * The questions the contract test asks of heyarr-core's OpenAPI document: "what is the
 * success-response schema of `GET /api/v1/works`?" and "which property names can appear
 * anywhere in that response?".
 *
 * A **target** is either an operation — `"GET /api/v1/works"`, whose 2xx `application/json`
 * response schemas are used — or a named component schema — `"#Problem"`. Naming the
 * operation rather than the schema is deliberate: it also fails when the route itself is
 * removed or stops returning JSON, which is drift too.
 *
 * [fieldsOf] is the set of property names **reachable** from the targets: `$ref`, `allOf` /
 * `oneOf` / `anyOf`, array `items` and `additionalProperties` are all followed. That is the
 * right granularity for a static check. A parser's key reads cannot be attributed to a
 * nesting level from its source (a read on `it` inside `objectAt(obj, "artwork")?.let {}` is
 * a read of the nested `ArtworkRef`), so the question it answers is "does this name exist
 * anywhere in what this endpoint returns?" — which a renamed or removed field fails.
 */
internal class OpenApiIndex(private val root: Map<String, Any?>) {

    companion object {
        fun load(json: String): OpenApiIndex {
            @Suppress("UNCHECKED_CAST")
            val root = requireNotNull(SpecJson.parse(json) as? Map<String, Any?>) { "spec is not a JSON object" }
            return OpenApiIndex(root)
        }

        private val METHODS = setOf("GET", "PUT", "POST", "PATCH", "DELETE")
    }

    /** The schemas behind [target]. Throws [IllegalArgumentException] when the spec no longer has it. */
    fun schemasFor(target: String): List<Any?> {
        if (target.startsWith("#")) {
            val schema = map(map(root["components"])?.get("schemas"))?.get(target.substring(1))
            requireNotNull(schema) { "no component schema ${target.substring(1)}" }
            return listOf(schema)
        }
        val method = target.substringBefore(' ')
        val path = target.substringAfter(' ')
        require(method in METHODS && path.startsWith("/")) {
            "bad target '$target' (want \"GET /api/v1/…\" or \"#Schema\")"
        }
        val op = map(map(map(root["paths"])?.get(path))?.get(method.lowercase()))
        requireNotNull(op) { "no operation $method $path" }
        val schemas = map(op["responses"]).orEmpty()
            .filterKeys { it.startsWith("2") }
            .values
            .flatMap { response ->
                map(map(resolve(response))?.get("content")).orEmpty()
                    .filterKeys { "json" in it }
                    .values
                    .mapNotNull { map(it)?.get("schema") }
            }
        require(schemas.isNotEmpty()) { "$method $path has no 2xx JSON response" }
        return schemas
    }

    /** Every property name reachable from any of [targets] (see the class comment). */
    fun fieldsOf(targets: List<String>): Set<String> {
        val out = HashSet<String>()
        val seenRefs = HashSet<String>()
        val stack = ArrayDeque(targets.flatMap { schemasFor(it) })
        while (stack.isNotEmpty()) {
            val node = unseen(stack.removeLast(), seenRefs) ?: continue
            for (k in listOf("allOf", "oneOf", "anyOf")) (node[k] as? List<*>)?.let { stack.addAll(it) }
            node["items"]?.let { stack.add(it) }
            (node["additionalProperties"] as? Map<*, *>)?.let { stack.add(it) }
            map(node["properties"])?.forEach { (name, schema) ->
                out.add(name)
                stack.add(schema)
            }
        }
        return out
    }

    /** [raw] resolved, or null when it is not a schema or is a `$ref` already walked (schemas recurse). */
    private fun unseen(raw: Any?, seenRefs: MutableSet<String>): Map<String, Any?>? {
        val ref = map(raw)?.get("\$ref") as? String
        return if (ref != null && !seenRefs.add(ref)) null else map(resolve(raw))
    }

    /** Follow a local `$ref` (`#/components/schemas/Work`) to the node it names, repeatedly. */
    private fun resolve(node: Any?): Any? {
        var n = node
        var hops = 0
        while (true) {
            val ref = map(n)?.get("\$ref") as? String ?: return n
            require(ref.startsWith("#/") && hops++ < 32) { "unresolvable \$ref $ref" }
            n = ref.removePrefix("#/").split('/').fold(root as Any?) { acc, part ->
                map(acc)?.get(part.replace("~1", "/").replace("~0", "~"))
            }
            requireNotNull(n) { "dangling \$ref $ref" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
}
