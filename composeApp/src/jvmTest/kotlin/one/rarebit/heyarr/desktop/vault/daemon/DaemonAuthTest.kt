package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.core.auth.Credential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The API-credential selection: a configured write token becomes a bearer credential that sends
 * `Authorization: Bearer <token>` — the write path a headless daemon needs (device credentials are
 * read-floor, ADR-0067). The no-token fallback to the device credential is exercised on the box, not
 * here (it would shell out to the real `voidbind` CLI); its trigger — a null resolved token — is
 * covered by DaemonConfigTest.
 */
class DaemonAuthTest {

    @Test
    fun anInlineTokenBecomesABearerAuthorizationHeader() {
        val cred = bearerCredential(DaemonConfig(token = "heyarr_abc_secret"))
        assertNotNull(cred)
        assertEquals("Bearer heyarr_abc_secret", cred.asHeader()[Credential.HEADER])
    }

    @Test
    fun aBlankInlineTokenIsNotABearer() {
        // A blank token via config.json resolves to null (→ device fallback), never an empty Bearer.
        assertEquals(null, DaemonConfig(token = "   ").resolveApiToken { null })
    }
}
