package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.core.auth.Credential
import java.util.concurrent.TimeUnit

/**
 * The controller credential for the headless daemon, minted by shelling out to the installed
 * `voidbind` CLI — the least-new-crypto path (OPTION 1). The Go `voidbind identity credential
 * -header` emits the exact header lines the node's `Device` auth scheme expects:
 *
 * ```
 * Authorization: Device <cert>~<proof>
 * Voidbind-Membership: <ops>
 * ```
 *
 * The possession proof in `Authorization` is short-lived (~2 min), and one sync pass makes many
 * requests, so this hands the client a [Credential.Dynamic] whose headers are re-derived per
 * request — with a short in-process cache (default 60 s, well inside the proof's life) so a pass of
 * many blob PUT/GETs does not spawn a process per call. This deliberately couples the daemon to the
 * `voidbind` CLI + its Go device store on the host (see [GoDeviceStore]).
 *
 * The [runner] is a seam so the parsing + caching is unit-tested without spawning a process.
 */
class VoidbindCliCredential(
    private val deviceDir: String,
    private val binary: String = "voidbind",
    private val ttlMs: Long = 60_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val runner: (List<String>) -> String = ::runProcess,
) {
    @Volatile private var cached: Map<String, String>? = null
    @Volatile private var cachedAt: Long = 0

    /** The credential to hand the vault clients: fresh headers per request, cached for [ttlMs]. */
    fun credential(): Credential = Credential.Dynamic { headers() }

    @Synchronized
    private fun headers(): Map<String, String> {
        val now = clock()
        cached?.let { if (now - cachedAt < ttlMs) return it }
        val output = runner(listOf(binary, "identity", "credential", "-device-dir", deviceDir, "-header"))
        val parsed = parseHeaders(output)
        require(parsed.containsKey(Credential.HEADER)) {
            "`$binary identity credential -header` produced no ${Credential.HEADER} header"
        }
        cached = parsed
        cachedAt = now
        return parsed
    }

    companion object {
        /**
         * Parse `Name: value` header lines (split on the FIRST colon; blank lines ignored). The CLI
         * emits `Authorization` and `Voidbind-Membership`; both are returned so the membership rides
         * along on first contact.
         */
        fun parseHeaders(output: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (raw in output.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
            }
            return out
        }

        private fun runProcess(cmd: List<String>): String {
            val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
            // Read stdout to EOF before waiting, so the small output never blocks on a full pipe.
            val stdout = process.inputStream.readBytes().decodeToString()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("`${cmd.first()} identity credential` timed out")
            }
            if (process.exitValue() != 0) {
                val stderr = process.errorStream.readBytes().decodeToString().trim()
                error("`${cmd.first()} identity credential` failed (exit ${process.exitValue()}): $stderr")
            }
            return stdout
        }
    }
}
