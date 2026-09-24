package one.rarebit.heyarr.core.contract

import java.io.File

/**
 * A key a mapped parser may read although the spec does not declare it — always with the
 * reason, so the list stays a to-do list rather than a place drift goes to hide.
 */
internal data class Allowance(val parser: String, val keys: List<String>, val why: String)

/**
 * The contract check itself, independent of the real table and sources so it can be tested
 * against a seeded drift (`ContractCheckTest`).
 *
 * [mapping] names, for each parser declaration, the spec targets its reads must come from
 * (see [OpenApiIndex]). A read passes when ANY of its alternative keys is declared by those
 * targets or allowed — a tolerant `firstString(o, listOf("title", "name"))` works as long as
 * one alternative is still sent, and it is exactly the case where none is that breaks.
 */
internal class ContractCheck(
    private val index: OpenApiIndex,
    private val mapping: Map<String, List<String>>,
    private val allowances: List<Allowance>,
) {
    private val fieldCache = HashMap<String, Set<String>>()

    private fun fields(parser: String): Set<String> {
        val targets = mapping.getValue(parser)
        return fieldCache.getOrPut(parser) { index.fieldsOf(targets) }
    }

    private fun allowed(parser: String, key: String) = allowances.any { it.parser == parser && key in it.keys }

    /** "parser reads field X not in schema Y" — the hard failure. */
    fun violations(reads: List<KeyRead>): List<String> = reads
        .filter { it.declaration in mapping }
        .filterNot { r -> r.keys.any { it in fields(r.declaration) || allowed(r.declaration, it) } }
        .map { "$it — not declared by ${mapping.getValue(it.declaration).joinToString(", ")}" }

    /** Mapping targets the spec no longer has (a removed route is drift too). */
    fun missingTargets(): List<String> = mapping.flatMap { (parser, targets) ->
        targets.mapNotNull { t ->
            runCatching { index.schemasFor(t) }.exceptionOrNull()?.let { "$parser → $t: ${it.message}" }
        }
    }

    /** Allowances that no longer excuse anything: the spec now declares the key, or nobody reads it. */
    fun staleAllowances(reads: List<KeyRead>): List<String> = allowances.flatMap { a ->
        if (a.parser !in mapping) return@flatMap listOf("allowance for ${a.parser}, which is not a mapped parser")
        a.keys.mapNotNull { key ->
            when {
                key in fields(a.parser) -> "${a.parser} \"$key\": the spec now declares it — drop the allowance"

                reads.none { it.declaration == a.parser && key in it.keys } ->
                    "${a.parser} \"$key\": no longer read — drop the allowance"

                else -> null
            }
        }
    }

    /**
     * Informational: alternative keys of a tolerant read that the spec never sends while
     * another alternative covers the read (e.g. `"quality_profiles"` beside `"items"`). Dead
     * aliases, not failures — worth pruning when the parser is next touched.
     */
    fun deadAliases(reads: List<KeyRead>): List<String> = reads
        .filter { it.declaration in mapping && it.keys.size > 1 }
        .flatMap { r ->
            val f = fields(r.declaration)
            val covered = r.keys.any { it in f }
            r.keys.filter { covered && it !in f && !allowed(r.declaration, it) }.map { "$r: \"$it\"" }
        }
}

/** The repository's main (non-test) Kotlin sources, read as text from every module. */
internal object RepoSources {

    val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the repository root (settings.gradle.kts) above ${File("").absolutePath}")
    }

    /** Every `.kt` under `<module>/src/<set>/` for each source set whose name does not contain "test". */
    fun mainKotlin(): List<File> = root.listFiles().orEmpty()
        .map { File(it, "src") }
        .filter { it.isDirectory }
        .flatMap { src -> src.listFiles().orEmpty().filter { it.isDirectory && !it.name.contains("test", true) } }
        .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" } }
        .sortedBy { it.path }

    fun reads(): List<KeyRead> = mainKotlin().flatMap { ParserSource.reads(it.relativeTo(root).path, it.readText()) }
}
