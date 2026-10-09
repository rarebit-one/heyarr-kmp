package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.voidwhichbinds.auth.DeviceCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceCredentialTest {

    @Test fun formatsWithTildeSeparator() {
        assertEquals("CERT~PROOF", DeviceCredential.format("CERT", "PROOF"))
    }

    @Test fun parseRoundTrips() {
        val (cert, proof) = DeviceCredential.parse("CERT~PROOF")
        assertEquals("CERT", cert)
        assertEquals("PROOF", proof)
    }

    @Test fun rejectsCertContainingSeparator() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceCredential.format("bad~cert", "proof")
        }
    }

    @Test fun deviceCredentialHeaderValue() {
        assertEquals("Device CERT~PROOF", Credential.Device("CERT", "PROOF").headerValue())
    }

    @Test fun sessionCredentialHeaderValue() {
        assertEquals("Bearer tok", Credential.Session("tok").headerValue())
    }
}
