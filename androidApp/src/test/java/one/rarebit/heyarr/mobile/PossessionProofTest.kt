package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.Ed25519Signer
import one.rarebit.voidbind.Ed25519Verifier
import one.rarebit.voidbind.auth.DeviceCredential
import one.rarebit.voidbind.auth.PossessionProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Software Ed25519 for tests, via the JDK provider (JDK 15+): raw 32-byte seeds and
 * public keys wrapped in the fixed PKCS#8 / SPKI prefixes. Stands in for the phone's
 * hardware-sealed `DeviceKeyStore` — same signature bytes, no enclave.
 */
object JdkEd25519 {
    private val PKCS8_PREFIX = hex("302e020100300506032b657004220420")
    private val SPKI_PREFIX = hex("302a300506032b6570032100")

    fun privateKey(seed: ByteArray): PrivateKey =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed))

    fun publicKey(raw: ByteArray): PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + raw))

    fun sign(seed: ByteArray, message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(privateKey(seed))
        update(message)
        sign()
    }

    val verifier = Ed25519Verifier { pub, message, sig ->
        runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey(pub))
                update(message)
                verify(sig)
            }
        }.getOrDefault(false)
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Cross-language vectors against the Go implementation heyarr-core runs
 * (voidbind-go v0.5.0 `enrolment`). Both were minted by the real Go code with fixed
 * seeds and clocks (Ed25519 is deterministic), so a byte-for-byte match here proves
 * the proof this app presents is what `deviceauth.Verify` accepts. The implementation
 * is voidbind-client's `auth/` (since 0.4.0 — the app's own port was deleted); these
 * vectors stay HERE so a library bump that drifts the wire format fails this app's
 * CI, not just the library's. If a constant ever has to change to make this pass,
 * the wire format broke — stop and investigate.
 */
class PossessionProofTest {

    // Vector A — heyarr-core's own crosscompat golden cert (user seed 0x01*32, device
    // seed 0x02*32, iat 1700000000) + a proof minted by Go `SignPossession(dev, cert,
    // 1700000000, 0)` (this session, scratch Go program).
    private val certA = "eyJ2IjoyLCJ1c3IiOiJlZDI1NTE5OjhhODhlM2RkNzQwOWYxOTVmZDUyZGIyZDNjYmE1ZDcyY2E2NzA5YmYxZDk0MTIxYmYzNzQ4ODAxYjQwZjZmNWMiLCJkZXYiOiJlZDI1NTE5OjgxMzk3NzBlYTg3ZDE3NWY1NmEzNTQ2NmMzNGM3ZWNjY2I4ZDhhOTFiNGVlMzdhMjVkZjYwZjViOGZjOWIzOTQiLCJkZW5jIjoieDI1NTE5OjAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMiLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MTcwNzc3NjAwMH0.yUnLKnvDZ8YtkgV9zf5eRrYHes5osqzzGlVHXcFSPiuIuVM0jmcdGH4qOQA-UCla_9qwSK7VPpXSfsTbSY_JBA"
    private val proofA = "eyJ2IjoyLCJjcnQiOiJsQ2ViWTBpTTM0SGM0Z3RGUERtZ1o2S0pMWWE0ejc0YklhU1hSWFV1d01ZIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwMDAxMjB9.2Ta_JOte2RDRHBFrNvOToo68--ea7TIfRjFPNGV-JtvQiXVb_hfE1gQCA9RwlbHyFJ9Mp0AZ5G_u19U1jB-FAg"
    private val deviceSeedA = ByteArray(32) { 0x02 }
    private val nowA = 1_700_000_000L

    // Vector B — the coordinator's device-scheme vector (device seed 0x80..0x9f, cert
    // iat 1788307200, possession now 1788350400).
    private val certB = "eyJ2IjoyLCJ1c3IiOiJlZDI1NTE5OjAzYTEwN2JmZjNjZTEwYmUxZDcwZGQxOGU3NGJjMDk5NjdlNGQ2MzA5YmE1MGQ1ZjFkZGM4NjY0MTI1NTMxYjgiLCJkZXYiOiJlZDI1NTE5OmNkMTRiMzdmOTU2ZTk1MzE5NGZmN2ZiNzNiM2Q4MWRjYzU2MWQ2MWE3NTM4MDk0YjdjM2UxYTY0M2VlNWYzYWEiLCJkZW5jIjoieDI1NTE5OjAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmYiLCJpYXQiOjE3ODgzMDcyMDAsImV4cCI6MTc5NjA4MzIwMH0.3VwIUF7Bg1fGQgZ8lwGUvzjpNf2FwoaP-oHcrtleTFaLMiGS2AuljHoBSpOivIl1cJ5ue61_Ci50xX7GPFWCCA"
    private val proofB = "eyJ2IjoyLCJjcnQiOiJJdG01WGo5Vmtta2NkQWNOV3JjNEM0QU1LeVppTmx2cFRZM2dfS2FtakxJIiwiaWF0IjoxNzg4MzUwNDAwLCJleHAiOjE3ODgzNTA1MjB9.Qrj11oz4bLp_Zy8xWcHzQkhvYsjcCdy69LGGRcoCABPnlz3WynYLQwVFuxoVlYkn024FaXIDhXGadMAfR5g5Bw"
    private val deviceSeedB = JdkEd25519.hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val nowB = 1_788_350_400L

