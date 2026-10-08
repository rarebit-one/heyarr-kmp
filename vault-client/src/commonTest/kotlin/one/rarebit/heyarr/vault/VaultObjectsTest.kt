package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ref-addressed reads and writes (heyarr ADR-0104) through an in-memory node that holds only
 * ciphertext. The space key, the change and frame AEADs and the key-history chain are the REAL
 * voidbind primitives; only the X25519 wrap is a stand-in (the copy is the raw key), since the
 * wrap is [SpaceOpen]'s and is pinned elsewhere.
 */
class VaultObjectsTest {
    private val spaceId = "0192f3a4-5b6c-7d8e-9f01-23456789abcd"
    private val me = "x25519:" + "11".repeat(32)
    private val envelope = """{"v":1,"type":"answer","body":"JDCANARY-42","citations":[]}"""

    /** A node: wrapped copies + key history, the change log and the blob store — opaque bytes only. */
    private class FakeNode(val spaceId: String) :
        SpaceKeySource,
        VaultSpace,
        VaultBlobStore {
        var keyEpoch = 0
        val wrapped = ArrayList<WrappedKey>()
        val history = ArrayList<KeyHistoryEntry>()
        var keysStatus = 200
        var keysCalls = 0

        /** Runs before the Nth `GET /keys` answers (1-based) — a rotation landing mid-operation. */
        var beforeKeys: (call: Int) -> Unit = {}
        val changes = ArrayList<EncryptedChange>()
        val blobs = HashMap<String, ByteArray>()
        val pushedParents = ArrayList<List<String>>()

        /** When set, every blob PUT is refused with this HTTP status. */
        var putStatus: Int? = null

        override fun spaceKeys(spaceId: String): SpaceKeyList {
            beforeKeys(++keysCalls)
            if (keysStatus != 200) throw VaultHttpException(keysStatus, "keys: HTTP $keysStatus")
            return SpaceKeyList(keyEpoch, wrapped.toList())
        }

        override fun keyHistory(spaceId: String): List<KeyHistoryEntry> = history.toList()

        override fun pullChanges(spaceId: String): List<EncryptedChange> = changes.toList()

        override fun pullChangesSince(spaceId: String, since: Long) = ChangePage(changes.drop(since.toInt()), 0)

        override fun pushChange(spaceId: String, parents: List<String>, ciphertext: ByteArray): String {
            val canonical = PersonalStateId.canonical(parents)
            val id = PersonalStateId.changeId(spaceId, canonical, ciphertext)
            pushedParents.add(canonical)
            changes.add(EncryptedChange(spaceId, id, canonical, ciphertext))
            return id
        }

        override fun putBlob(baseUrl: String, hash: String, bytes: ByteArray, credential: Credential): PutResult {
            putStatus?.let { return PutResult.Failed("upload failed: HTTP $it", status = it) }
            blobs[hash] = bytes
            return PutResult.Stored(hash, bytes.size.toLong())
        }

        override fun fetchRange(baseUrl: String, hash: String, start: Long, end: Long, credential: Credential) =
            fetchAll(baseUrl, hash, credential).copyOfRange(start.toInt(), end.toInt())

        override fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray =
            blobs[hash] ?: throw VaultHttpException(404, "blob: HTTP 404")
    }

    private fun node(key: ByteArray, recipient: String = me) = FakeNode(spaceId).apply {
        wrapped.add(WrappedKey(recipient, key))
    }

    private fun client(node: FakeNode, recipient: String = me) = VaultObjects(
        keys = node,
        space = node,
        blobs = node,
        baseUrl = "https://node.example",
        credential = Credential.Guest,
        recipient = VaultRecipient(recipient) { it.copyOf() }, // stand-in wrap: the copy is the key
        now = { 1_760_000_000L },
    )

