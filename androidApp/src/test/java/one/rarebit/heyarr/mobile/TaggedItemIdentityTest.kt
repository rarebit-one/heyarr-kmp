package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.ItemResolver
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.personalstate.ItemKind
import one.rarebit.heyarr.mobile.personalstate.ItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #41 part 1: the `(kind, id)` tag encoding and the asset→work resolver. */
class TaggedItemIdentityTest {

    @Test fun workEncodesBareForBackCompat() {
        assertEquals("018f-c3", ItemRef.work("018f-c3").encode())
    }

    @Test fun taggedKindsRoundTrip() {
        assertEquals("asset:A1", ItemRef.asset("A1").encode())
        assertEquals("item:E1", ItemRef.item("E1").encode())
        assertEquals(ItemRef(ItemKind.ASSET, "A1"), ItemRef.decode("asset:A1"))
        assertEquals(ItemRef(ItemKind.ITEM, "E1"), ItemRef.decode("item:E1"))
    }

    @Test fun untaggedLegacyIdIsAWork() {
        assertEquals(ItemRef(ItemKind.WORK, "bare-id"), ItemRef.decode("bare-id"))
        // A work id that happens to carry a colon but no known kind prefix is still a work id.
        assertEquals(ItemRef(ItemKind.WORK, "urn:x:y"), ItemRef.decode("urn:x:y"))
    }

    /** Routes canned works/assets/editions bodies by URL so the resolver's join can be exercised. */
    private class Fake(private val byUrl: Map<String, String>) : HttpTransport {
        override fun get(url: String, headers: Map<String, String>) =
            byUrl[url]?.let { HttpResponse(200, it) } ?: HttpResponse(404, "")
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
            HttpResponse(404, "")
    }

    private val base = "https://n"

    private fun resolver(fake: Fake) = ItemResolver(LibraryClient(fake, base, Credential.Session("t")))

    @Test fun resolvesAWorkIdStraightThrough() {
        val fake = Fake(mapOf("$base/api/v1/works/W1" to """{"id":"W1","title":"Album"}"""))
        val r = resolver(fake).resolve("W1")
        assertEquals("W1", r?.itemId)
        assertEquals("Album", r?.work?.title)
        assertNull(r?.asset)
    }

    @Test fun resolvesAnAssetToItsWorkViaEdition() {
        val fake = Fake(
            mapOf(
                "$base/api/v1/assets/A1" to
                    """{"id":"A1","edition_id":"E1","filename":"01 - Song.flac","blob_hash":"h","mime":"audio/flac"}""",
                "$base/api/v1/editions/E1" to """{"id":"E1","work_id":"W1","label":"Deluxe"}""",
                "$base/api/v1/works/W1" to """{"id":"W1","title":"Album"}""",
            ),
        )
        val r = resolver(fake).resolve("asset:A1")
        // The entry id is preserved (so a reader can remove/re-star exactly this entry),
        // it folds to its parent work, and the file itself rides along for display/playback.
        assertEquals("asset:A1", r?.itemId)
        assertEquals("W1", r?.work?.id)
        assertEquals("A1", r?.asset?.id)
        assertEquals("h", r?.asset?.blobHash)
    }

    @Test fun aMissingAssetResolvesToNullNotACrash() {
        val fake = Fake(emptyMap()) // everything 404s
        assertNull(resolver(fake).resolve("asset:gone"))
    }
}
