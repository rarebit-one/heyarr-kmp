package one.rarebit.heyarr.mobile.net

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/**
 * Stamps the live credential onto requests that go to OUR node's `/api/v1` and carry
 * no `Authorization` of their own — posters and range reads issued by the image
 * loader and the media data source, which never see a [one.rarebit.heyarr.core.auth.Credential].
 *
 * Deliberately narrow: same scheme, host and port as the configured base URL, and a
 * path under `/api/v1/`. A poster URL a discovery provider hands back (another host)
 * goes out bare — the credential never leaks off the node. API calls made through
 * [OkHttpTransport] already carry their header, so this is a no-op for them.
 *
 * No 401 retry here: that is [DeviceAuthTransport]'s job for API calls, and a failed
 * image simply reloads on the next composition.
 */
class AuthInterceptor(private val baseUrl: () -> String, private val header: AuthHeaderSource) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // A guest-planned stream carries NO_AUTH_MARKER so auth is never stamped onto it
        // mid-playback (#132). Strip the marker here — it must not reach the wire.
        if (req.header(NO_AUTH_MARKER) != null) {
            return chain.proceed(req.newBuilder().removeHeader(NO_AUTH_MARKER).build())
        }
        val hasAuth = req.header(AUTHORIZATION) != null
        val value = if (shouldStamp(req.url.toString(), baseUrl(), hasAuth)) header.current() else null
        return chain.proceed(if (value != null) req.newBuilder().header(AUTHORIZATION, value).build() else req)
    }

    companion object {
        const val AUTHORIZATION = "Authorization"

        /**
         * Marker added by [one.rarebit.heyarr.mobile.playback.HeyarrDataSource] for
         * guest-planned streams: [intercept] strips it and skips stamping so a sign-in
         * mid-playback cannot re-credential a guest-target range read (#132).
         */
        const val NO_AUTH_MARKER = "X-Heyarr-No-Auth"
        const val NO_AUTH_VALUE = "1"

        private const val API_PREFIX = "/api/v1/"

        /**
         * Pure, unit-tested: stamp iff the request has no auth, targets the base URL's
         * origin (scheme + host + effective port), and is under `/api/v1/`.
         */
        fun shouldStamp(requestUrl: String, baseUrl: String, hasAuth: Boolean): Boolean {
            if (hasAuth) return false
            val req = runCatching { URI(requestUrl) }.getOrNull() ?: return false
            val base = runCatching { URI(baseUrl) }.getOrNull() ?: return false
            if (!sameOrigin(req, base)) return false
            return (req.path ?: "").startsWith(API_PREFIX)
        }

        private fun sameOrigin(a: URI, b: URI): Boolean = a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)

        private fun effectivePort(u: URI): Int = when {
            u.port != -1 -> u.port
            u.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
    }
}
