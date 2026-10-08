package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption

/**
 * The vault content codec (W4.1) — the Kotlin twin of heyarr-core's
 * `internal/personalstate/vaultframe` (ADR-0097). It turns a plaintext file into
 * fixed-size, independently-decryptable ciphertext frames plus an encrypted manifest,
 * so this client can range-read and decrypt part of a vault file without the whole
 * object, and the server/peer store only ciphertext they cannot read (ADR-0021/0049).
 *
 * Each frame seals `header ‖ data` with voidbind's per-frame AEAD
 * ([VoidbindEncryption.encryptChange], XChaCha20-Poly1305, no AAD), where the 21-byte
 * header — version ‖ file_id ‖ frame_index — is authenticated with the data by being
 * inside the sealed plaintext. During a range read the whole-blob id is not checked, so
 * the header is what stops a peer substituting one frame's ciphertext for another's: a
 * reordered frame fails the index check, a spliced frame fails the file_id check.
 *
 * Wire-compatible with Go byte-for-byte; proven by the golden vectors in
 * `jvmTest/resources/vault` (generated from live voidbind-go / heyarr-core).
 */
object VaultFrame {
    const val VERSION = 1

    /** Fixed plaintext payload of a full frame (the last frame may be shorter). 1 MiB. */
    const val FRAME_SIZE = 1 shl 20

    const val FILE_ID_LEN = 16

    /** version(1) + file_id(16) + frame_index(uint32 BE, 4). */
    const val HEADER_LEN = 1 + FILE_ID_LEN + 4

    // voidbind's XChaCha20-Poly1305 framing: a 24-byte nonce prefix and a 16-byte tag.
    const val NONCE_LEN = 24
    const val TAG_LEN = 16

    /** A frame that decrypted but did not match its manifest, or would not decrypt. */
    class FrameException(message: String) : Exception(message)

    /**
     * A blob whose bytes do not hash to the id it was fetched by (`blake3:<hex>` of its
     * ciphertext): a node or storage serving other bytes — a different, still-decryptable
     * manifest or content from the same space included. Never "absent": the bytes came back,
     * and they were the wrong ones.
     */
    class IntegrityException(message: String) : Exception(message)

    /** Refuse [bytes] unless their BLAKE3 is [expectedId] — the content-addressing check on a fetched blob. */
    fun verifyBlob(bytes: ByteArray, expectedId: String) {
        val actual = Blake3.hashHex(bytes)
        if (actual != expectedId) {
            throw IntegrityException("blob $expectedId: fetched bytes hash to $actual")
        }
    }

    /** A vault object's geometry (mirrors heyarr-core's Manifest; JSON is the contract). */
    data class Manifest(
        val version: Int,
        val fileId: String, // hex of the 16 random per-object bytes
        val frameSize: Int,
        val frameCount: Int,
        val plaintextSize: Long,
        val content: String, // "blake3:<hex>" of the ciphertext content blob
    ) {
        private fun fullFrameCipherLen(): Long =
            (NONCE_LEN + HEADER_LEN).toLong() + frameSize.toLong() + TAG_LEN.toLong()

        /**
         * The [start, end) byte range within the content blob that holds [index], so a
         * caller can range-fetch exactly that frame. The last frame may be short.
         */
        fun frameByteRange(index: Int): LongRange {
            val l = fullFrameCipherLen()
            val start = index.toLong() * l
            val end = if (index == frameCount - 1) {
                val lastData = plaintextSize - index.toLong() * frameSize.toLong()
                start + (NONCE_LEN + HEADER_LEN).toLong() + lastData + TAG_LEN.toLong()
            } else {
                start + l
            }
            return start until end
        }
    }

    /** Fetches ciphertext bytes [start, end) of a content blob (a ranged GET, in prod). */
    fun interface Fetch {
        fun range(start: Long, end: Long): ByteArray
    }

    /** Names a ciphertext blob: `"blake3:<hex>"` of its bytes. */
    fun interface ContentHasher {
        fun hash(bytes: ByteArray): String
    }

    /** The content hasher heyarr uses to name every blob (BLAKE3). */
    val BLAKE3 = ContentHasher { Blake3.hashHex(it) }

    // ---- reading ----

    /** Decrypt one frame from its sealed bytes and verify its header against [m]. */
    fun openFrame(spaceKey: ByteArray, m: Manifest, index: Int, sealed: ByteArray): ByteArray {
        val pt = try {
            VoidbindEncryption.decryptChange(spaceKey, sealed)
        } catch (e: Exception) {
            throw FrameException("frame $index did not decrypt: ${e::class.simpleName}: ${e.message}")
        }
        if (pt.size < HEADER_LEN || pt[0] != VERSION.toByte()) throw FrameException("frame $index: bad header")
        val wantId = hexToBytes(m.fileId)
        if (!pt.copyOfRange(
                1,
                1 + FILE_ID_LEN,
            ).contentEquals(wantId)
        ) {
            throw FrameException("frame $index: wrong file id")
        }
        if (beU32(pt, 1 + FILE_ID_LEN) != index) throw FrameException("frame $index: wrong index")
        return pt.copyOfRange(HEADER_LEN, pt.size)
    }

