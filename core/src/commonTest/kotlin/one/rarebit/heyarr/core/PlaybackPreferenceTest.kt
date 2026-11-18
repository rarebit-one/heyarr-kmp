package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.playback.Copies
import one.rarebit.heyarr.core.playback.CopyFile
import one.rarebit.heyarr.core.playback.DisplayFit
import one.rarebit.heyarr.core.playback.PlaybackPreference
import one.rarebit.heyarr.core.playback.QualityPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The person's synced preference, its device override, and the cap under it (heyarr-core#887). */
class PlaybackPreferenceTest {
    private data class F(
        override val id: String,
        override val probedHeight: Int?,
        override val sizeBytes: Long? = null,
        override val probedHdr: Boolean? = false,
        override val filename: String? = null,
        override val editionLabel: String? = null,
        override val mime: String? = "video/x-matroska",
        override val role: String? = "primary",
        override val blobHash: String? = "blake3:$id",
        override val isPlayable: Boolean = true,
        override val sourcePath: String? = null,
    ) : CopyFile

    private val uhd = F("uhd", 2160, 40L shl 30)
    private val fhd = F("fhd", 1080, 6L shl 30)
    private val hd = F("hd", 720, 2L shl 30)
    private val tv = DisplayFit(height = 2160)
    private val phone = DisplayFit(height = 1080)

    @Test fun theRegisterValueIsThePlanPreferenceAndRoundTrips() {
        val cases = listOf(
            PlaybackPreference() to """{"quality":"fit"}""",
            PlaybackPreference(QualityPreference.BEST, maxHeight = 1080) to """{"quality":"best","max_height":1080}""",
            PlaybackPreference(QualityPreference.DATA_SAVER, maxBitrateBps = 4_000_000) to
                """{"quality":"data_saver","max_bitrate_bps":4000000}""",
        )
        for ((pref, json) in cases) {
            assertEquals(json, pref.planJson())
            assertEquals(pref, PlaybackPreference.parse(json))
        }
    }

    @Test fun aValueThisClientCannotReadIsNoPreference() {
        assertNull(PlaybackPreference.parse(null))
        assertNull(PlaybackPreference.parse(""))
        assertNull(PlaybackPreference.parse("best"), "a bare key is not the register's shape")
        assertNull(PlaybackPreference.parse("""{"quality":"cinema"}"""), "a quality this client does not know")
        assertEquals(
            PlaybackPreference(QualityPreference.FIT),
            PlaybackPreference.parse("""{"quality":"fit","max_height":0,"max_bitrate_bps":-3}"""),
            "a cap that is not positive is dropped",
        )
    }

    @Test fun theDeviceOverrideTakesTheQualityAndThePersonKeepsTheCap() {
        val account = PlaybackPreference(QualityPreference.BEST, maxHeight = 1080)
        assertEquals(account, PlaybackPreference.resolve(account, null), "no override: the account default")
        assertEquals(
            PlaybackPreference(QualityPreference.DATA_SAVER, maxHeight = 1080),
            PlaybackPreference.resolve(account, QualityPreference.DATA_SAVER),
        )
        assertEquals(PlaybackPreference(), PlaybackPreference.resolve(null, null), "neither: fit, no cap")
        assertEquals(
            PlaybackPreference(QualityPreference.BEST),
            PlaybackPreference.resolve(null, QualityPreference.BEST),
        )
    }

    @Test fun thePersonsCapHoldsUnderEveryQuality() {
        val copies = Copies.of(listOf(uhd, fhd, hd))
        val cases = listOf(
            Triple(PlaybackPreference(QualityPreference.BEST), tv, "uhd"),
            Triple(PlaybackPreference(QualityPreference.BEST, maxHeight = 1080), tv, "fhd"),
            Triple(PlaybackPreference(QualityPreference.FIT, maxHeight = 1080), tv, "fhd"),
            Triple(PlaybackPreference(QualityPreference.FIT, maxHeight = 2160), phone, "fhd"),
            Triple(PlaybackPreference(QualityPreference.DATA_SAVER, maxHeight = 1080), tv, "hd"),
        )
        for ((pref, display, want) in cases) {
            assertEquals(
                want,
                Copies.pick(copies, display, pref)?.asset?.id,
                "$pref on a ${display.height}-line screen",
            )
        }
    }

    @Test fun theDeclaredHeightAgreesWithTheNodesRule() {
        val uhdCopy = Copies.read(uhd)
        assertEquals(2160, Copies.declaredMaxHeight(uhdCopy, phone, PlaybackPreference(QualityPreference.BEST)))
        assertEquals(
            1440,
            Copies.declaredMaxHeight(uhdCopy, phone, PlaybackPreference(QualityPreference.BEST, maxHeight = 1440)),
            "best stops at the person's cap",
        )
        assertEquals(
            720,
            Copies.declaredMaxHeight(uhdCopy, tv, PlaybackPreference(QualityPreference.FIT, maxHeight = 720)),
        )
        assertEquals(
            2160,
            Copies.declaredMaxHeight(
                uhdCopy,
                phone,
                PlaybackPreference(QualityPreference.DATA_SAVER, maxHeight = 720),
                chosen = true,
            ),
            "a copy picked by hand is the picture the person asked for",
        )
    }
}
