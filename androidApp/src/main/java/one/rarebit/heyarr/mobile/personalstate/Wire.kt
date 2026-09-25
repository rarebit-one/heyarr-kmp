package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.core.vault.PersonalStateId
import java.util.Base64

/**
 * The opaque wire records of the personal-state sync surface (heyarr-core
 * `internal/api/personalstate`), as this device sends and receives them. Every
 * `ByteArray` here is base64 on the wire (Go's `[]byte` JSON) and stays opaque to
 * the peer (Invariant 6); only [SpaceSession] decrypts, on the device.
 */
internal data class SpaceInfo(val id: String, val kind: String, val createdAt: String)

internal data class WrappedKeyEntry(val recipient: String, val wrapped: ByteArray)

/**
 * One encrypted change as it crosses the peer surface. The id is content-addressed
 * over the space, the parents and the ciphertext; [mint] computes it exactly as the
 * node does (so a write is accepted) and [validate] re-checks a fetched change
 * before it is decrypted (Invariant 1).
 */
internal data class EncryptedChange(
    val spaceId: String,
    val changeId: String,
    val parents: List<String>,
    val ciphertext: ByteArray,
) {
    /** Serialise for `POST /spaces/{id}/changes`. */
    fun encode(): String = buildString {
        append("{\"space_id\":").append(PsJson.goJsonString(spaceId))
        append(",\"change_id\":").append(PsJson.goJsonString(changeId))
        append(",\"parents\":").append(jsonStringArray(parents))
        append(",\"ciphertext\":").append(PsJson.goJsonString(Base64.getEncoder().encodeToString(ciphertext)))
        append('}')
    }

    fun validate(): Boolean = changeId.isNotEmpty() && spaceId.isNotEmpty() && ciphertext.isNotEmpty() &&
        changeId == PersonalStateId.changeId(spaceId, parents, ciphertext)

    companion object {
        /** Mint a change at the given causal [parents] (the space's current heads). */
        fun mint(spaceId: String, parents: List<String>, ciphertext: ByteArray): EncryptedChange {
            val canonical = PersonalStateId.canonical(parents)
            return EncryptedChange(
                spaceId,
                PersonalStateId.changeId(spaceId, canonical, ciphertext),
                canonical,
                ciphertext,
            )
        }

        fun parse(obj: String): EncryptedChange = EncryptedChange(
            JsonScan.stringField(obj, "space_id") ?: "",
            JsonScan.stringField(obj, "change_id") ?: "",
            PsJson.stringArray(obj, "parents"),
            decodeB64(JsonScan.stringField(obj, "ciphertext")),
        )
    }
}

internal data class EncryptedSnapshot(
    val spaceId: String,
    val snapshotId: String,
    val frontier: List<String>,
    val ciphertext: ByteArray,
) {
    fun validate(): Boolean = snapshotId.isNotEmpty() && spaceId.isNotEmpty() && ciphertext.isNotEmpty() &&
        snapshotId == PersonalStateId.snapshotId(spaceId, frontier, ciphertext)

    companion object {
        fun parse(obj: String): EncryptedSnapshot = EncryptedSnapshot(
            JsonScan.stringField(obj, "space_id") ?: "",
            JsonScan.stringField(obj, "snapshot_id") ?: "",
            PsJson.stringArray(obj, "frontier"),
            decodeB64(JsonScan.stringField(obj, "ciphertext")),
        )
    }
}

/** The causal frontier of a change set plus an optional snapshot frontier — the parents a new change takes. */
internal object Reconcile {
    fun heads(changes: List<EncryptedChange>, snapshotFrontier: List<String> = emptyList()): List<String> {
        val ids = HashSet<String>()
        val referenced = HashSet<String>()
        for (c in changes) {
            ids.add(c.changeId)
            referenced.addAll(c.parents)
        }
        // A snapshot's frontier ids are known heads too, unless a fetched change names them as a parent.
        for (f in snapshotFrontier) ids.add(f)
        return ids.filter { it !in referenced }.sorted()
    }
}

private fun jsonStringArray(xs: List<String>): String =
    if (xs.isEmpty()) "[]" else xs.joinToString(",", "[", "]") { PsJson.goJsonString(it) }

private fun decodeB64(s: String?): ByteArray = if (s.isNullOrEmpty()) ByteArray(0) else Base64.getDecoder().decode(s)
