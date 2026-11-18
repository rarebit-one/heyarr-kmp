package one.rarebit.heyarr.mobile.reader

import one.rarebit.heyarr.mobile.net.CredentialUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpStatus
import org.readium.r2.streamer.PublicationOpener
import java.io.IOException
import java.net.UnknownHostException

/**
 * What the reader tells a person when a book will not open (heyarr-kmp#240): the HTTP error
 * Readium wraps, read from its cause chain, and never the error's own `toString`.
 */
class ReaderProblemTest {
    /** As AssetRetriever hands back a failed read of the blob route. */
    private fun retrieveFailed(http: HttpError) = AssetRetriever.RetrieveUrlError.Reading(ReadError.Access(http))

    @Test fun theRefusalAfterProcessDeathReadsAsARefusal() {
        // The phone's case: the restored reader asked with no credential and the node said 401.
        assertEquals(ReaderProblem.REFUSED, ReaderProblem.of(retrieveFailed(HttpError.ErrorResponse(HttpStatus(401)))))
        assertEquals(ReaderProblem.REFUSED, ReaderProblem.of(retrieveFailed(HttpError.ErrorResponse(HttpStatus(403)))))
    }

    @Test fun statusesMapToWhatHappened() {
        assertEquals(ReaderProblem.GONE, ReaderProblem.of(retrieveFailed(HttpError.ErrorResponse(HttpStatus(404)))))
        assertEquals(ReaderProblem.GONE, ReaderProblem.ofStatus(410))
        assertEquals(ReaderProblem.SERVER, ReaderProblem.of(retrieveFailed(HttpError.ErrorResponse(HttpStatus(503)))))
        assertEquals(ReaderProblem.UNREADABLE, ReaderProblem.ofStatus(418))
    }

    @Test fun noAnswerIsUnreachable() {
        val offline = HttpError.Unreachable(ThrowableError(UnknownHostException("node")))
        assertEquals(ReaderProblem.UNREACHABLE, ReaderProblem.of(retrieveFailed(offline)))
        assertEquals(ReaderProblem.UNREACHABLE, ReaderProblem.of(retrieveFailed(HttpError.Timeout(DebugError("slow")))))
        assertEquals(ReaderProblem.SERVER, ReaderProblem.of(retrieveFailed(HttpError.MalformedResponse(null))))
    }

    @Test fun aLockedKeyWinsOverTheIoErrorReadiumFilesItAs() {
        val locked = CredentialUnavailableException(IllegalStateException("DEVICE_LOCKED"))
        assertEquals(ReaderProblem.LOCKED, ReaderProblem.of(retrieveFailed(HttpError.IO(locked))))
    }

    @Test fun formatsTheReaderCannotOpenAreUnsupported() {
        assertEquals(ReaderProblem.UNSUPPORTED, ReaderProblem.of(AssetRetriever.RetrieveUrlError.FormatNotSupported()))
        assertEquals(
            ReaderProblem.UNSUPPORTED,
            ReaderProblem.of(AssetRetriever.RetrieveUrlError.SchemeNotSupported(Url.Scheme("ftp"))),
        )
        assertEquals(ReaderProblem.UNSUPPORTED, ReaderProblem.of(PublicationOpener.OpenError.FormatNotSupported()))
    }

    @Test fun aFileThatArrivedButCannotBeReadIsUnreadable() {
        assertEquals(ReaderProblem.UNREADABLE, ReaderProblem.of(ReadError.Decoding("bad zip")))
        assertEquals(
            ReaderProblem.UNREADABLE,
            ReaderProblem.of(PublicationOpener.OpenError.Reading(ReadError.Decoding("bad opf"))),
        )
    }

    @Test fun aFailedPdfDownloadMapsTheSameWay() {
        assertEquals(ReaderProblem.REFUSED, ReaderProblem.of(PdfDownload.Failed("HTTP 401", status = 401)))
        assertEquals(ReaderProblem.SERVER, ReaderProblem.of(PdfDownload.Failed("HTTP 502", status = 502)))
        assertEquals(
            ReaderProblem.UNREACHABLE,
            ReaderProblem.of(PdfDownload.Failed("no route", UnknownHostException("node"))),
        )
        assertEquals(ReaderProblem.UNREADABLE, ReaderProblem.of(PdfDownload.Failed("could not store the file")))
        val locked = PdfDownload.Failed(
            "signing",
            CredentialUnavailableException(IllegalStateException("DEVICE_LOCKED")),
        )
        assertEquals(ReaderProblem.LOCKED, ReaderProblem.of(locked))
        assertEquals(ReaderProblem.UNREACHABLE, ReaderProblem.of(IOException("reset")))
    }

    @Test fun nothingShownNamesAClass() {
        for (problem in ReaderProblem.entries) {
            val shown = problem.title + " " + problem.detail
            assertFalse(
                "${problem.name} reads like code: $shown",
                Regex("""[A-Za-z]+\.[A-Za-z]+|\$|@[0-9a-f]""").containsMatchIn(shown),
            )
        }
    }
}
