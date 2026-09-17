package one.rarebit.heyarr.desktop.vault.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