    @Test
    fun putThenGetRoundTripsTheEnvelopeAndTheNodeSeesOnlyCiphertext() {
        val n = node(VoidbindEncryption.newSpaceKey())
        val vault = client(n)

        val put = vault.put("hv1:$spaceId", envelope)
        assertEquals(spaceId, put.ref.space)
        assertEquals(".jumpdrive/objects/${put.ref.objectId}.json", put.path)
        assertEquals(envelope.length.toLong(), put.size)
        assertTrue(put.manifestBlob in n.blobs, "the sealed manifest is the drive entry's blob")

        val got = vault.get(put.ref.toString())
        assertEquals(envelope, got.json)
        assertEquals(1, got.version)
        assertEquals("answer", got.type)

        // Nothing the node holds carries the plaintext.
        val canary = "JDCANARY-42".encodeToByteArray()
        (n.blobs.values + n.changes.map { it.ciphertext }).forEach { assertTrue(!it.containsSubsequence(canary)) }
    }

    @Test
    fun aSecondWriteIsParentedOnTheCurrentHeads() {
        val n = node(VoidbindEncryption.newSpaceKey())
        val vault = client(n)
        val first = vault.put(spaceId, envelope)
        vault.put(spaceId, envelope)
        assertEquals(listOf(emptyList(), listOf(first.changeId)), n.pushedParents)
    }

    @Test
    fun anObjectWrittenBeforeARotationStaysReadable() {
        val k0 = VoidbindEncryption.newSpaceKey()
        val n = node(k0)
        val put = client(n).put(spaceId, envelope)

        // Rotate to epoch 1: a fresh key, its predecessor sealed under it, this device's copy re-sent.
        val k1 = VoidbindEncryption.newSpaceKey()
        n.keyEpoch = 1
        n.history.add(KeyHistoryEntry(1, VoidbindEncryption.sealSpaceKey(k1, k0)))
        n.wrapped.clear()
        n.wrapped.add(WrappedKey(me, k1, epoch = 1))

        assertEquals(envelope, client(n).get(put.ref).json)
        // And a write after the rotation is sealed under the new key only.
        val after = client(n).put(spaceId, envelope)
        assertEquals(envelope, client(n).get(after.ref).json)
    }

    @Test
    fun aRotationBetweenOpeningAndSealingMovesTheWriteOntoTheNewKey() {
        val k0 = VoidbindEncryption.newSpaceKey()
        val k1 = VoidbindEncryption.newSpaceKey()
        val n = node(k0)
        // The space opens at epoch 0; the rotation commits before the write re-reads the epoch, and
        // brings no change — so no decrypt failure would ever have revealed it.
        n.beforeKeys = { call ->
            if (call == 2) {
                n.keyEpoch = 1
                n.history.add(KeyHistoryEntry(1, VoidbindEncryption.sealSpaceKey(k1, k0)))
                n.wrapped.clear()
                n.wrapped.add(WrappedKey(me, k1, epoch = 1))
            }
        }
        val put = client(n).put(spaceId, envelope)

        val sealedManifest = n.blobs.getValue(put.manifestBlob)
        VaultFrame.openManifest(k1, sealedManifest) // sealed under the new key …
        assertFailsWith<Exception> { VaultFrame.openManifest(k0, sealedManifest) } // … and not the retired one
        val change = n.changes.single().ciphertext
        VoidbindEncryption.decryptChange(k1, change)
        assertFailsWith<Exception> { VoidbindEncryption.decryptChange(k0, change) }
        assertEquals(envelope, client(n).get(put.ref).json)
    }

    @Test
    fun aRotationThisDeviceCannotFollowRefusesTheWrite() {
        val n = node(VoidbindEncryption.newSpaceKey())
        n.beforeKeys = { call -> if (call == 2) n.keyEpoch = 1 } // rotated, but no copy for this device
        assertFailsWith<VaultRefException.StaleEpoch> { client(n).put(spaceId, envelope) }
        assertTrue(n.blobs.isEmpty() && n.changes.isEmpty(), "nothing is sealed under the retired key")
    }

    @Test
    fun anOutageWhileReopeningARotatedSpaceIsNotReportedAsStale() {
        val n = node(VoidbindEncryption.newSpaceKey())
        // The epoch moves (call 2), then re-opening the space hits a 5xx (call 3): retriable, not a
        // rotation this device cannot follow.
        n.beforeKeys = { call ->
            if (call == 2) n.keyEpoch = 1
            if (call == 3) n.keysStatus = 503
        }
        val e = assertFailsWith<VaultHttpException> { client(n).put(spaceId, envelope) }
        assertEquals(503, e.status)
        assertTrue(n.blobs.isEmpty() && n.changes.isEmpty(), "nothing is sealed under the retired key")
    }

