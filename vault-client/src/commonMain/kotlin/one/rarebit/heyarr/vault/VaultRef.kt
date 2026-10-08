package one.rarebit.heyarr.vault

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A reference into an owner's encrypted vault, as an outside system that never decrypts holds it
 * (heyarr ADR-0104) — the Kotlin twin of heyarr-core's `internal/personalstate/vaultref`:
 *
 * ```
 * hv1:<space_uuid>                a collection: one vault space
 * hv1:<space_uuid>/<object_uuid>  one sealed object in it
 * ```
 *
 * The grammar is the referring system's contract, `^hv1:[0-9a-f-]{36}(/[0-9a-f-]{36})?$`, and
 * [parse] accepts no string that pattern refuses. Like Go it is stricter in one way only: each
 * part must also be a canonical lowercase UUID. Every ref [newObject] returns matches both.
 *
 * An object lives in the space's drive at [OBJECT_DIR]`/<object_uuid>.json`. The name is random,
 * so nothing about the object's content reaches the drive path, which is itself encrypted state
 * the server never sees.
 */
data class VaultRef(val space: String, val objectId: String? = null) {
    init {
        require(canonical(space)) { "vaultref: \"$space\" is not a space id" }
        require(objectId == null || canonical(objectId)) { "vaultref: \"$objectId\" is not an object id" }
    }

    /** Whether this names one object rather than a collection. */
    val isObject: Boolean get() = objectId != null

    /** The drive path of the object this names, or null for a collection ref. */
    val path: String? get() = objectId?.let { "$OBJECT_DIR/$it.json" }

    /** The wire form. */
    override fun toString(): String = if (objectId == null) SCHEME + space else "$SCHEME$space/$objectId"

    companion object {
        /** The version prefix of every ref. */
        const val SCHEME = "hv1:"

        /** The drive directory sealed objects live in. */
        const val OBJECT_DIR = ".jumpdrive/objects"

        // The referring contract's grammar, verbatim.
        private val PATTERN = Regex("^hv1:([0-9a-f-]{36})(?:/([0-9a-f-]{36}))?$")
        private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** [s] parsed, or [VaultRefException.Malformed]. */
        fun parse(s: String): VaultRef = parseOrNull(s) ?: throw VaultRefException.Malformed()

        /** [s] parsed, or null when it is not a vault ref. */
        fun parseOrNull(s: String): VaultRef? {
            val m = PATTERN.matchEntire(s) ?: return null
            val space = m.groupValues[1]
            val obj = m.groupValues[2].ifEmpty { null }
            val wellFormed = canonical(space) && (obj == null || canonical(obj))
            return if (wellFormed) VaultRef(space, obj) else null
        }

        /**
         * A collection ref (`hv1:<space>`) or a bare space id → the space id (Go `ParseSpace`). An
         * object ref is refused: it names more than a space.
         */
        fun parseSpace(s: String): String {
            if (canonical(s)) return s
            val r = parse(s)
            if (r.isObject) throw VaultRefException.Malformed("vaultref: \"$s\" names an object, not a collection")
            return r.space
        }

        /** A ref for a fresh object in [space]: a random (version 4) UUID, carrying no time and no content. */
        @OptIn(ExperimentalUuidApi::class)
        fun newObject(space: String): VaultRef = VaultRef(space, Uuid.random().toString())

        /** Whether [s] is a UUID in its canonical lowercase form (Go: `uuid.Parse(s).String() == s`). */
        fun canonical(s: String): Boolean = CANONICAL_UUID.matches(s)
    }
}

/**
 * Why a ref-addressed read or write failed — the same four outcomes heyarr-core's
 * `vault get-ref` / `put-ref` exit with, so a client renders the same reasons the executor
 * reports. No message ever carries any of the object's plaintext.
 */
sealed class VaultRefException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not a vault ref, or the wrong kind (a collection where an object was needed). CLI exit 1. */
    class Malformed(
        message: String = "vaultref: a vault ref is hv1:<space uuid> or hv1:<space uuid>/<object uuid>, lowercase",
    ) : VaultRefException(message)

    /**
     * The space is not visible to this credential: no grant, a revoked or expired one, or no such
     * space — the node answers all of these alike (ADR-0104). CLI exit 4.
     */
    class Forbidden(val status: Int, message: String, cause: Throwable? = null) : VaultRefException(message, cause)

    /**
     * The space is visible but this device cannot decrypt it: no copy of its key is wrapped for
     * this recipient, or the copy or the key history does not open. CLI exit 5.
     */
    class Unwrap(message: String, cause: Throwable? = null) : VaultRefException(message, cause)

    /**
     * The ref names no object, one with conflicting versions, or one whose blobs this node cannot
     * serve (replication lag, storage loss). CLI exit 6.
     */
    class Absent(message: String, cause: Throwable? = null) : VaultRefException(message, cause)

    /**
     * A fetched blob does not hash to the id it was fetched by: the node or its storage served
     * other bytes (a substituted manifest or tampered content). Distinct from [Absent]: retrying
     * elsewhere may help, but the bytes must never be trusted. CLI: no equivalent yet.
     */
    class Integrity(message: String, cause: Throwable? = null) : VaultRefException(message, cause)

    /**
     * A write refused because the space rotated after it was opened and this device cannot open
     * it at the new key epoch: sealing under the retired key would let a recipient that rotation
     * revoked read the object (ADR-0103). Nothing was written.
     */
    class StaleEpoch(message: String, cause: Throwable? = null) : VaultRefException(message, cause)

    /** An object that is not a versioned JSON envelope (or too large), refused before or after sealing. */
    class InvalidObject(message: String) : VaultRefException(message)
}
