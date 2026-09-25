package one.rarebit.heyarr.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.device.DesktopDeviceEnroller
import one.rarebit.heyarr.desktop.device.DesktopDeviceKeyring
import one.rarebit.heyarr.desktop.device.DevicePairingSteps
import one.rarebit.heyarr.desktop.device.PairingCoordinator
import one.rarebit.heyarr.desktop.device.PairingState
import one.rarebit.voidbind.Ed25519Verifier
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.auth.PossessionProof
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The caveat on heyarr-kmp #32: an AUTOMATED end-to-end proof that the DESKTOP enroller's
 * relay transport completes a pairing against a REAL voidbind-go relay — not a fake.
 *
 * PR #35's `PairingCoordinatorTest` proves the state machine with a fake `PairingSteps`;
 * this proves the missing half — the LIVE wire. It stands up a real voidbind-go relay and
 * a real genesis initiator (the Go `relaysmoke` helper), then drives the production desktop
 * responder — [PairingCoordinator] → [DevicePairingSteps] → `DevicePairing` over a real
 * [one.rarebit.heyarr.desktop.device.PatientRelayTransport] and `JdkHttpTransport` — against
 * it, and asserts:
 *
 *  - both sides derive the SAME 7-digit SAS across the language boundary,
 *  - the desktop reaches [PairingState.Enrolled],
 *  - the received admitting op is a signature-valid ADD (Go-signed) for THIS desktop's keys
 *    into the Go initiator's identity ([MembershipOp.verify], which checks the Ed25519 sig),
 *  - the resulting [Credential.Device] is presentable and its possession proof verifies
 *    against an Ed25519 verifier ([PossessionProof.verify]).
 *
 * # Gating (default CI stays green)
 *
 * The test is a no-op UNLESS `VOIDBIND_GO_DIR` points at a voidbind-go checkout AND the `go`
 * toolchain is on PATH. `desktop.yml` sets neither, so `:composeApp:jvmTest` skips it. Run
 * it explicitly:
 *
 * ```
 * VOIDBIND_GO_DIR=/path/to/voidbind-go ./gradlew :composeApp:jvmTest \
 *     --tests one.rarebit.heyarr.desktop.RelaySmokeTest
 * ```
 *
 * The committed Go source (`src/jvmTest/resources/relaysmoke/relaysmoke.go`) is copied into
 * a throwaway package dir INSIDE the voidbind-go module and `go run`, so its imports resolve
 * against voidbind-go's own module with no network.
 */
class RelaySmokeTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** A heyarr node with no `/enrol` route: the pairing (relay) is what this test proves; a
     *  real node is separate infra, so registration truthfully resolves to NeedsAdmin and the
     *  coordinator still reaches Enrolled with the admission stored. */
    private class NoEnrolNode : HttpTransport {
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
            HttpResponse(404, "")
    }

    @Test
    fun desktop_enroller_pairs_against_a_real_voidbind_relay() = runBlocking {
        val goDir = System.getenv("VOIDBIND_GO_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        if (goDir == null || !File(goDir, "go.mod").exists() || !goToolchainPresent()) {
            println(
                "RelaySmokeTest SKIPPED — set VOIDBIND_GO_DIR to a voidbind-go checkout and have `go` on PATH " +
                    "to run the live relay round-trip (goDir=$goDir).",
            )
            return@runBlocking
        }

        val pkgDir = File(goDir, ".heyarr-smoke-" + UUID.randomUUID().toString().take(8))
        val dataDir = File(
            System.getProperty("java.io.tmpdir"),
            "heyarr-smoke-dev-" + UUID.randomUUID().toString().take(8),
        )
        var proc: Process? = null
        try {
            pkgDir.mkdirs()
            copyResource("/relaysmoke/relaysmoke.go", File(pkgDir, "relaysmoke.go"))

            proc = ProcessBuilder("go", "run", "./" + pkgDir.name)
                .directory(goDir)
                .redirectErrorStream(false)
                .start()
            val p = proc

            val invite = CompletableFuture<String>()
            val user = CompletableFuture<String>()
            val helperSas = CompletableFuture<String>()
            // Drain the helper's stdout on a daemon thread, surfacing its line protocol.
            Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("INVITE ") -> invite.complete(line.removePrefix("INVITE ").trim())
                        line.startsWith("USER ") -> user.complete(line.removePrefix("USER ").trim())
                        line.startsWith("SAS ") -> helperSas.complete(line.removePrefix("SAS ").trim())
                    }
                }
            }.apply { isDaemon = true }.start()
            // Surface the helper's stderr (go build output / ERROR lines) for diagnosis.
            Thread {
                p.errorStream.bufferedReader().forEachLine { System.err.println("relaysmoke> $it") }
            }.apply { isDaemon = true }.start()

            val inviteQr = invite.get(90, TimeUnit.SECONDS)

            val keyring = DesktopDeviceKeyring(dataDir = dataDir, keyFile = File(dataDir, "device.key"))
            val steps = DevicePairingSteps(
                keyring = keyring,
                nodeTransport = NoEnrolNode(),
                baseUrl = { "http://127.0.0.1:1" },
                deviceName = { "relay-smoke-desktop" },
                credential = { Credential.Guest },
            )
            val coordinator = PairingCoordinator(scope, steps = { steps })

            coordinator.start(inviteQr)

            val compare = coordinator.await(90_000) {
                it is PairingState.CompareSas && !it.awaitingAdmission
            } as PairingState.CompareSas
            // Cross-language agreement: the desktop and the Go initiator derived the SAME SAS.
            assertEquals(helperSas.get(30, TimeUnit.SECONDS), compare.sas, "SAS must match across the wire")
            assertTrue(compare.sas.length == 7 && compare.sas.all { it.isDigit() }, "SAS is 7 digits")

            coordinator.confirmMatch()

            val enrolled = coordinator.await(90_000) { it is PairingState.Enrolled } as PairingState.Enrolled
            assertTrue(enrolled.op.isNotEmpty(), "an admitting op was received")

            // The received cert is a signature-valid ADD, Go-signed, for THIS desktop's keys.
            val cert = assertNotNull(keyring.certToken(), "the admission was persisted")
            val op = MembershipOp.verify(cert)
            assertEquals(MembershipOp.Kind.ADD, op.kind)
            assertEquals(user.get(5, TimeUnit.SECONDS), op.user, "admitted into the Go initiator's identity")
            assertEquals(keyring.info().deviceKey, op.device, "admits this desktop's device key")

            // The resulting Credential.Device is presentable and its possession proof verifies.
            val device =
                assertNotNull(
                    DesktopDeviceEnroller(keyring).enrolled(),
                    "the enroller reports a live Device credential",
                )
            assertEquals(cert, device.cert)
            PossessionProof.verify(
                proof = device.proof,
                devicePublicKey = keyring.identity().signPublicKey,
                certToken = cert,
                now = System.currentTimeMillis() / 1000,
                verifier = jdkEd25519Verifier(),
            )

            // Release the helper (it holds the relay open until our stdin closes) and confirm the
            // Go initiator also completed cleanly (it Authorised — signed + sealed the add op).
            p.outputStream.close()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the relaysmoke helper exits")
            assertEquals(0, p.exitValue(), "the Go relay+initiator completed without error")
        } finally {
            proc?.destroyForcibly()
            pkgDir.deleteRecursively()
            dataDir.deleteRecursively()
            scope.cancel()
        }
    }

    private suspend fun PairingCoordinator.await(timeoutMs: Long, p: (PairingState) -> Boolean): PairingState =
        withTimeout(timeoutMs) { state.first(p) }

    private fun goToolchainPresent(): Boolean = try {
        val pr = ProcessBuilder("go", "version").redirectErrorStream(true).start()
        pr.waitFor(15, TimeUnit.SECONDS) && pr.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    private fun copyResource(resource: String, dest: File) {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("missing test resource $resource on the jvmTest classpath")
        stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    /** An [Ed25519Verifier] backed directly by the JDK provider (JDK 15+ ships "Ed25519"). */
    private fun jdkEd25519Verifier() = Ed25519Verifier { publicKey, message, signature ->
        try {
            // Wrap the raw 32-byte key in the fixed X.509 SubjectPublicKeyInfo Ed25519 prefix.
            val der = byteArrayOf(
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
            ) + publicKey
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}
