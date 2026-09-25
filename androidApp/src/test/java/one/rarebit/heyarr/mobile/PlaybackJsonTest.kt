package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.PlaybackJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackJsonTest {

    @Test fun parsesADirectPlan() {
        val plan = PlaybackJson.parse(
            """{"mode":"direct","url":"https://h/api/v1/blobs/x/content","mime":"video/mp4","reason":"ok"}""",
        )
        assertTrue(plan.isDirect)
        assertFalse(plan.isStream)
        assertEquals("https://h/api/v1/blobs/x/content", plan.url)
        assertEquals("video/mp4", plan.mime)
        assertEquals("ok", plan.reason)
        assertNull(plan.source)
    }

    @Test fun parsesAStreamPlanWithSource() {
        val body = """
            {"mode":"stream","url":"/api/v1/playback/stream/abc","mime":"video/mp4",
             "reason":"audio \"ac3\" not decodable",
             "source":{"container":"mp4","video":"h264","audio":"ac3","width":1920,"height":1080}}
        """.trimIndent()
        val plan = PlaybackJson.parse(body)
        assertTrue(plan.isStream)
        assertFalse(plan.isDirect)
        assertEquals("/api/v1/playback/stream/abc", plan.url)
        assertEquals("audio \"ac3\" not decodable", plan.reason)
        assertEquals("mp4", plan.source?.container)
        assertEquals("h264", plan.source?.video)
        assertEquals("ac3", plan.source?.audio)
        assertEquals(1920, plan.source?.width)
        assertEquals(1080, plan.source?.height)
    }

    @Test fun sourceFieldsDoNotLeakIntoTopLevel() {
        // "mime"/"url" inside source-shaped nesting must not be mistaken for the plan's.
        val plan = PlaybackJson.parse(
            """{"source":{"container":"mkv","video":"hevc","audio":"eac3"},"mode":"stream","url":"/s"}""",
        )
        assertEquals("/s", plan.url)
        assertNull(plan.mime)
        assertEquals("hevc", plan.source?.video)
    }

    @Test fun streamWithoutUrlIsNotAStream() {
        val plan = PlaybackJson.parse("""{"mode":"stream","reason":"pending"}""")
        assertFalse(plan.isStream)
        assertFalse(plan.isDirect)
    }

    @Test fun legacyDecisionShapeStillReadsAsDirect() {
        val plan = PlaybackJson.parse("""{"decision":"DIRECT","content_url":"https://h/x","remote":false}""")
        assertTrue(plan.isDirect)
        assertEquals("https://h/x", plan.url)
    }

    @Test fun legacyNonDirectIsNeither() {
        val plan = PlaybackJson.parse("""{"decision":"TRANSCODE","remote":false}""")
        assertFalse(plan.isDirect)
        assertFalse(plan.isStream)
        assertNull(plan.url)
    }

    @Test fun tolerantOfMissingFields() {
        val plan = PlaybackJson.parse("""{"remote":true}""")
        assertNull(plan.mode)
        assertNull(plan.url)
        assertNull(plan.mime)
        assertNull(plan.reason)
        assertNull(plan.source)
    }
}
