package one.rarebit.heyarr.core.mcp

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan

/**
 * heyarr's MCP tool surface over plain HTTP: every semantic action the desktop takes —
 * `search_content`, `want_content`, `explain_release`, `play_here` … — is one
 * `tools/call` JSON-RPC 2.0 request `POST`ed to `/api/v1/mcp` with the same bearer
 * credential the REST reads carry. The endpoint is stateless (no `initialize`
 * handshake, no session header), which was verified against the live node before this
 * was written, so a call is a single round trip.
 *
 * A tool answers in one of two ways and this client keeps them apart on purpose:
 *
 *  - **[McpOutcome.Ok]** — the tool ran. Its result is the JSON text in
 *    `result.content[0].text`, handed back verbatim for a typed reader to scan.
 *  - **[McpOutcome.Refused]** — the tool declined (`error` member). The message names
 *    the rule or reason and is meant to be QUOTED to the person, not paraphrased; the
 *    `data.tool` field says which tool spoke.
 *
 * Anything else — a socket failure, a non-200, a body that is not JSON-RPC — is a
 * [McpTransportException], which the UI reads as "can't reach heyarr" rather than as an
 * answer. Blocking, like every transport call here; run it on `Dispatchers.IO`.
 */
class McpClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    private var nextId = 1L

    /** Call [tool] with [arguments] (nulls dropped — see [JsonWrite]). */
    fun call(tool: String, arguments: Map<String, Any?> = emptyMap()): McpOutcome {
        val id = synchronized(this) { nextId++ }
        val body = JsonWrite.obj(
            linkedMapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "method" to "tools/call",
                "params" to linkedMapOf("name" to tool, "arguments" to arguments),
            ),
        )
        val resp = try {
            http.post(
                url = endpoint(baseUrl),
                body = body,
                contentType = "application/json",
                headers = credential.asHeader() + ("Accept" to "application/json"),
            )
        } catch (e: Exception) {
            // Any transport-layer failure (unreachable host, interrupted call, TLS, …).
            // commonMain has no java.io.IOException / InterruptedException, so catch the
            // common supertype; the per-platform HttpTransport actual raises whatever its
            // stack throws and it is surfaced uniformly as a transport failure.
            throw McpTransportException("heyarr is unreachable: ${e.message ?: e::class.simpleName}", e)
        }
        return parse(tool, resp.status, resp.body)
    }

    /** Pure: turn a raw HTTP status + body into an outcome. Split out so it is unit-tested without a socket. */
    fun parse(tool: String, status: Int, body: String): McpOutcome {
        if (status == 401 || status == 403) {
            throw McpTransportException("heyarr refused the credential (HTTP $status) — check the token in Settings", null, status)
        }
        if (status != 200) {
            throw McpTransportException("heyarr answered HTTP $status to $tool", null, status)
        }
        val root = JsonScan.rootObject(body)
            ?: throw McpTransportException("heyarr answered $tool with something that is not JSON-RPC", null, status)
        JsonScan.objectAt(root, "error")?.let { err ->
            val data = JsonScan.objectAt(err, "data")
            return McpOutcome.Refused(
                McpError(
                    code = JsonScan.intField(err, "code") ?: -1,
                    message = JsonScan.stringField(err, "message") ?: "refused without a reason",
                    tool = data?.let { JsonScan.stringField(it, "tool") } ?: tool,
                ),
            )
        }
        val result = JsonScan.objectAt(root, "result")
            ?: throw McpTransportException("heyarr answered $tool with neither result nor error", null, status)
        // MCP's `isError: true` content is a tool-level refusal carried as text.
        val isError = JsonScan.boolField(result, "isError") == true
        val text = JsonScan.objectsOf(result, listOf("content"))
            .firstNotNullOfOrNull { c -> JsonScan.stringField(c, "text") }
            ?: ""
        return if (isError) {
            McpOutcome.Refused(McpError(-1, text.ifBlank { "refused without a reason" }, tool))
        } else {
            McpOutcome.Ok(text)
        }
    }

    companion object {
        fun endpoint(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/mcp"
    }
}

/** What a tool said: a result body to read, or a refusal to quote. */
sealed interface McpOutcome {
    data class Ok(val json: String) : McpOutcome
    data class Refused(val error: McpError) : McpOutcome

    /** The result JSON, or throw the refusal as [McpRefusedException] — for callers that treat a refusal as failure. */
    fun require(): String = when (this) {
        is Ok -> json
        is Refused -> throw McpRefusedException(error)
    }
}

/**
 * A refusal, kept as data. [message] is the server's own wording — it names the rule
 * code that rejected the action, so the UI shows it verbatim.
 */
data class McpError(val code: Int, val message: String, val tool: String)

/** A refusal, thrown. */
class McpRefusedException(val error: McpError) : RuntimeException(error.message)

/** Could not get an answer at all (network, auth, protocol). The UI shows the offline banner on this. */
class McpTransportException(message: String, cause: Throwable?, val status: Int? = null) : RuntimeException(message, cause)
