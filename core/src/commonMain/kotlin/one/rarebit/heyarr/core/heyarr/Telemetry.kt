package one.rarebit.heyarr.core.heyarr

import one.rarebit.heyarr.core.net.JsonScan

/** `GET /api/v1/session` — what this credential is and may do. */
data class SessionInfo(
    val kind: String,
    val principalId: String?,
    val scopes: List<String>,
    val canWrite: Boolean,
    val managementAuthorized: Boolean,
    /** The enrolled device key a `Device` credential names (`device_key`); null for other kinds. */
    val deviceKey: String? = null,
)

object SessionInfoJson {
    fun parse(body: String): SessionInfo? {
        val o = JsonScan.rootObject(body) ?: return null
        return SessionInfo(
            kind = JsonScan.stringField(o, "kind") ?: "unknown",
            principalId = JsonScan.stringField(o, "principal_id"),
            scopes =
            JsonScan.arrayOf(o, listOf("scopes"))?.let { one.rarebit.heyarr.core.net.JsonArrays.parseStrings(it) }
                ?: emptyList(),
            canWrite = JsonScan.boolField(o, "can_write") ?: false,
            managementAuthorized = JsonScan.boolField(o, "management_authorized") ?: false,
            deviceKey = JsonScan.stringField(o, "device_key"),
        )
    }
}

/** `GET /api/v1/providers` — each configured indexer / downloader / metadata provider and whether it answered. */
data class ProviderInfo(
    val name: String,
    val capabilities: List<String>,
    val healthy: Boolean,
    val detail: String?,
    val version: String?,
    val checkedAt: String?,
)

object ProviderJson {
    fun list(body: String): List<ProviderInfo> =
        JsonScan.objectsOf(body, listOf("providers", "items")).mapNotNull { p ->
            ProviderInfo(
                name = JsonScan.stringField(p, "name") ?: return@mapNotNull null,
                capabilities =
                JsonScan.arrayOf(p, listOf("capabilities"))?.let {
                    one.rarebit.heyarr.core.net.JsonArrays.parseStrings(it)
                }
                    ?: emptyList(),
                healthy = JsonScan.boolField(p, "healthy") ?: false,
                detail = JsonScan.stringField(p, "detail"),
                version = JsonScan.stringField(p, "version"),
                checkedAt = JsonScan.stringField(p, "checked_at"),
            )
        }
}

/** `GET /api/v1/capabilities` — what the node's workers have proved they can do. */
data class Capabilities(val available: List<String>, val holders: List<CapabilityHolder>)
data class CapabilityHolder(
    val peerName: String,
    val workerId: String,
    val capabilities: List<String>,
    val expiresAt: String?,
)

object CapabilitiesJson {
    fun parse(body: String): Capabilities {
        val root = JsonScan.rootObject(body) ?: return Capabilities(emptyList(), emptyList())
        val available =
            JsonScan.arrayOf(root, listOf("available"))?.let { one.rarebit.heyarr.core.net.JsonArrays.parseStrings(it) }
                ?: emptyList()
        val holders = JsonScan.objectsOf(root, listOf("holders")).map { h ->
            CapabilityHolder(
                peerName = JsonScan.stringField(h, "peer_name") ?: "?",
                workerId = JsonScan.stringField(h, "worker_id") ?: "?",
                capabilities = JsonScan.objectsOf(h, listOf("capabilities")).mapNotNull {
                    JsonScan.stringField(it, "name")
                },
                expiresAt = JsonScan.stringField(h, "expires_at"),
            )
        }
        return Capabilities(available, holders)
    }
}

/** `GET /api/v1/libraries` — the scanned roots. */
data class LibraryInfo(
    val id: String,
    val name: String,
    val contentType: String?,
    val enabled: Boolean,
    val roots: List<String>,
)

object LibraryInfoJson {
    fun list(body: String): List<LibraryInfo> = JsonScan.objectsOf(body, listOf("items", "libraries")).mapNotNull { l ->
        LibraryInfo(
            id = JsonScan.stringField(l, "id") ?: return@mapNotNull null,
            name = JsonScan.stringField(l, "name") ?: "?",
            contentType = JsonScan.stringField(l, "content_type"),
            enabled = JsonScan.boolField(l, "enabled") ?: true,
            roots = JsonScan.objectsOf(l, listOf("roots")).mapNotNull { JsonScan.stringField(it, "path") },
        )
    }
}

/** `GET /api/v1/jobs` — the queue's recent rows. */
data class JobInfo(
    val id: String,
    val type: String,
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: String?,
)

object JobJson {
    fun list(body: String): List<JobInfo> = JsonScan.objectsOf(body, listOf("items", "jobs")).mapNotNull { j ->
        JobInfo(
            id = JsonScan.stringField(j, "id") ?: return@mapNotNull null,
            type = JsonScan.stringField(j, "type") ?: "?",
            state = JsonScan.stringField(j, "state") ?: "?",
            attempts = JsonScan.intField(j, "attempts") ?: 0,
            lastError = JsonScan.stringField(j, "last_error"),
            updatedAt = JsonScan.stringField(j, "updated_at"),
        )
    }
}
