package one.rarebit.heyarr.mobile.state

import one.rarebit.heyarr.core.state.ExternalMetadata
import java.io.File

/**
 * The phone's side of `:core`'s [ExternalMetadata]: an OkHttp fetcher over a bare client
 * (the node's credential never travels to these hosts) with the phone's User-Agent.
 */
object PhoneExternalMetadata {
    private const val HTTP_OK = 200
    const val USER_AGENT = "heyarr-mobile/0.3 (+https://github.com/rarebit-one/heyarr-mobile)"

    /** A fetcher over a bare OkHttp client: a 200 body, else null. Never the app's authenticated client. */
    fun okHttpFetch(client: okhttp3.OkHttpClient): (String) -> String? = { url ->
        runCatching {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ExternalMetadata.ACCEPT)
                .get().build()
            client.newCall(req).execute().use { resp -> if (resp.code == HTTP_OK) resp.body?.string() else null }
        }.getOrNull()
    }

    /** The disabled instance for previews and tests. */
    fun none(): ExternalMetadata {
        val dir = File(System.getProperty("java.io.tmpdir"), "heyarr-meta-none")
        return ExternalMetadata(dir, enabled = { false }, fetch = { null })
    }
}
