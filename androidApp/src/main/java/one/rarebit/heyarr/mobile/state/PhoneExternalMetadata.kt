package one.rarebit.heyarr.mobile.state

import one.rarebit.heyarr.core.state.ExternalMetadata
import java.io.File
import java.io.IOException

/**
 * The phone's side of `:core`'s [ExternalMetadata]: an OkHttp fetcher over a bare client
 * (the node's credential never travels to these hosts) with the phone's User-Agent.
 */
object PhoneExternalMetadata {
    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404
    const val USER_AGENT = "heyarr-mobile/0.3 (+https://github.com/rarebit-one/heyarr-mobile)"

    /**
     * A fetcher over a bare OkHttp client: a 200 body, null for a 404 ("nothing found"), and a
     * throw for any other failure, so `:core`'s [ExternalMetadata] retries it later instead of
     * caching an outage as a miss. Never the app's authenticated client.
     */
    fun okHttpFetch(client: okhttp3.OkHttpClient): (String) -> String? = { url ->
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ExternalMetadata.ACCEPT)
            .get().build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                HTTP_OK -> resp.body?.string()
                HTTP_NOT_FOUND -> null
                else -> throw IOException("Metadata provider returned HTTP ${resp.code}")
            }
        }
    }

    /** The disabled instance for previews and tests. */
    fun none(): ExternalMetadata {
        val dir = File(System.getProperty("java.io.tmpdir"), "heyarr-meta-none")
        return ExternalMetadata(dir, enabled = { false }, fetch = { null })
    }
}
