package one.rarebit.heyarr.desktop.preview

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Each piece of fixture artwork gets its own blob hash, derived from a readable key (a work id,
 * an episode stem), so cards differ and the screenshot harness can map a request back to a file
 * in the preview-art directory ([PlaceholderArt]). These hashes name no real blob.
 */
object FixtureArt {
    private val keys = ConcurrentHashMap<String, String>()

    fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val hash = "blake3:" + digest.joinToString("") { "%02x".format(it) }
        keys[hash] = key
        return hash
    }

    fun path(key: String): String = "/api/v1/blobs/${hash(key)}/content"

    /** The readable key behind an artwork content path, or null when it isn't fixture artwork. */
    fun keyOf(contentPath: String): String? = keys[contentPath.removePrefix("/api/v1/blobs/").removeSuffix("/content")]
}

/** Shorthands for the fixture JSON templates, where a call with an argument trips ktlint's wrapping rules. */
val String.fixtureArtHash: String get() = FixtureArt.hash(this)

val String.fixtureArtPath: String get() = FixtureArt.path(this)