    private fun signer(seed: ByteArray) = Ed25519Signer { JdkEd25519.sign(seed, it) }

    @Test fun signingBytesMatchGoJson() {
        assertEquals(
            """{"v":2,"crt":"Itm5Xj9VkmkcdAcNWrc4C4AMKyZiNlvpTY3g_KamjLI","iat":1788350400,"exp":1788350520}""",
            PossessionProof.signingBytes(certB, nowB, nowB + 120).decodeToString(),
        )
        assertEquals("lCebY0iM34Hc4gtFPDmgZ6KJLYa4z74bIaSXRXUuwMY", PossessionProof.certHash(certA))
    }

    @Test fun mintsTheExactGoProof_vectorA() {
        assertEquals(proofA, PossessionProof.mint(certA, signer(deviceSeedA), nowA))
    }

    @Test fun mintsTheExactGoProof_vectorB() {
        assertEquals(proofB, PossessionProof.mint(certB, signer(deviceSeedB), nowB))
    }

    @Test fun fullCredentialMatchesTheVector() {
        val minted = DeviceCredential.mint(certB, signer(deviceSeedB), nowB)
        assertEquals("Device $certB~$proofB", minted.headerValue)
        // The app's own Credential.Device renders through the library — same bytes.
        assertEquals(minted.headerValue, Credential.Device(minted.cert, minted.proof).headerValue())
        val (c, p) = DeviceCredential.parse(DeviceCredential.format(minted.cert, minted.proof))
        assertEquals(certB, c)
        assertEquals(proofB, p)
    }

    @Test fun certVectorsParseAndVerifyAgainstTheirUserKey() {
        for (token in listOf(certA, certB)) {
            val parsed = Cert.parse(token)
            assertEquals(2, parsed.cert.version)
            assertTrue("cert signature by usr", parsed.verify(JdkEd25519.verifier))
        }
        // The device key named by the cert is the one our seed derives — else a proof could never verify.
        assertEquals(Cert.parse(certB).cert.device.render(), "ed25519:cd14b37f956e953194ff7fb73b3d81dcc561d61a7538094b7c3e1a643ee5f3aa")
    }

    private fun devicePub(token: String) = Cert.parse(token).cert.device.bytes

    @Test fun verifyMirrorsGoWindow() {
        val pub = devicePub(certA)
        // honoured 1s before expiry; expired AT ttl (strict); honoured half a skew early; refused 2 skews early
        PossessionProof.verify(proofA, pub, certA, nowA + 119, JdkEd25519.verifier)
        assertEquals(PossessionProof.Reason.EXPIRED, refused { PossessionProof.verify(proofA, pub, certA, nowA + 120, JdkEd25519.verifier) })
        PossessionProof.verify(proofA, pub, certA, nowA - 15, JdkEd25519.verifier)
        assertEquals(PossessionProof.Reason.NOT_YET_VALID, refused { PossessionProof.verify(proofA, pub, certA, nowA - 60, JdkEd25519.verifier) })
    }

    @Test fun verifyRefusesWrongCertAndTamperedBody() {
        val pub = devicePub(certA)
        assertEquals(PossessionProof.Reason.WRONG_CERT, refused { PossessionProof.verify(proofA, pub, certB, nowA + 1, JdkEd25519.verifier) })
        val flipped = proofA.substring(0, 5) + (if (proofA[5] == 'A') 'B' else 'A') + proofA.substring(6)
        assertEquals(PossessionProof.Reason.BAD_SIGNATURE, refused { PossessionProof.verify(flipped, pub, certA, nowA + 1, JdkEd25519.verifier) })
        assertEquals(PossessionProof.Reason.MALFORMED, refused { PossessionProof.verify("no-dot", pub, certA, nowA + 1, JdkEd25519.verifier) })
    }

    private fun refused(block: () -> Unit): PossessionProof.Reason =
        assertThrows(PossessionProof.Refused::class.java) { block() }.reason

    @Test fun sessionCredentialHeaderValueUnchanged() {
        assertEquals("Bearer tok", Credential.Session("tok").headerValue())
    }
}
