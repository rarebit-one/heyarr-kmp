package one.rarebit.heyarr.mobile.personalstate

/**
 * Loads the cross-language parity vectors copied verbatim from heyarr-core
 * (`internal/personalstate/{crdt,protocol}/testdata/vectors`, minted by its
 * parity_test.go `-update`). Regenerate there and re-copy into
 * `app/src/test/resources/personalstate/vectors/` — the same discipline
 * heyarr-core's `internal/deviceauth` uses for the voidbind-go membership vectors.
 */
internal object Vectors {
    fun load(name: String): String = Vectors::class.java.getResourceAsStream("/personalstate/vectors/$name")
        ?.readBytes()?.decodeToString()
        ?: error("missing parity vector resource: $name")
}