    /** Return plaintext[off : off+n], fetching only the frames that cover it. */
    fun openRange(spaceKey: ByteArray, m: Manifest, off: Long, n: Long, fetch: Fetch): ByteArray {
        require(off >= 0 && n >= 0 && off + n <= m.plaintextSize) {
            "range [$off,${off + n}) is outside the ${m.plaintextSize}-byte file"
        }
        if (n == 0L) return ByteArray(0)
        val first = (off / m.frameSize).toInt()
        val last = ((off + n - 1) / m.frameSize).toInt()
        val out = ArrayList<Byte>(((last - first + 1).toLong() * m.frameSize).toInt().coerceAtLeast(0))
        for (i in first..last) {
            val r = m.frameByteRange(i)
            val fc = fetch.range(r.first, r.last + 1)
            val data = openFrame(spaceKey, m, i, fc)
            for (b in data) out.add(b)
        }
        val lo = (off - first.toLong() * m.frameSize).toInt()
        return out.subList(lo, lo + n.toInt()).toByteArray()
    }

    /** The whole plaintext of a vault object. */
    fun openAll(spaceKey: ByteArray, m: Manifest, fetch: Fetch): ByteArray =
        openRange(spaceKey, m, 0, m.plaintextSize, fetch)

    /**
     * [openAll], with the content blob held to its id: every frame is fetched in order and
     * contiguously from byte 0, the bytes are hashed as they arrive (one frame in memory, not a
     * second copy of the blob), and the plaintext is returned only when the BLAKE3 of all of them
     * equals [Manifest.content] — else [IntegrityException]. Since the hash covers the whole blob,
     * this is the full content-address check a whole-blob fetch would give.
     *
     * A partial [openRange] cannot hash the blob, so it is NOT content-checked; it relies on what
     * heyarr-core's `vaultread` relies on for every read: each frame's AEAD under the space key,
     * with the manifest's random file id and the frame index inside the sealed header (a frame
     * from another object, or another position, fails), and the manifest's own check
     * ([openManifestVerified], whose [parseManifest] also holds its geometry to [validateManifest])
     * pinning that file id and geometry.
     */
    @Suppress("ThrowsCount") // a gap, a short read and a wrong hash are three different lies
    fun openAllVerified(spaceKey: ByteArray, m: Manifest, fetch: Fetch): ByteArray {
        val hasher = Blake3.streaming()
        var next = 0L
        val plaintext = openAll(spaceKey, m) { start, end ->
            if (start != next) throw IntegrityException("content ${m.content}: non-contiguous read at $start")
            val bytes = fetch.range(start, end)
            if (bytes.size.toLong() != end - start) {
                throw IntegrityException("content ${m.content}: [$start,$end) came back ${bytes.size} bytes")
            }
            hasher.update(bytes)
            next = end
            bytes
        }
        val actual = hasher.hashHex()
        if (actual != m.content) throw IntegrityException("content ${m.content}: fetched bytes hash to $actual")
        return plaintext
    }

    /** Reverse of [sealManifest]: decrypt the manifest blob and parse it. */
    fun openManifest(spaceKey: ByteArray, sealed: ByteArray): Manifest {
        val json = VoidbindEncryption.decryptChange(spaceKey, sealed).decodeToString()
        return parseManifest(json)
    }

    /** A manifest opened by [openManifest] over a keyring, with the key that opened it. */
    class OpenedManifest(val manifest: Manifest, val key: ByteArray)

    /**
     * [openManifest] over a space's whole [keyring], newest key first (heyarr-core
     * `vaultread.OpenManifestWithKeys`, ADR-0103). A space's key can rotate and nothing is
     * re-encrypted, so a file pushed before a rotation stays under the key of its epoch; the
     * manifest records nothing about the key, so each is tried. The key that opens the manifest is
     * the one the file's frames are sealed under (a push seals both under one key), so the caller
     * reads the frames with [OpenedManifest.key].
     *
     * No key opening it is [SpaceKeyring.NoKeyOpensException] (the caller may re-fetch a stale
     * ring). A key that DECRYPTS the manifest but whose plaintext does not parse is a
     * [FrameException] at once: the key was right and the manifest is bad, so no other key is tried.
     */
    fun openManifest(keyring: SpaceKeyring, sealed: ByteArray): OpenedManifest {
        val opened = keyring.open(sealed)
        return OpenedManifest(parseManifest(opened.plaintext.decodeToString()), opened.key)
    }

