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
        val hasAuth = req.header(AUTHORIZATION) != null
        if (!shouldStamp(req.url.toString(), baseUrl(), hasAuth)) return chain.proceed(req)
        val value = header.current() ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header(AUTHORIZATION, value).build())
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
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
