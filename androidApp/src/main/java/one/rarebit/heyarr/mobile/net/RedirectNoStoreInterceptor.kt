package one.rarebit.heyarr.mobile.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Carries a redirect hop's `Cache-Control: no-store` onto the final response, for the
 * image loader only.
 *
 * The artwork redirect with `?size=` (heyarr-core ADR-0109) answers a thumbnail that is
 * still being derived with a 307 to the ORIGINAL blob, marked `no-store`, so the next
 * request for the same URL reaches the thumbnail once it is ready. OkHttp follows that
 * redirect transparently and the image loader only sees the final response — the
 * original blob, whose headers say `immutable` because a blob URL is its hash. Without
 * this, the loader files the full-size original under the artwork URL and never asks
 * again: on a phone that had seen a card before its thumbnail existed, the thumbnail
 * never arrived (#140).
 *
 * Application-level (not a network interceptor) because only here does the response
 * carry its `priorResponse` chain. A response reached without a `no-store` hop passes
 * through untouched, so a ready thumbnail and every other image keep their own headers.
 */
class RedirectNoStoreInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!reachedThroughNoStore(response)) return response
        return response.newBuilder().header(CACHE_CONTROL, NO_STORE).build()
    }

    companion object {
        private const val CACHE_CONTROL = "Cache-Control"
        private const val NO_STORE = "no-store"

        /** True iff any redirect hop before [response] said `no-store`. */
        fun reachedThroughNoStore(response: Response): Boolean =
            generateSequence(response.priorResponse) { it.priorResponse }.any { it.cacheControl.noStore }
    }
}
