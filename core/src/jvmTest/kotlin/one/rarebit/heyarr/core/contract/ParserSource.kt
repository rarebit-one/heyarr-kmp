package one.rarebit.heyarr.core.contract

/**
 * One field read in a parser's source: [keys] are the alternatives the read accepts (one
 * for `stringField(o, "title")`; several for `firstString(o, listOf("title", "name"))` or an
 * elvis chain `intField(o, "items_known") ?: intField(o, "known")`), and [declaration] is the
 * top-level declaration (object / class / fun / val) the read sits in.
 */
internal data class KeyRead(val declaration: String, val file: String, val line: Int, val keys: List<String>) {
    override fun toString() = "$file:$line $declaration reads ${keys.joinToString("|") { "\"$it\"" }}"
}

/**
 * Static extraction of the JSON keys each parser reads, straight from the Kotlin source.
 *
 * Why static, not a recording `JsonScan` at test time: a recording would only see the
 * parsers a test happens to drive, with fixtures someone wrote by hand, and half the parsers
 * live in `:androidApp`, whose unit tests cannot run on every dev box. The source is always
 * there, for every module, and a key a parser reads is — in this codebase's style — a string
 * literal at the call site.
 *
 * What counts as a read: a call to one of [READERS] (`JsonScan.stringField(obj, "id")`, or
 * an unqualified local helper of the same name, as `SessionJson` has), with the literal keys
 * in every argument after the first. A key list held in a same-file constant
 * (`private val TITLE_KEYS = listOf("title", "name")`) is resolved; a key passed in a
 * variable is dynamic and skipped. `arrayOf` counts only as `JsonScan.arrayOf`, since the
 * bare name is Kotlin's.
 *
 * Comments and string contents are masked before any matching, so a brace, paren or call
 * inside a string or KDoc never confuses the scan. Declarations are the column-0 ones
 * (ktlint enforces the indentation that makes that reliable); every read belongs to the
 * nearest one above it.
 */
internal object ParserSource {

    val READERS = setOf(
        "stringField", "longField", "intField", "doubleField", "boolField", "objectAt",
        "stringMap", "stringArray", "stringArrayField", "firstString", "firstInt", "objectsOf",
        "arrayOf", "valueStart",
    )

    private val DECLARATION = Regex(
        """^(?:@[\w.]+(?:\([^)\n]*\))?[ \t]+)*(?:[a-z]+[ \t]+)*?""" +
            """(?:object|class|interface|fun|val|var|typealias)[ \t]+(?:<[^>\n]*>\s*)?([\w.]+)""",
        RegexOption.MULTILINE,
    )
    private val CALL = Regex("""(?<!\w)(?:(\w+)\s*\.\s*)?(\w+)\s*\(""")
    private val CONSTANT = Regex("""\bval\s+(\w+)\s*(?::[^=\n]+)?=\s*(?:(listOf|setOf|arrayOf)\s*\(|(?="))""")
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    private val ELVIS = Regex("""\s*\?:\s*""")

    fun reads(file: String, source: String): List<KeyRead> {
        val masked = KotlinMask.mask(source)
        val decls = DECLARATION.findAll(masked.code)
            .map { it.range.first to it.groupValues[1].substringAfterLast('.') }
            .toList()
        val constants = constants(masked)
        val resolve = { name: String, at: Int -> resolve(name, at, constants, decls) }
        val calls = CALL.findAll(masked.code)
            .filter { it.groupValues[2] in READERS }
            .filter { it.groupValues[2] != "arrayOf" || it.groupValues[1] == "JsonScan" }
            .map { call(masked, resolve, it) }
            .toList()
        return group(masked.code, calls).mapNotNull { group ->
            val keys = group.flatMap { it.keys }.distinct()
            val start = group.first().start
            val decl = decls.lastOrNull { it.first <= start }?.second
            if (keys.isEmpty() || decl == null) null else KeyRead(decl, file, lineOf(source, start), keys)
        }
    }

    private class Call(val start: Int, val end: Int, val keys: List<String>)

    private fun call(masked: KotlinMask.Masked, resolve: (String, Int) -> List<String>, m: MatchResult): Call {
        val open = m.range.last
        val (close, args) = arguments(masked.code, open)
        val keys = args.drop(1).flatMap { arg ->
            val literal = masked.literalsIn(arg)
            val name = masked.code.substring(arg).trim()
            literal.ifEmpty { if (IDENTIFIER.matches(name)) resolve(name, m.range.first) else emptyList() }
        }
        return Call(m.range.first, close, keys)
    }