    /**
     * [openManifest] of a manifest fetched by its drive entry's blob id [manifestId], held to that
     * id FIRST: a sealed manifest whose BLAKE3 is not [manifestId] is an [IntegrityException] and
     * is never decrypted. Without it a node could hand back a different, validly sealed manifest
     * of the same space (another file), and the reader would decrypt and follow it. heyarr-core's
     * `vaultread` trusts the fetch here; this exceeds it.
     */
    fun openManifestVerified(keyring: SpaceKeyring, sealed: ByteArray, manifestId: String): OpenedManifest {
        verifyBlob(sealed, manifestId)
        return openManifest(keyring, sealed)
    }

    /**
     * Parse a manifest's JSON and hold its geometry to what a writer ([seal], [sealStreaming], Go
     * `vaultframe.Seal`) can produce — [validateManifest]. A manifest is AEAD-sealed under the
     * space key, so a bad one comes from a buggy or hostile key holder, not the node; either way
     * its numbers drive range arithmetic and allocation, so they are checked before any frame is
     * fetched. Numbers are read as longs and range-checked, never truncated to an int.
     */
    fun parseManifest(json: String): Manifest {
        val obj = JsonScan.rootObject(json) ?: throw FrameException("manifest is not an object")
        val m = Manifest(
            version = intIn(obj, "version"),
            fileId = JsonScan.stringField(obj, "file_id") ?: throw FrameException("manifest: no file_id"),
            frameSize = intIn(obj, "frame_size"),
            frameCount = intIn(obj, "frame_count"),
            plaintextSize =
            JsonScan.longField(obj, "plaintext_size") ?: throw FrameException("manifest: no plaintext_size"),
            content = JsonScan.stringField(obj, "content") ?: throw FrameException("manifest: no content"),
        )
        validateManifest(m)
        return m
    }

    /**
     * Refuse a manifest no writer produces ([FrameException]): another wire [Manifest.version], a
     * file id that is not [FILE_ID_LEN] lowercase-hex bytes, a content id that is not
     * `blake3:<64 lowercase hex>`, a frame size outside `1..`[FRAME_SIZE], a negative plaintext
     * size, or a frame count other than `ceil(plaintext_size / frame_size)`. Together these keep
     * [openRange] from dividing by zero and [Manifest.frameByteRange] inside the content blob, and
     * bound the plaintext a reader allocates to what the frames can actually carry.
     *
     * heyarr-core's `vaultread.readAll` checks only that the counts are non-negative (and then
     * that the frames do not decrypt past `plaintext_size`); this is the strict superset.
     */
    @Suppress("ThrowsCount") // one refusal per field
    fun validateManifest(m: Manifest) {
        if (m.version != VERSION) throw FrameException("manifest: version ${m.version}, want $VERSION")
        if (!FILE_ID_HEX.matches(m.fileId)) throw FrameException("manifest: file_id is not $FILE_ID_LEN hex bytes")
        if (!CONTENT_ID.matches(m.content)) throw FrameException("manifest: content is not a blake3 blob id")
        if (m.frameSize !in 1..FRAME_SIZE) throw FrameException("manifest: frame_size ${m.frameSize} out of range")
        if (m.plaintextSize < 0) throw FrameException("manifest: negative plaintext_size ${m.plaintextSize}")
        val want = (m.plaintextSize + m.frameSize - 1) / m.frameSize
        if (m.frameCount.toLong() != want) {
            throw FrameException(
                "manifest: frame_count ${m.frameCount} does not cover ${m.plaintextSize} bytes in " +
                    "${m.frameSize}-byte frames (want $want)",
            )
        }
    }

    private val FILE_ID_HEX = Regex("^[0-9a-f]{${FILE_ID_LEN * 2}}$")
    private val CONTENT_ID = Regex("^blake3:[0-9a-f]{64}$")

    /** A manifest integer field, refused when absent or outside the int range rather than truncated. */
    private fun intIn(obj: String, key: String): Int {
        val v = JsonScan.longField(obj, key) ?: throw FrameException("manifest: no $key")
        if (v !in Int.MIN_VALUE..Int.MAX_VALUE) throw FrameException("manifest: $key $v out of range")
        return v.toInt()
    }

    // ---- writing (upload path; the content hash arrives with BLAKE3 in W4.0) ----