    private fun FakeNode.rotate(from: ByteArray, to: ByteArray, recipient: String? = me) {
        keyEpoch++
        history.add(KeyHistoryEntry(keyEpoch, VoidbindEncryption.sealSpaceKey(to, from)))
        wrapped.clear()
        if (recipient != null) wrapped.add(WrappedKey(recipient, to, epoch = keyEpoch))
    }

    @Test
    fun aRotationWhileTheWriteSealsIsCaughtBeforeThePushAndResealedUnderTheNewKey() {
        val k0 = VoidbindEncryption.newSpaceKey()
        val k1 = VoidbindEncryption.newSpaceKey()
        val n = node(k0)
        // GET /keys: 1 opens, 2 is the pre-seal check (epoch 0), 3 the pre-push check — by which
        // time the rotation has committed, after the epoch-0 blobs went up.
        n.beforeKeys = { call -> if (call == 3) n.rotate(k0, k1) }
        val put = client(n).put(spaceId, envelope)

        val change = n.changes.single().ciphertext // only ONE change was pushed, under the new key
        VoidbindEncryption.decryptChange(k1, change)
        assertFailsWith<Exception> { VoidbindEncryption.decryptChange(k0, change) }
        VaultFrame.openManifest(k1, n.blobs.getValue(put.manifestBlob))
        assertEquals(envelope, client(n).get(put.ref).json)
        // The epoch-0 blobs stay on the node, but no change names them.
        val orphans =
            n.blobs.keys - put.manifestBlob - VaultFrame.openManifest(k1, n.blobs.getValue(put.manifestBlob)).content
        assertEquals(2, orphans.size)
    }

    @Test
    fun aSecondRotationDuringTheResealRefusesTheWriteAndPushesNothing() {
        val k0 = VoidbindEncryption.newSpaceKey()
        val k1 = VoidbindEncryption.newSpaceKey()
        val k2 = VoidbindEncryption.newSpaceKey()
        val n = node(k0)
        // 3 = first pre-push check (rotated), 4 = the reopen, 5 = the re-seal's pre-push check (rotated again).
        n.beforeKeys = { call ->
            if (call == 3) n.rotate(k0, k1)
            if (call == 5) n.rotate(k1, k2)
        }
        assertFailsWith<VaultRefException.StaleEpoch> { client(n).put(spaceId, envelope) }
        assertTrue(n.changes.isEmpty(), "no drive change under a retired key")
    }

    @Test
    fun aRotationBeforeThePushThatThisDeviceCannotFollowRefusesIt() {
        val k0 = VoidbindEncryption.newSpaceKey()
        val n = node(k0)
        n.beforeKeys = { call -> if (call == 3) n.rotate(k0, VoidbindEncryption.newSpaceKey(), recipient = null) }
        assertFailsWith<VaultRefException.StaleEpoch> { client(n).put(spaceId, envelope) }
        assertTrue(n.changes.isEmpty(), "this device was revoked: nothing it sealed is published")
    }

    /** Seal a well-formed object, then replace its manifest with [json] sealed under the same key. */
    private fun withManifest(json: (VaultFrame.Manifest) -> String): Pair<VaultObjects, VaultRef> {
        val key = VoidbindEncryption.newSpaceKey()
        val n = node(key)
        val vault = client(n)
        val put = vault.put(spaceId, envelope)
        val m = VaultFrame.openManifest(key, n.blobs.getValue(put.manifestBlob))
        // The drive entry names the blob by id, so a bad manifest gets its own id and drive entry.
        val bad = VoidbindEncryption.encryptChange(key, json(m).encodeToByteArray())
        val badId = VaultFrame.BLAKE3.hash(bad)
        n.blobs[badId] = bad
        val ref = VaultRef.newObject(spaceId)
        val loaded = vault.loadDrive(spaceId, SpaceKeyring.single(key))
        val change = loaded.drive.put(requireNotNull(ref.path), badId, m.plaintextSize, 1L)
        n.pushChange(
            spaceId,
            loaded.heads(),
            VoidbindEncryption.encryptChange(key, encodeDriveChange(change).encodeToByteArray()),
        )
        return vault to ref
    }

