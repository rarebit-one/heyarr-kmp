package one.rarebit.heyarr.desktop.device

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import one.rarebit.voidwhichbinds.Ed25519Signer

/**
 * Software Ed25519 for the desktop device signing key — a deliberate mirror of
 * voidbind-client's own (internal) `Ed25519Engine`, using the SAME provider
 * (cryptography-kotlin 0.6.0, the JDK provider on JVM) over the SAME RAW 32-byte seed
 * and 32-byte public-key formats. Because both sides speak `EdDSA.*.Format.RAW`, a seed
 * generated here signs proofs/ops that verify byte-for-byte against voidbind's verifier
 * and heyarr-core — this is not a re-implementation of the curve, only a second caller
 * of the vetted library.
 *
 * Why heyarr holds this at all: voidbind's JVM [one.rarebit.voidwhichbinds.DeviceKeyStore]
 * actual is a non-persisted, process-lifetime key that never exposes its seed, so a
 * desktop enrolment that must survive a relaunch cannot be backed by it. The desktop
 * keyring instead generates a seed here, seals it at rest ([DesktopSecretStore]) and
 * reconstructs an [Ed25519Signer] from the unsealed seed on each launch.
 *
 * A plain JVM has no secure element: this seed lives in the heap while signing, exactly
 * as voidbind's own JVM actual documents. Non-exportability is unattainable on desktop;
 * the protection is the seal-at-rest around the seed (see [DesktopSecretStore]).
 */
internal object DesktopEd25519 {

    private val eddsa = CryptographyProvider.Default.get(EdDSA)
    private val generator = eddsa.keyPairGenerator(EdDSA.Curve.Ed25519)
    private val privateDecoder = eddsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
    private val publicDecoder = eddsa.publicKeyDecoder(EdDSA.Curve.Ed25519)

    /** A freshly generated device key, as raw bytes ready to seal / store. */
    class Generated(val seed: ByteArray, val publicKey: ByteArray)

    /** Generate a new Ed25519 key; returns the RAW 32-byte seed + 32-byte public key. */
    fun generate(): Generated {
        val pair = generator.generateKeyBlocking()
        return Generated(
            seed = pair.privateKey.encodeToByteArrayBlocking(EdDSA.PrivateKey.Format.RAW),
            publicKey = pair.publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW),
        )
    }

    /** Sign [message] with a RAW 32-byte private [seed]; returns a 64-byte signature. */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val priv = privateDecoder.decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, seed)
        return priv.signatureGenerator().generateSignatureBlocking(message)
    }

    /** Verify a 64-byte [signature] over [message] against a RAW 32-byte [publicKey]. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val pub = publicDecoder.decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, publicKey)
        return pub.signatureVerifier().tryVerifySignatureBlocking(message, signature)
    }

    /** An [Ed25519Signer] backed by [seed] — what a reconstructed [DeviceIdentity] signs through. */
    fun signer(seed: ByteArray): Ed25519Signer = Ed25519Signer { sign(seed, it) }
}
