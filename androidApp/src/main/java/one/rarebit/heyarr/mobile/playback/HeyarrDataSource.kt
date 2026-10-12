package one.rarebit.heyarr.mobile.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import one.rarebit.heyarr.core.auth.Credential

/**
 * The bridge from a [PlaybackTarget] to a Media3 [DataSource.Factory] that streams the
 * authenticated, range-capable blob endpoint.
 *
 * Two things matter here and both are ADR-0013's design, not ours to reinvent:
 *
 *  1. **Auth on every read.** The blob endpoint requires the caller's credential, so
 *     the target's `Authorization` header is set as a *default request property* on
 *     the factory — ExoPlayer copies it onto every HTTP request it makes for this
 *     media item, including the many small range reads a seek produces.
 *  2. **Range/206 is Media3's, unchanged.** [OkHttpDataSource] issues `Range` headers
 *     and consumes `206 Partial Content` itself; a scrub becomes a fresh ranged read
 *     rather than a re-download from zero — the M10 win. We add auth and get seeking
 *     for free; we do not (and must not) add a player-shaped byte route or signed URL.
 *
 * Pure header assembly lives in [authRequestProperties] so the auth-injection contract
 * is unit-tested on plain JVM; wiring it into a live [OkHttpDataSource] and pulling a
 * real codec's bytes is the phone-gated half.
 */
@UnstableApi
object HeyarrDataSource {
    private const val AUTHORIZATION = "Authorization"

    fun authRequestProperties(target: PlaybackTarget): Map<String, String> = target.authHeaders()

    /**
     * The `Authorization` value for one read: the live one when the app can mint it
     * (an enrolled phone's possession proof lives about two minutes and is re-minted
     * by the library; a session token also lapses), else the value the target was
     * planned with. Pure, so the choice is unit-tested.
     *
     * A target planned as a guest stays headerless for its whole life: its stream URL is
     * bound to the principal that planned it, so stamping a credential minted by a sign-in
     * mid-playback onto it would get the next range read refused (Codex on #131).
     */
    fun authorization(live: String?, target: PlaybackTarget): String? = if (target.credential is Credential.Guest) {
        null
    } else {
        live?.takeIf { it.isNotBlank() } ?: target.authHeaders()[AUTHORIZATION]
    }

    /**
     * A factory whose every open re-stamps `Authorization` through [live]. The header
     * used to be frozen into the factory's default request properties at play time, and
     * because a request that already carries one is left alone by the app's interceptor,
     * a film outlived its proof: every range read after ~2 minutes came back 401.
     */
    fun factory(client: OkHttpClient, target: PlaybackTarget, live: () -> String? = { null }): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(client)
        return ResolvingDataSource.Factory(upstream) { spec ->
            val value = authorization(live(), target)
            if (value == null) spec else spec.withAdditionalHeaders(mapOf(AUTHORIZATION to value))
        }
    }
}