    private fun manifestJson(m: VaultFrame.Manifest, vararg overrides: Pair<String, Any>): String {
        val fields = linkedMapOf<String, Any>(
            "version" to m.version,
            "file_id" to "\"${m.fileId}\"",
            "frame_size" to m.frameSize,
            "frame_count" to m.frameCount,
            "plaintext_size" to m.plaintextSize,
            "content" to "\"${m.content}\"",
        )
        overrides.forEach { (k, v) -> fields[k] = v }
        return fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    @Test
    fun aManifestWithImpossibleGeometryIsAnIntegrityFailureBeforeAnyContentIsRead() {
        listOf(
            arrayOf("frame_size" to 0), // would divide by zero
            arrayOf("frame_size" to -1),
            arrayOf("frame_size" to VaultFrame.FRAME_SIZE + 1),
            arrayOf("frame_size" to 4_294_967_312L), // 2^32 + 16: truncated to an int it would read as 16
            arrayOf("frame_count" to 0),
            arrayOf("frame_count" to 2),
            arrayOf("frame_count" to -1),
            arrayOf("plaintext_size" to -1),
            arrayOf("plaintext_size" to 10_000_000_000L, "frame_count" to 1),
            arrayOf("version" to 2),
            arrayOf("file_id" to "\"zz\""),
            arrayOf("file_id" to "\"${"AB".repeat(16)}\""),
            arrayOf("content" to "\"sha256:${"00".repeat(32)}\""),
        ).forEach { overrides ->
            val (vault, ref) = withManifest { manifestJson(it, *overrides) }
            assertFailsWith<VaultRefException.Integrity>(overrides.joinToString()) { vault.get(ref) }
        }
        // Control: the untouched manifest re-sealed under a new id reads fine.
        val (vault, ref) = withManifest { manifestJson(it) }
        assertEquals(envelope, vault.get(ref).json)
    }

    @Test
    fun aConsistentManifestLargerThanAnyObjectIsRefusedBeforeItsContentIsFetched() {
        val size = VaultObjects.MAX_OBJECT_BYTES.toLong() + 1
        val frames = (size + VaultFrame.FRAME_SIZE - 1) / VaultFrame.FRAME_SIZE
        val (vault, ref) = withManifest {
            manifestJson(it, "frame_size" to VaultFrame.FRAME_SIZE, "frame_count" to frames, "plaintext_size" to size)
        }
        assertFailsWith<VaultRefException.InvalidObject> { vault.get(ref) }
    }

    @Test
    fun aSpaceTheNodeWillNotShowIsForbidden() {
        val n = node(VoidbindEncryption.newSpaceKey())
        val ref = client(n).put(spaceId, envelope).ref
        for (status in listOf(401, 403, 404)) {
            n.keysStatus = status
            val e = assertFailsWith<VaultRefException.Forbidden> { client(n).get(ref) }
            assertEquals(status, e.status)
        }
        n.keysStatus = 500
        assertFailsWith<VaultHttpException> { client(n).get(ref) } // a failure, not a refusal
    }

    @Test
    fun anUploadRefusedAfterTheSpaceOpenedIsForbidden() {
        val n = node(VoidbindEncryption.newSpaceKey())
        for (status in listOf(401, 403)) {
            n.putStatus = status
            val e = assertFailsWith<VaultRefException.Forbidden> { client(n).put(spaceId, envelope) }
            assertEquals(status, e.status)
        }
        n.putStatus = 500
        assertFailsWith<IllegalStateException> { client(n).put(spaceId, envelope) } // a failure, not a refusal
    }

    @Test
    fun aSpaceWithNoCopyForThisRecipientCannotBeDecrypted() {
        val n = node(VoidbindEncryption.newSpaceKey(), recipient = "x25519:" + "22".repeat(32))
        assertFailsWith<VaultRefException.Unwrap> { client(n).get("hv1:$spaceId/3f2504e0-4f89-41d3-9a0c-0305e82c3301") }
    }

    @Test
    fun aSupersededCopyIsRefusedAsUndecryptable() {
        val n = node(VoidbindEncryption.newSpaceKey())
        n.keyEpoch = 1 // the space rotated; this device still holds its epoch-0 copy
        assertFailsWith<VaultRefException.Unwrap> { client(n).openSpace(spaceId) }
    }

    @Test
    fun anUnknownObjectOrAnUnservableBlobIsAbsent() {
        val n = node(VoidbindEncryption.newSpaceKey())
        val put = client(n).put(spaceId, envelope)
        assertFailsWith<VaultRefException.Absent> { client(n).get(VaultRef.newObject(spaceId)) }

        n.blobs.remove(put.manifestBlob)
        assertFailsWith<VaultRefException.Absent> { client(n).get(put.ref) }
    }

    @Test
    fun aSubstitutedManifestFromTheSameSpaceIsRejectedBeforeItIsDecrypted() {
        val n = node(VoidbindEncryption.newSpaceKey())
        val vault = client(n)
        val victim = vault.put(spaceId, envelope)
        val other = vault.put(spaceId, """{"v":1,"type":"answer","body":"someone else's"}""")
        // The node serves the other object's (validly sealed, same-key) manifest under the victim's id.
        n.blobs[victim.manifestBlob] = n.blobs.getValue(other.manifestBlob)
        assertFailsWith<VaultRefException.Integrity> { vault.get(victim.ref) }
    }

    @Test
    fun contentThatIsNotTheBlobTheManifestNamesIsRejected() {
        val key = VoidbindEncryption.newSpaceKey()
        val n = node(key)
        val vault = client(n)
        val put = vault.put(spaceId, envelope)
        val manifest = VaultFrame.openManifest(key, n.blobs.getValue(put.manifestBlob))

        // Re-sealed with the same key and file id: every frame decrypts and binds, but the bytes are
        // not the blob the manifest names — only the content hash catches it.
        val fileId = manifest.fileId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val (resealed, _) = VaultFrame.seal(key, envelope.encodeToByteArray(), fileId)
        n.blobs[manifest.content] = resealed
        assertFailsWith<VaultRefException.Integrity> { vault.get(put.ref) }

        // A flipped byte fails the frame's AEAD: also an integrity failure, never "absent".
        val tampered = resealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        n.blobs[manifest.content] = tampered
        assertFailsWith<VaultRefException.Integrity> { vault.get(put.ref) }
    }

    @Test
    fun aChangeWhoseIdDoesNotMatchItsBytesFailsTheRead() {
        val n = node(VoidbindEncryption.newSpaceKey())
        val put = client(n).put(spaceId, envelope)
        val c = n.changes.single()
        n.changes[0] = EncryptedChange(c.spaceId, "blake3:" + "00".repeat(32), c.parents, c.ciphertext)
        assertFailsWith<IllegalStateException> { client(n).get(put.ref) }
    }

    @Test
    fun putRefusesAnythingButAVersionedTypedEnvelopeAndUploadsNothing() {
        val n = node(VoidbindEncryption.newSpaceKey())
        listOf(
            "not json",
            "[1,2]",
            """{"type":"answer"}""",
            """{"v":2,"type":"answer"}""",
            """{"v":"1","type":"answer"}""",
            """{"v":1,"type":""}""",
            """{"v":1,"type":"answer"} trailing""",
            """{"v":1 "type":"answer"}""",
            """{"v":1.0,"type":"answer"}""",
            """{"v":1,"type":"answer","v":2}""",
            """{"v":1,"type":"answer","body":"${"x".repeat(VaultObjects.MAX_OBJECT_BYTES)}"}""",
        ).forEach { bad ->
            assertFailsWith<VaultRefException.InvalidObject>(bad.take(40)) { client(n).put(spaceId, bad) }
        }
        assertTrue(n.blobs.isEmpty() && n.changes.isEmpty())
        val objectRef = VaultRef.newObject(spaceId).toString()
        assertFailsWith<VaultRefException.Malformed> { client(n).put(objectRef, envelope) }
        assertFailsWith<VaultRefException.Malformed> { client(n).get("hv1:$spaceId") }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }
}
