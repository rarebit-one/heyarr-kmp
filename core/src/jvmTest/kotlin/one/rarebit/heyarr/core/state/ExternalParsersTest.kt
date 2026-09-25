package one.rarebit.heyarr.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The readers over each public source's answer, against the shapes observed live. */
class ExternalParsersTest {

    @Test fun tvmazeShowAndEpisodes() {
        val show = ExternalParsers.tvmazeShow(
            """{"id":24594,"url":"https://www.tvmaze.com/shows/24594/yellowstone","name":"Yellowstone","premiered":"2018-06-20","image":{"medium":"https://static.tvmaze.com/m.jpg","original":"https://static.tvmaze.com/o.jpg"},"summary":"<p><b>Yellowstone </b>follows the Dutton family &amp; co.</p>"}""",
        )!!
        assertEquals("https://static.tvmaze.com/o.jpg", show.imageUrl)
        assertEquals("Yellowstone follows the Dutton family & co.", show.synopsis)
        assertEquals(24594L, show.tvmazeId)
        assertEquals("TVmaze", show.source)
        val eps = ExternalParsers.tvmazeEpisodes(
            """[{"id":1,"season":4,"number":2,"name":"Phantom Pain","airdate":"2021-11-07","image":{"medium":"https://static.tvmaze.com/e.jpg"},"summary":"<p>The Yellowstone recovers.</p>"},{"id":2,"season":5,"number":8,"name":"Desire Is All You Need","airdate":"2022-12-31","image":null,"summary":null}]""",
        )
        assertEquals(2, eps.size)
        assertEquals("Phantom Pain", eps[0].name)
        assertEquals("The Yellowstone recovers.", eps[0].summary)
        assertEquals("https://static.tvmaze.com/e.jpg", eps[0].imageUrl)
        assertNull(eps[1].imageUrl)
        assertEquals(8, eps[1].number)
    }

    @Test fun wikipediaOpenLibraryItunesMusicBrainz() {
        val w = ExternalParsers.wikipedia(
            """{"type":"standard","title":"Sintel","thumbnail":{"source":"https://upload.wikimedia.org/x/330px-Sintel_poster.jpg?utm_source=en"},"extract":"Sintel is a 2010 animated fantasy short film.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Sintel"}}}""",
        )!!
        assertEquals("https://upload.wikimedia.org/x/330px-Sintel_poster.jpg", w.imageUrl)
        assertEquals("Sintel is a 2010 animated fantasy short film.", w.synopsis)
        assertEquals("https://en.wikipedia.org/wiki/Sintel", w.sourceUrl)
        assertNull(ExternalParsers.wikipedia("""{"type":"disambiguation","title":"Blue"}"""))
        val ol = ExternalParsers.openLibrary(
            """{"docs":[{"author_name":["Susanna Clarke"],"cover_i":10226290,"first_publish_year":2020,"title":"Piranesi","first_sentence":["When the Moon rose in the Third Northern Hall I went to the Ninth Vestibule."]}]}""",
        )!!
        assertEquals("https://covers.openlibrary.org/b/id/10226290-L.jpg", ol.imageUrl)
        assertEquals("When the Moon rose in the Third Northern Hall I went to the Ninth Vestibule.", ol.synopsis)
        assertNull(ExternalParsers.openLibrary("""{"docs":[]}"""))
        val it = ExternalParsers.itunes(
            """{"results":[{"collectionName":"ATP","artworkUrl100":"https://a/100.jpg","artworkUrl600":"https://a/600.jpg","collectionViewUrl":"https://podcasts.apple.com/x"}]}""",
        )!!
        assertEquals("https://a/600.jpg", it.imageUrl)
        assertEquals(
            "e75c0549-ad55-39e3-8025-c72c5d4a3c5d",
            ExternalParsers.musicBrainzReleaseGroup(
                """{"release-groups":[{"id":"e75c0549-ad55-39e3-8025-c72c5d4a3c5d","title":"Kid A"}]}""",
            ),
        )
    }

    @Test fun feedImageAndDescription() {
        val rss = """<?xml version="1.0"?><rss><channel><title>ATP</title><description><![CDATA[Three <b>nerds</b> discussing tech.]]></description><itunes:image href="https://cdn/atp.jpg"/><image><url>https://cdn/small.png</url><title>x</title></image></channel></rss>"""
        assertEquals("https://cdn/atp.jpg", ExternalParsers.feedImage(rss))
        assertEquals("Three nerds discussing tech.", ExternalParsers.feedDescription(rss))
        val plain = """<rss><channel><title>Cloudflare</title><image><url>https://blog.cloudflare.com/favicon.ico</url><title>Cloudflare</title></image></channel></rss>"""
        assertEquals(
            "https://blog.cloudflare.com/favicon.ico",
            ExternalParsers.feedImage(plain),
            "a favicon is a last resort but still an image",
        )
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Blog</title><subtitle>Notes on things</subtitle><logo>https://blog/logo.png</logo></feed>"""
        assertEquals("https://blog/logo.png", ExternalParsers.feedImage(atom))
        assertEquals("Notes on things", ExternalParsers.feedDescription(atom))
        assertNull(ExternalParsers.feedImage("<rss><channel><title>none</title></channel></rss>"))
    }
}