    /** The sealed content blob for [plaintext] plus the manifest describing it. */
    fun seal(
        spaceKey: ByteArray,
        plaintext: ByteArray,
        fileId: ByteArray,
        hasher: ContentHasher = BLAKE3,
        frameSize: Int = FRAME_SIZE,
    ): Pair<ByteArray, Manifest> {
        require(fileId.size == FILE_ID_LEN) { "file id must be $FILE_ID_LEN bytes" }
        val content = ArrayList<Byte>(plaintext.size + 64)
        var index = 0
        var off = 0
        while (off < plaintext.size) {
            val end = minOf(off + frameSize, plaintext.size)
            val pt = frameHeader(fileId, index) + plaintext.copyOfRange(off, end)
            val sealed = VoidbindEncryption.encryptChange(spaceKey, pt)
            for (b in sealed) content.add(b)
            index++
            off += frameSize
        }
        val contentBytes = content.toByteArray()
        val manifest = Manifest(
            version = VERSION,
            fileId = toHex(fileId),
            frameSize = frameSize,
            frameCount = index,
            plaintextSize = plaintext.size.toLong(),
            content = hasher.hash(contentBytes),
        )
        return contentBytes to manifest
    }

    /** A source of plaintext bytes, `InputStream`-style: fill `buf[off, off+len)`, return the
     * count read, or -1 at EOF. The streaming seal never holds more than one frame. */
    fun interface PlaintextSource {
        fun read(buf: ByteArray, off: Int, len: Int): Int
    }

    /** A sink the streaming seal writes each sealed frame to, in order (e.g. a temp file). */
    fun interface SealedSink {
        fun write(bytes: ByteArray)
    }

    /**
     * The STREAMING twin of [seal] for large files: read [source] a frame at a time, seal each
     * frame, write it to [sink], and BLAKE3 the sealed bytes incrementally — so memory stays flat
     * at ~one frame no matter the file size (the whole-buffer [seal] OOMs, and a file whose sealed
     * content exceeds ~2 GiB cannot even be held in a single array). The frames, their order and
     * the content id are byte-identical to [seal] for the same plaintext (the content blob is
     * frame₀‖frame₁‖…‖frameₙ and its id is `blake3:` of exactly those bytes), so a file sealed
     * either way is wire-compatible and decrypts the same. Returns the [Manifest]; the caller
     * uploads [sink]'s bytes to the `content` id and seals+uploads the manifest.
     */
    fun sealStreaming(
        spaceKey: ByteArray,
        fileId: ByteArray,
        source: PlaintextSource,
        sink: SealedSink,
        frameSize: Int = FRAME_SIZE,
    ): Manifest {
        require(fileId.size == FILE_ID_LEN) { "file id must be $FILE_ID_LEN bytes" }
        val h = Blake3.streaming()
        val buf = ByteArray(frameSize)
        var index = 0
        var total = 0L
        while (true) {
            val n = readFully(source, buf)
            if (n == 0) break // clean EOF on a frame boundary (incl. an empty file → 0 frames)
            val pt = frameHeader(fileId, index) + buf.copyOfRange(0, n)
            val sealed = VoidbindEncryption.encryptChange(spaceKey, pt)
            sink.write(sealed)
            h.update(sealed)
            index++
            total += n.toLong()
            if (n < frameSize) break // a short frame is the last one
        }
        return Manifest(
            version = VERSION,
            fileId = toHex(fileId),
            frameSize = frameSize,
            frameCount = index,
            plaintextSize = total,
            content = h.hashHex(),
        )
    }

    /** Fill [buf] from [source], looping over short reads; return bytes read (< size ⇒ EOF). */
    private fun readFully(source: PlaintextSource, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = source.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** Seal a manifest under the space key → the ciphertext blob a peer stores. */
    fun sealManifest(spaceKey: ByteArray, m: Manifest): ByteArray =
        VoidbindEncryption.encryptChange(spaceKey, manifestJson(m).encodeToByteArray())

    fun manifestJson(m: Manifest): String = JsonWrite.obj(
        linkedMapOf(
            "version" to m.version,
            "file_id" to m.fileId,
            "frame_size" to m.frameSize,
            "frame_count" to m.frameCount,
            "plaintext_size" to m.plaintextSize,
            "content" to m.content,
        ),
    )

    // ---- bytes ----

    fun frameHeader(fileId: ByteArray, index: Int): ByteArray {
        val h = ByteArray(HEADER_LEN)
        h[0] = VERSION.toByte()
        fileId.copyInto(h, 1, 0, FILE_ID_LEN)
        h[1 + FILE_ID_LEN] = (index ushr 24).toByte()
        h[2 + FILE_ID_LEN] = (index ushr 16).toByte()
        h[3 + FILE_ID_LEN] = (index ushr 8).toByte()
        h[4 + FILE_ID_LEN] = index.toByte()
        return h
    }

    private fun beU32(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 24) or
        ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or
        (b[at + 3].toInt() and 0xFF)

    private fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex must be even length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((hexNibble(s[i * 2]) shl 4) or hexNibble(s[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex char '$c'")
    }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }
}
