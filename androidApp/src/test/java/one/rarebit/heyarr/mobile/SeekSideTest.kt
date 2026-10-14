package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.ui.screens.seekSide
import org.junit.Assert.assertEquals
import org.junit.Test

/** Double tap to seek: the outer thirds of the picture jump, the middle third never does. */
class SeekSideTest {

    @Test
    fun `left third seeks back, right third seeks ahead`() {
        assertEquals(-1, seekSide(0f, 900))
        assertEquals(-1, seekSide(299f, 900))
        assertEquals(1, seekSide(601f, 900))
        assertEquals(1, seekSide(899f, 900))
    }

    @Test
    fun `middle third is left alone`() {
        assertEquals(0, seekSide(300f, 900))
        assertEquals(0, seekSide(450f, 900))
        assertEquals(0, seekSide(600f, 900))
    }

    @Test
    fun `an unmeasured picture never seeks`() {
        assertEquals(0, seekSide(10f, 0))
    }
}
