package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.voidbind.crypto.VoidbindEncryption

/**
 * The space-key crypto the personal-state plane needs, as a seam so tests can
 * substitute a deterministic fake for the real X25519/XChaCha20 primitives. The
 * real implementation delegates to voidbind-client's [VoidbindEncryption]
 * (byte-identical to `voidbind-go/encryption`, KAT-proven) — this repo re-derives
 * NO wire format (the one-copy rule). A space key is a raw 32-byte key; a wrapped
 * key is 104 bytes; a change ciphertext is `nonce(24) ‖ AEAD`.
 */
internal interface SpaceCrypto {
    fun newSpaceKey(): ByteArray
    fun seal(spaceKey: ByteArray, recipientPub: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray, recipientSeed: ByteArray): ByteArray
    fun encryptChange(spaceKey: ByteArray, plaintext: ByteArray): ByteArray
    fun decryptChange(spaceKey: ByteArray, blob: ByteArray): ByteArray
}

internal object VoidbindSpaceCrypto : SpaceCrypto {
    override fun newSpaceKey(): ByteArray = VoidbindEncryption.newSpaceKey()
    override fun seal(spaceKey: ByteArray, recipientPub: ByteArray): ByteArray =
        VoidbindEncryption.seal(spaceKey, recipientPub)
    override fun unwrap(wrapped: ByteArray, recipientSeed: ByteArray): ByteArray =
        VoidbindEncryption.unwrap(wrapped, recipientSeed)
    override fun encryptChange(spaceKey: ByteArray, plaintext: ByteArray): ByteArray =
        VoidbindEncryption.encryptChange(spaceKey, plaintext)
    override fun decryptChange(spaceKey: ByteArray, blob: ByteArray): ByteArray =
        VoidbindEncryption.decryptChange(spaceKey, blob)
}

/**
 * This phone's X25519 encryption key — the recipient a space key is wrapped for and
 * the seed that unwraps it. The private scalar is held sealed-at-rest by
 * `device/SealedSecretStore` (X25519 agreement keys cannot live in the secure
 * element; ADR-0001), so [seed] returns the unsealed bytes; the ECDH runs in
 * [SpaceCrypto], not in the enclave. The recipient id renders exactly like Go's
 * `encryption.FormatPublicKey`: `x25519:<lowercase-hex>`.
 */
internal interface DeviceEncKey {
    fun publicKey(): ByteArray
    fun seed(): ByteArray
    fun recipientId(): String = "x25519:" + Hex.encode(publicKey())
}

/** Parse an `x25519:<hex>` recipient id (e.g. a member's `denc`) into a raw 32-byte public key, or null. */
internal fun parseX25519Recipient(id: String): ByteArray? {
    val hex = id.removePrefix("x25519:")
    if (hex == id || hex.length != 64) return null
    return Hex.decodeOrNull(hex)?.takeIf { it.size == 32 }
}
