package one.rarebit.heyarr.desktop.vault.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** Config resolution precedence: defaults → JSON file → env → args (last wins). */
class DaemonConfigTest {

    @Test
    fun defaultsAreTheContractValues() {
        val c = DaemonConfig()
        assertEquals("01a0ae3b-ca68-7165-9dbb-527425e2f380", c.spaceId)
        assertEquals("https://heyarr.br.thesim.family:7777", c.controller)
        assertEquals("vault-sync.json", c.statusFile.fileName.toString())
        assertEquals("vault-sync.sock", c.socketPath.fileName.toString())
    }

    @Test
    fun fileValuesOverrideDefaults() {
        val dir = Files.createTempDirectory("vaultcfg")
        try {
            val file = File(dir.toFile(), "config.json")
            file.writeText(
                """{"folder":"/data/Vault","space_id":"space-x","controller":"https://node:1","poll_ms":5000}""",
            )
            val c = DaemonConfig.fromFile(file)
            assertEquals("/data/Vault", c.folder)
            assertEquals("space-x", c.spaceId)
            assertEquals("https://node:1", c.controller)
            assertEquals(5000, c.pollMs)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun envOverridesFileAndArgsOverrideEnv() {
        val dir = Files.createTempDirectory("vaultcfg")
        try {
            val file = File(dir.toFile(), "config.json")
            file.writeText("""{"folder":"/from/file","controller":"https://file"}""")
            val env = mapOf(
                "HEYARR_VAULT_SYNC_CONFIG" to file.path,
                "HEYARR_VAULT_FOLDER" to "/from/env",
                "HEYARR_VAULT_CONTROLLER" to "https://env",
            )
            val c = DaemonConfig.resolve(
                args = arrayOf("--folder", "/from/args"),
                env = { env[it] },
            )
            assertEquals("/from/args", c.folder)   // arg beats env
            assertEquals("https://env", c.controller) // env beats file
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun equalsFormArgsParse() {
        val c = DaemonConfig.resolve(arrayOf("--folder=/x", "--poll-ms=1234"), env = { null })
        assertEquals("/x", c.folder)
        assertEquals(1234, c.pollMs)
    }

    // ── write token resolution (ADR-0067: a headless writer needs a write-scoped bearer) ──

    @Test
    fun inlineTokenWinsAndIsTrimmed() {
        val c = DaemonConfig(token = "  heyarr_abc_secret  ")
        // readFile must not be consulted when an inline token is present.
        assertEquals("heyarr_abc_secret", c.resolveApiToken { fail("must not read a file") })
    }

    @Test
    fun tokenFileIsReadWhenNoInlineToken() {
        val c = DaemonConfig(tokenFile = "/some/where/cli.token")
        val token = c.resolveApiToken { path -> if (path == "/some/where/cli.token") "tok-from-file\n" else null }
        assertEquals("tok-from-file", token)
    }

    @Test
    fun defaultTokenFileUsedWhenNeitherSet() {
        val c = DaemonConfig()
        val token = c.resolveApiToken { path -> if (path == DaemonConfig.DEFAULT_TOKEN_FILE) "default-tok" else null }
        assertEquals("default-tok", token)
    }

    @Test
    fun nullWhenNoTokenAnywhere() {
        val c = DaemonConfig()
        assertNull(c.resolveApiToken { null })
    }

    @Test
    fun envTokenIsPickedUp() {
        val c = DaemonConfig.resolve(args = arrayOf(), env = { if (it == "HEYARR_VAULT_TOKEN") "env-tok" else null })
        assertEquals("env-tok", c.token)
        assertEquals("env-tok", c.resolveApiToken { fail("inline env token must not read a file") })
    }

    @Test
    fun argTokenBeatsEnvToken() {
        val c = DaemonConfig.resolve(
            args = arrayOf("--token", "arg-tok"),
            env = { if (it == "HEYARR_VAULT_TOKEN") "env-tok" else null },
        )
        assertEquals("arg-tok", c.token)
    }

    // ── per-vault sync-index override (separate index per daemon instance, no XDG hack) ──

    @Test
    fun indexFileDefaultsToNull() {
        assertNull(DaemonConfig().indexFile)
    }

    @Test
    fun indexFileFromJsonFile() {
        val dir = Files.createTempDirectory("vaultcfg")
        try {
            val file = File(dir.toFile(), "config.json")
            file.writeText("""{"folder":"/data/Vault","index_file":"/data/idx/personal.json"}""")
            assertEquals("/data/idx/personal.json", DaemonConfig.fromFile(file).indexFile)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun indexFileEnvOverridesFileAndArgOverridesEnv() {
        val dir = Files.createTempDirectory("vaultcfg")
        try {
            val file = File(dir.toFile(), "config.json")
            file.writeText("""{"index_file":"/from/file.json"}""")
            val env = mapOf(
                "HEYARR_VAULT_SYNC_CONFIG" to file.path,
                "HEYARR_VAULT_INDEX" to "/from/env.json",
            )
            // env beats file
            assertEquals("/from/env.json", DaemonConfig.resolve(arrayOf(), env = { env[it] }).indexFile)
            // arg beats env
            assertEquals(
                "/from/args.json",
                DaemonConfig.resolve(arrayOf("--index-file", "/from/args.json"), env = { env[it] }).indexFile,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
