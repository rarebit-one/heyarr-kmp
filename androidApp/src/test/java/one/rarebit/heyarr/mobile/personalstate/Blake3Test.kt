package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.crypto.Blake3
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * :core's BLAKE3-256 (what `PersonalStateId` hashes with) against Go-minted KATs
 * (`github.com/zeebo/blake3`, what heyarr-core hashes with). Empty, "abc", one
 * 64-byte block, one 1024-byte chunk, and a 3000-byte multi-chunk tree — the last
 * two prove the CV-stack tree path, not just a single block. These are the official
 * BLAKE3 vectors (bytes = i mod 251).
 */
class Blake3Test {
    private fun hex(b: ByteArray) = Hex.encode(Blake3.hash(b))

    private fun pattern(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test fun empty() = assertEquals(
        "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
        hex(ByteArray(0)),
    )

    @Test fun abc() =
        assertEquals("6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85", hex("abc".encodeToByteArray()))

    @Test fun block64() = assertEquals(
        "4eed7141ea4a5cd4b788606bd23f46e212af9cacebacdc7d1f4c6dc7f2511b98",
        hex(
            ByteArray(64) {
                it.toByte()
            },
        ),
    )

    @Test fun chunk1024() =
        assertEquals("42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7", hex(pattern(1024)))

    @Test fun multiChunk3000() =
        assertEquals("5fade288bf27444bee55ba2babb98c3c922c1e84c2e445e7d1f6da24756f5060", hex(pattern(3000)))
}
