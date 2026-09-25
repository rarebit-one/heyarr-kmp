package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.net.ProblemDetail
import one.rarebit.heyarr.mobile.search.SessionJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** Go's encoding/json escapes `&`, `<`, `>` as & etc. — every reader must decode them. */
class JsonEscapesTest {

    @Test fun sessionJsonDecodesUnicodeEscapes() {
        val a = SessionJson.parse(
            """{"kind":"device","principal_id":"a&b","device_key":"ed25519:00","scopes":["read"],"can_write":false,"management_authorized":false}""",
        )!!
        assertEquals("a&b", a.principalId)
        assertEquals(true, a.isDevice)
        assertEquals(true, a.isReadOnly)
        assertEquals(false, a.isReadOnlySession)
    }

    @Test fun problemDetailDecodesUnicodeEscapes() {
        assertEquals("<tv> & \"radio\"", ProblemDetail.of("""{"detail":"<tv> & \"radio\""}"""))
    }
}
