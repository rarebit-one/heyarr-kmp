package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.library.Bytes
import one.rarebit.heyarr.core.playback.Copies
import one.rarebit.heyarr.core.playback.CopyFile
import one.rarebit.heyarr.core.playback.DisplayFit
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.core.playback.ReleaseName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CopiesTest {
    private data class F(
        override val id: String,
        override val filename: String? = null,
        override val editionLabel: String? = null,
        override val sizeBytes: Long? = null,
        override val mime: String? = "video/x-matroska",
        override val role: String? = "primary",
        override val blobHash: String? = "blake3:$id",
        override val isPlayable: Boolean = true,
        override val probedWidth: Int? = null,
        override val probedHeight: Int? = null,
        override val probedHdr: Boolean? = null,
        override val sourcePath: String? = null,
    ) : CopyFile

    private val gb = 1024L * 1024 * 1024
    private val uhdHdr = F("uhd", "Film.2160p.UHD.BluRay.REMUX.HDR.HEVC.mkv", "Remux-2160p", 46 * gb)
    private val fhd = F("fhd", "Film.1080p.WEB-DL.x264.mkv", "WEBDL-1080p", 6 * gb)
    private val hd = F("hd", "Film.720p.WEB.x264.mp4", null, 2 * gb, mime = "video/mp4")

    private val phone = DisplayFit(height = 1080, supportsHdr = true)
    private val sdrLaptop = DisplayFit(height = 1080, supportsHdr = false)
    private val sdrUhd = DisplayFit(height = 2160, supportsHdr = false)
    private val hdrUhd = DisplayFit(height = 2160, supportsHdr = true)

    private fun pick(
        files: List<F>,
        display: DisplayFit,
        pref: QualityPreference = QualityPreference.FIT,
        chosen: String? = null,
        default: String? = null,
    ) = Copies.pick(Copies.of(files), display, pref, chosen, default)?.asset?.id

    private data class Row(
        val why: String,
        val files: List<F>,
        val display: DisplayFit,
        val pref: QualityPreference,
        val want: String?,
        val default: String? = null,
    )

    @Test fun theAcceptanceCaseThePhonePlaysTheTenEightyCopyByDefault() {
        assertEquals("fhd", pick(listOf(uhdHdr, fhd), phone))
    }

    @Test fun aChosenCopyWinsWhateverTheScreen() {
        assertEquals("uhd", pick(listOf(uhdHdr, fhd), phone, chosen = "uhd"))
        // A choice that no longer exists (the copy was removed) falls back to the rule.
        assertEquals("fhd", pick(listOf(uhdHdr, fhd), phone, chosen = "gone"))
    }

    @Test fun selectionTable() {
        val sdrUhdCopy = F("uhd-sdr", "Film.2160p.WEB-DL.SDR.mkv", null, 20 * gb)
        val unlabelled = F("plain", "Film.mkv", null, 9 * gb)
        val fit = QualityPreference.FIT
        val rows = listOf(
            Row("best quality takes the tallest", listOf(fhd, uhdHdr, hd), phone, QualityPreference.BEST, "uhd"),
            Row("data saver stops at 720p", listOf(fhd, uhdHdr, hd), phone, QualityPreference.DATA_SAVER, "hd"),
            Row("a 4K HDR screen takes the 4K HDR copy", listOf(fhd, uhdHdr), hdrUhd, fit, "uhd"),
            Row("a 4K SDR screen prefers SDR over HDR", listOf(fhd, uhdHdr), sdrUhd, fit, "fhd"),
            Row("a 4K SDR screen takes a 4K SDR copy", listOf(fhd, uhdHdr, sdrUhdCopy), sdrUhd, fit, "uhd-sdr"),
            Row("an HDR screen takes HDR on a tie", listOf(sdrUhdCopy, uhdHdr), hdrUhd, fit, "uhd"),
            Row("best on SDR still prefers SDR", listOf(uhdHdr, fhd), sdrLaptop, QualityPreference.BEST, "fhd"),
            Row("nothing fits: the smallest copy above the cap", listOf(uhdHdr, fhd), DisplayFit(480), fit, "fhd"),
            Row("data saver with nothing that small", listOf(uhdHdr, fhd), phone, QualityPreference.DATA_SAVER, "fhd"),
            Row("no stated height beats one above the cap", listOf(uhdHdr, unlabelled), phone, fit, "plain"),
            Row("a stated fit beats no stated height", listOf(unlabelled, fhd), phone, fit, "fhd"),
            Row(
                "only unlabelled copies: the node's default",
                listOf(unlabelled, unlabelled.copy(id = "plain2")),
                phone,
                fit,
                "plain2",
                default = "plain2",
            ),
            Row(
                "two copies at one height: the larger",
                listOf(fhd.copy(id = "fhd-small", sizeBytes = 2 * gb), fhd),
                phone,
                fit,
                "fhd",
            ),
            Row(
                "data saver takes the smaller of two 720p copies",
                listOf(hd, hd.copy(id = "hd-small", sizeBytes = gb)),
                phone,
                QualityPreference.DATA_SAVER,
                "hd-small",
            ),
            Row("a single copy is simply it", listOf(uhdHdr), DisplayFit(480), fit, "uhd"),
            Row("no copies at all", emptyList(), phone, fit, null),
        )
        for (r in rows) {
            assertEquals(r.want, pick(r.files, r.display, r.pref, default = r.default), r.why)
        }
    }

    @Test fun chooseSaysWhetherThePickWasThePersons() {
        val byRule = Copies.choose(listOf(uhdHdr, fhd), phone, defaultId = "uhd")!!
        assertEquals("fhd", byRule.picked.asset.id)
        assertEquals(false, byRule.chosen)
        assertEquals(2, byRule.copies.size)
        val byHand = Copies.choose(listOf(uhdHdr, fhd), phone, chosenId = "uhd")!!
        assertEquals("uhd", byHand.picked.asset.id)
        assertTrue(byHand.chosen)
        assertNull(Copies.choose(emptyList<F>(), phone))
    }

    @Test fun aProbedHeightBeatsTheReleaseName() {
        // The label says 2160p; the node measured a 1080-line frame.
        val mislabelled = F("x", "Film.2160p.mkv", "Remux-2160p", probedHeight = 1080, probedHdr = false)
        val c = Copies.read(mislabelled)
        assertEquals(1080, c.height)
        assertEquals(false, c.hdr)
        assertTrue(c.probed)
        assertTrue(Copies.read(F("y", "Film.2160p.mkv", probedHeight = 2160, probedHdr = true)).hdr)
    }

    @Test fun aReleaseNamedHdrIsHdrWhateverAnOldProbeSays() {
        // A probe taken before heyarr-core#885 read the transfer characteristic says `hdr: false`.
        val c = Copies.read(F("x", "Film.2160p.HDR.mkv", "Remux-2160p", probedHeight = 2160, probedHdr = false))
        assertEquals(2160, c.height)
        assertTrue(c.hdr)
    }

    @Test fun theLabelIsReadBeforeTheFilename() {
        val c = Copies.read(F("x", "Film.720p.mkv", "Bluray-1080p"))
        assertEquals(1080, c.height)
        assertEquals(false, c.probed)
        assertEquals(720, Copies.read(F("y", "Film.720p.mkv", "Default")).height)
    }

    @Test fun probedFramesReadAsTheirLineClass() {
        assertEquals(1080, Copies.nominalHeight(1920, 800)) // 2.40:1 scope
        assertEquals(2160, Copies.nominalHeight(3840, 1608))
        assertEquals(2160, Copies.nominalHeight(4096, 2160)) // DCI
        assertEquals(1080, Copies.nominalHeight(1440, 1080)) // 4:3
        assertEquals(720, Copies.nominalHeight(1280, 536))
        assertEquals(576, Copies.nominalHeight(720, 576))
        assertEquals(1080, Copies.nominalHeight(null, 1080))
        // The node sends no width (heyarr-core#885): a letterboxed height reads as its class.
        assertEquals(1080, Copies.nominalHeight(null, 800))
        assertEquals(2160, Copies.nominalHeight(null, 1608))
        assertEquals(720, Copies.nominalHeight(null, 536))
        assertEquals(1440, Copies.nominalHeight(null, 1440))
        assertEquals(300, Copies.nominalHeight(null, 300))
        assertEquals(576, Copies.nominalHeight(null, 576)) // PAL is still PAL
        assertEquals(1080, Copies.nominalHeight(null, 1040)) // 1.85:1
    }

    @Test fun releaseNames() {
        assertEquals(2160, ReleaseName.height("Remux-2160p"))
        assertEquals(1080, ReleaseName.height("Film (2019) [1080p]"))
        assertEquals(1080, ReleaseName.height("film.1080i.hdtv"))
        assertEquals(2160, ReleaseName.height("Film 4K"))
        assertEquals(2160, ReleaseName.height("Film UHD BluRay"))
        assertNull(ReleaseName.height("Film 2019 x264"))
        assertNull(ReleaseName.height("Film.1080x.mkv"))

        assertEquals(true, ReleaseName.hdr("Film.2160p.HDR10+.mkv"))
        assertEquals(true, ReleaseName.hdr("Film.2160p.DV.HEVC"))
        assertEquals(true, ReleaseName.hdr("Film 2160p Dolby Vision"))
        assertEquals(false, ReleaseName.hdr("Film.2160p.SDR.mkv"))
        assertNull(ReleaseName.hdr("Film.1080p.DVD.mkv"))
    }

    @Test fun onlyPlayablePrimaryVideoFilesAreCopies() {
        val files = listOf(
            fhd,
            F("sub", "Film.en.srt", mime = "text/plain", role = "subtitle"),
            F("art", "poster.jpg", mime = "image/jpeg", role = "artwork"),
            F("gone", "Film.720p.mkv", isPlayable = false),
            F("sniffed", "Film.480p.avi", mime = "application/octet-stream"),
            F("song", "track.flac", mime = "audio/flac"),
        )
        assertEquals(listOf("fhd", "sniffed"), Copies.of(files).map { it.asset.id })
    }

    @Test fun labels() {
        assertEquals("2160p HDR · 46.0 GB", Copies.read(uhdHdr).label)
        assertEquals("1080p · 6.0 GB", Copies.read(fhd).label)
        assertEquals("Film.mkv", Copies.read(F("plain", "Film.mkv")).label)
        assertEquals("HDR", Copies.read(F("h", "Film.HDR.mkv")).picture)
    }

    @Test fun declaredMaxHeight() {
        val uhd = Copies.read(uhdHdr)
        assertEquals(1080, Copies.declaredMaxHeight(uhd, phone, QualityPreference.FIT))
        assertEquals(720, Copies.declaredMaxHeight(uhd, phone, QualityPreference.DATA_SAVER))
        // Best quality and a hand-picked copy are not downscaled by the plan…
        assertEquals(2160, Copies.declaredMaxHeight(uhd, phone, QualityPreference.BEST))
        assertEquals(2160, Copies.declaredMaxHeight(uhd, phone, QualityPreference.FIT, chosen = true))
        // …unless the app bounds what it will have re-encoded for it.
        assertEquals(1080, Copies.declaredMaxHeight(uhd, phone, QualityPreference.BEST, ceiling = 1080))
        assertEquals(1080, Copies.declaredMaxHeight(null, phone, QualityPreference.BEST))
    }

    @Test fun preferenceKeysRoundTrip() {
        for (p in QualityPreference.entries) assertEquals(p, QualityPreference.of(p.key))
        assertEquals(QualityPreference.FIT, QualityPreference.of(null))
        assertEquals(QualityPreference.FIT, QualityPreference.of("nonsense"))
    }

    @Test fun bytes() {
        assertEquals("512 B", Bytes.format(512))
        assertEquals("3.2 KB", Bytes.format(3277))
        assertEquals("812 MB", Bytes.format(812L * 1024 * 1024))
        assertEquals("1.4 GB", Bytes.format((1.4 * gb).toLong()))
        assertEquals("46.6 GB", Bytes.format((46.6 * gb).toLong()))
    }
}
