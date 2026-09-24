package one.rarebit.heyarr.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract machinery against a seeded drift: a tiny spec and parser source written here,
 * so a regression in the extraction (a missed read, a mis-grouped alias) cannot silently turn
 * OpenApiContractTest into a test that passes on everything.
 */
class ContractCheckTest {

    private val spec = """{"paths":{"/api/v1/things":{"get":{"responses":{
        "200":{"content":{"application/json":{"schema":{"allOf":[{"${'$'}ref":"#/components/schemas/Page"},
          {"properties":{"items":{"type":"array","items":{"${'$'}ref":"#/components/schemas/Thing"}}}}]}}}},
        "404":{"content":{"application/problem+json":{"schema":{"${'$'}ref":"#/components/schemas/Problem"}}}}}}}},
      "components":{"schemas":{
        "Page":{"properties":{"items":{"type":"array"},"next_cursor":{"type":"string"}}},
        "Thing":{"properties":{"id":{"type":"string"},"title":{"type":"string"},
          "art":{"oneOf":[{"${'$'}ref":"#/components/schemas/Art"},{"type":"null"}]}}},
        "Art":{"properties":{"blob_hash":{"type":"string"}}},
        "Problem":{"properties":{"detail":{"type":"string"}}}}}}"""

    private val source = "package x\n\n" +
        "import one.rarebit.heyarr.core.net.JsonScan\n\n" +
        "/** KDoc mentioning JsonScan.stringField(o, \"not_a_read\") and a brace { */\n" +
        "object ThingJson {\n" +
        "    private val TITLE_KEYS = listOf(\"title\", \"name\")\n" +
        "    fun list(body: String) = JsonScan.objectsOf(body, listOf(\"items\", \"things\")).map { o ->\n" +
        "        val label = \"\${o.length} {\" // a template and a stray brace in a string\n" +
        "        Triple(\n" +
        "            JsonScan.stringField(o, \"id\"),\n" +
        "            JsonScan.firstString(o, TITLE_KEYS),\n" +
        "            JsonScan.objectAt(o, \"art\")?.let { JsonScan.stringField(it, \"blob_hash\") },\n" +
        "        ) to (JsonScan.intField(o, \"count\") ?: JsonScan.intField(o, \"total\")) to label\n" +
        "    }\n" +
        "    val cursor: (String) -> String? = { JsonScan.stringField(it, \"next_cursor\") }\n" +
        "}\n\n" +
        "private fun detail(b: String) = JsonScan.stringField(b, \"detail\")\n"

    private val reads = ParserSource.reads("x/ThingJson.kt", source)

    @Test
    fun `extracts literal keys, same-file constants, alias lists and elvis chains`() {
        assertEquals(
            listOf(
                "ThingJson" to listOf("items", "things"),
                "ThingJson" to listOf("id"),
                "ThingJson" to listOf("title", "name"),
                "ThingJson" to listOf("art"),
                "ThingJson" to listOf("blob_hash"),
                "ThingJson" to listOf("count", "total"),
                "ThingJson" to listOf("next_cursor"),
                "detail" to listOf("detail"),
            ),
            reads.map { it.declaration to it.keys },
        )
        assertEquals(11, reads.first { it.keys == listOf("id") }.line)
    }

    @Test
    fun `resolves refs, allOf and oneOf to every reachable property`() {
        val index = OpenApiIndex.load(spec)
        assertEquals(
            setOf("items", "next_cursor", "id", "title", "art", "blob_hash"),
            index.fieldsOf(listOf("GET /api/v1/things")),
        )
    }

    @Test
    fun `a seeded drift fails and an allowance or a surviving alias covers it`() {
        val index = OpenApiIndex.load(spec)
        val mapping = mapOf("ThingJson" to listOf("GET /api/v1/things"), "detail" to listOf("#Problem"))

        // `count|total` is the seeded drift: neither alternative is in the schema.
        val violations = ContractCheck(index, mapping, emptyList()).violations(reads)
        assertEquals(1, violations.size, violations.joinToString())
        assertTrue("reads \"count\"|\"total\"" in violations.single(), violations.single())

        val allowed = ContractCheck(index, mapping, listOf(Allowance("ThingJson", listOf("total"), "seeded")))
        assertEquals(emptyList(), allowed.violations(reads))
        assertEquals(emptyList(), allowed.staleAllowances(reads))
        // "things" and "name" are dead aliases beside "items" / "title", and only reported.
        assertEquals(2, allowed.deadAliases(reads).size)
    }

    @Test
    fun `stale allowances and missing targets are reported`() {
        val index = OpenApiIndex.load(spec)
        val targets = listOf("GET /api/v1/things", "GET /api/v1/gone")
        val gone = ContractCheck(index, mapOf("ThingJson" to targets), emptyList())
        assertEquals(1, gone.missingTargets().size)
        // "title" is declared by the spec now; "never_read" is not read by anything.
        val stale = ContractCheck(
            index,
            mapOf("ThingJson" to listOf("GET /api/v1/things")),
            listOf(Allowance("ThingJson", listOf("title", "never_read"), "x")),
        )
        assertEquals(2, stale.staleAllowances(reads).size)
    }
}
