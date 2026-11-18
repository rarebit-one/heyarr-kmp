package one.rarebit.heyarr.mobile.reader

import one.rarebit.heyarr.mobile.net.AuthInterceptor
import one.rarebit.heyarr.mobile.net.CredentialUnavailableException
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest

/**
 * Readium fetches a remote publication through its own HTTP client (range reads over
 * the blob route), not our OkHttp — so the credential rides a request callback here,
 * under the same rule as net/AuthInterceptor: only our node, only under /api/v1/.
 */
object ReaderHttp {
    fun client(baseUrl: () -> String, header: () -> String?): DefaultHttpClient = DefaultHttpClient(
        callback = object : DefaultHttpClient.Callback {
            override suspend fun onStartRequest(request: HttpRequest): Try<HttpRequest, HttpError> {
                val hasAuth = request.headers.keys.any { it.equals("Authorization", ignoreCase = true) }
                if (!AuthInterceptor.shouldStamp(
                        request.url.toString(),
                        baseUrl(),
                        hasAuth,
                    )
                ) {
                    return Try.success(request)
                }
                // A locked phone's key will not sign (CredentialUnavailableException). Readium calls
                // this outside its own IO handling, so a throw here would end the reader's
                // coroutine and the app with it; as a failed request it reaches the reader, which
                // asks for the phone to be unlocked (ReaderProblem.LOCKED, heyarr-kmp#240).
                val value = try {
                    header()
                } catch (e: CredentialUnavailableException) {
                    return Try.failure(HttpError.IO(e))
                } ?: return Try.success(request)
                return Try.success(request.buildUpon().setHeader("Authorization", value).build())
            }
        },
    )
}
