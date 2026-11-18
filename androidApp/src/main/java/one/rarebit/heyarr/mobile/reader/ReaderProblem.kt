package one.rarebit.heyarr.mobile.reader

import one.rarebit.heyarr.mobile.net.CredentialUnavailableException
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.streamer.PublicationOpener
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Why the reader could not open a book, in words a person can act on (heyarr-kmp#240). The
 * reader used to print whatever it was handed, which for Readium is an error's `toString`:
 * "Could not fetch the file: org.readium.r2.shared.util.asset.AssetRetriever$RetrieveUrlError
 * $Reading@6836f4e" told nobody anything. Each kind here says what happened and what to try;
 * the raw error goes to the log, never to the screen. Pure, unit-tested.
 */
enum class ReaderProblem(val title: String, val detail: String) {
    /** 401/403: the node did not accept what this phone sent, or that it sent nothing. */
    REFUSED(
        "heyarr didn't let this phone open the book",
        "Check this device in Settings, then try again.",
    ),

    /** 404/410: the blob is not (or no longer) on the node. */
    GONE("This book is no longer on heyarr", "It may have been moved or removed. Go back and open it again."),

    /** No answer: offline, the node down, a timeout, a TLS failure. */
    UNREACHABLE("Can't reach heyarr", "Check the connection, then try again."),

    /** The node answered with a 5xx, or something that was not a response. */
    SERVER("heyarr couldn't send this book", "Try again in a moment."),

    /**
     * The device key would not sign (the phone is locked, or past its user-auth window):
     * [CredentialUnavailableException]. It clears once the phone is unlocked.
     */
    LOCKED(
        "Unlock this phone to read",
        "The book is fetched with this phone's key, which works only while it is unlocked.",
    ),

    /** A file the reader has no navigator for. */
    UNSUPPORTED("This book's format can't be read here", "The file is not one this reader can open."),

    /** Anything else: the file arrived but could not be read. */
    UNREADABLE("This book couldn't be opened", "The file may be damaged. Try again, or go back."),
    ;

    companion object {
        private const val MAX_CAUSE_DEPTH = 16

        private val REFUSALS = setOf(HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN)
        private val GONE_STATUSES = setOf(HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE)
        private val SERVER_ERRORS = HttpURLConnection.HTTP_INTERNAL_ERROR until HTTP_STATUS_LIMIT
        private const val HTTP_STATUS_LIMIT = 600

        /** The problem for an HTTP status the node answered with. */
        fun ofStatus(code: Int): ReaderProblem = when (code) {
            in REFUSALS -> REFUSED
            in GONE_STATUSES -> GONE
            in SERVER_ERRORS -> SERVER
            else -> UNREADABLE
        }

        /**
         * The problem behind a Readium error, from its cause chain: retrieving and opening
         * wrap the HTTP error that matters (`RetrieveUrlError.Reading` → `ReadError.Access` →
         * `HttpError`). A locked key wins wherever it shows up, since Readium files it as a
         * plain IO error.
         */
        fun of(error: Error): ReaderProblem {
            val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            if (chain.any { it is ThrowableError<*> && CredentialUnavailableException.isCause(it.throwable) }) {
                return LOCKED
            }
            return chain.firstNotNullOfOrNull(::kindOf) ?: UNREADABLE
        }

        private fun kindOf(error: Error): ReaderProblem? = when (error) {
            is HttpError.ErrorResponse -> ofStatus(error.status.code)

            is HttpError.MalformedResponse -> SERVER

            is HttpError -> UNREACHABLE

            is AssetRetriever.RetrieveUrlError.SchemeNotSupported,
            is AssetRetriever.RetrieveUrlError.FormatNotSupported,
            is PublicationOpener.OpenError.FormatNotSupported,
            -> UNSUPPORTED

            else -> null
        }

        /** The problem behind a failed PDF download ([PdfDownload.Failed] and what it wraps). */
        fun of(error: Throwable): ReaderProblem {
            if (CredentialUnavailableException.isCause(error)) return LOCKED
            val status = (error as? PdfDownload.Failed)?.status
            return when {
                status != null -> ofStatus(status)
                error is PdfDownload.Failed && error.cause is IOException -> UNREACHABLE
                error is PdfDownload.Failed -> UNREADABLE
                error is IOException -> UNREACHABLE
                else -> UNREADABLE
            }
        }
    }
}