    /** The index of the `)` matching [open], and each top-level argument's range. */
    private fun arguments(code: String, open: Int): Pair<Int, List<IntRange>> {
        val args = ArrayList<IntRange>()
        var depth = 0
        var argStart = open + 1
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth == 0) break
                ',' -> if (depth == 1) args.add(argStart until i).also { argStart = i + 1 }
            }
            i++
        }
        args.add(argStart until i)
        return i to args
    }

    private class Constant(val at: Int, val name: String, val keys: List<String>)

    /** Same-file `val NAME = listOf("a", "b")` / `val NAME = "a"` constants. */
    private fun constants(masked: KotlinMask.Masked): List<Constant> = CONSTANT.findAll(masked.code).map { m ->
        val valueStart = m.range.last + 1
        val range = if (m.groupValues[2].isEmpty()) {
            valueStart..valueStart
        } else {
            arguments(masked.code, m.range.last).let { (close, _) -> valueStart until close }
        }
        Constant(m.range.first, m.groupValues[1], masked.literalsIn(range))
    }.toList()

    /**
     * A constant used at [at]: the one of that name in the same top-level declaration wins
     * (two objects in one file may each have an `ENVELOPE_KEYS`), then the nearest above.
     */
    private fun resolve(
        name: String,
        at: Int,
        constants: List<Constant>,
        decls: List<Pair<Int, String>>,
    ): List<String> {
        val named = constants.filter { it.name == name }
        val from = decls.lastOrNull { it.first <= at }?.first ?: 0
        val until = decls.firstOrNull { it.first > at }?.first ?: Int.MAX_VALUE
        val pick = named.firstOrNull { it.at in from until until }
            ?: named.lastOrNull { it.at < at }
            ?: named.firstOrNull()
        return pick?.keys.orEmpty()
    }

    /** Calls joined by `?:` read the same field under alternative names — one read. */
    private fun group(code: String, calls: List<Call>): List<List<Call>> {
        val byStart = calls.associateBy { it.start }
        val joined = HashSet<Int>()
        val out = ArrayList<List<Call>>()
        for (c in calls) {
            if (c.start in joined) continue
            val chain = arrayListOf(c)
            var next = elvisNext(code, c.end, byStart)
            while (next != null) {
                chain.add(next)
                joined.add(next.start)
                next = elvisNext(code, next.end, byStart)
            }
            out.add(chain)
        }
        return out
    }

    private fun elvisNext(code: String, close: Int, byStart: Map<Int, Call>): Call? {
        if (close + 1 >= code.length) return null
        return ELVIS.matchAt(code, close + 1)?.let { byStart[it.range.last + 1] }
    }

    private fun lineOf(source: String, index: Int): Int = 1 + source.subSequence(0, index).count { it == '\n' }
}

/**
 * Masks a Kotlin source for scanning: comments and the contents of string and char literals
 * become spaces (lengths and newlines kept, so offsets stay valid), and every plain string
 * literal is recorded with its decoded value. String templates (`"${f("x")}"`) are walked
 * as code so a nested string cannot end the outer one early; such a literal has no value.
 */
internal object KotlinMask {

    class Literal(val start: Int, val value: String?)

    class Masked(val code: String, private val literals: List<Literal>) {
        fun literalsIn(range: IntRange): List<String> = literals.filter { it.start in range }.mapNotNull { it.value }
    }

    fun mask(src: String): Masked {
        val lexer = Lexer(src)
        var i = 0
        while (i < src.length) i = lexer.step(i)
        return Masked(lexer.out.toString(), lexer.literals)
    }

    private class Lexer(private val src: String) {
        val out = StringBuilder(src)
        val literals = ArrayList<Literal>()

        fun step(i: Int): Int = when {
            src.startsWith("//", i) -> blank(i, src.indexOf('\n', i).let { if (it < 0) src.length else it })
            src.startsWith("/*", i) -> blank(i, blockCommentEnd(i))
            src.startsWith("\"\"\"", i) -> string(i, raw = true)
            src[i] == '"' -> string(i, raw = false)
            src[i] == '\'' -> charLiteral(i)
            else -> i + 1
        }

        private fun blank(from: Int, to: Int): Int {
            for (k in from until to) if (out[k] != '\n') out.setCharAt(k, ' ')
            return to
        }

        /** Kotlin block comments nest. */
        private fun blockCommentEnd(start: Int): Int {
            var depth = 0
            var i = start
            while (i < src.length) {
                when {
                    src.startsWith("/*", i) -> depth++.also { i++ }
                    src.startsWith("*/", i) -> if (--depth == 0) return i + 2 else i++
                }
                i++
            }
            return src.length
        }

        private fun string(start: Int, raw: Boolean): Int {
            val q = if (raw) 3 else 1
            var i = start + q
            var templated = false
            while (i < src.length && !closesAt(i, raw)) {
                val next = if (!raw && src[i] == '\\') i + 2 else templateAt(i) ?: (i + 1)
                if (next != i + 1 && src[i] == '$') templated = true
                i = next
            }
            if (raw) while (i + q < src.length && src[i + q] == '"') i++ // `""""` ends on the last three
            val end = minOf(i + q, src.length)
            val body = src.substring(start + q, maxOf(start + q, end - q))
            literals.add(
                Literal(
                    start,
                    if (templated) {
                        null
                    } else if (raw) {
                        body
                    } else {
                        unescape(body)
                    },
                ),
            )
            blank(start + q, end - q)
            return end
        }

        /** Past a `${…}` or `$name` template starting at [i], or null when none starts there. */
        private fun templateAt(i: Int): Int? = when {
            src[i] != '$' || i + 1 >= src.length -> null
            src[i + 1] == '{' -> templateEnd(i + 2)
            src[i + 1].isJavaIdentifierStart() -> i + 1
            else -> null
        }

        private fun closesAt(i: Int, raw: Boolean) = if (raw) src.startsWith("\"\"\"", i) else src[i] == '"'

        /** The index just past the `}` closing a `${` template that starts at [from]. */
        private fun templateEnd(from: Int): Int {
            var depth = 1
            var i = from
            while (i < src.length) {
                when (src[i]) {
                    '{' -> depth++.also { i++ }
                    '}' -> if (--depth == 0) return i + 1 else i++
                    else -> i = step(i)
                }
            }
            return src.length
        }

        private fun charLiteral(start: Int): Int {
            var i = start + 1
            if (i < src.length && src[i] == '\\') i++
            i++
            while (i < src.length && src[i] != '\'') i++
            blank(start + 1, i)
            return minOf(i + 1, src.length)
        }

        private fun unescape(s: String): String = if ('\\' !in s) {
            s
        } else {
            Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(s) { m ->
                val e = m.groupValues[1]
                when (e[0]) {
                    'n' -> "\n"
                    't' -> "\t"
                    'r' -> "\r"
                    'u' -> e.substring(1).toInt(16).toChar().toString()
                    else -> e
                }
            }
        }
    }
}
