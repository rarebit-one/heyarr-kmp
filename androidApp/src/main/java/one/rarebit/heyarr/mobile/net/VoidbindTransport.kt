package one.rarebit.heyarr.mobile.net
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.BuildConfig
import java.util.concurrent.TimeUnit
import one.rarebit.voidbind.net.HttpResponse as VoidbindResponse
import one.rarebit.voidbind.net.HttpTransport as VoidbindHttpTransport

/**
 * The app's actual of voidbind-client's blocking [VoidbindHttpTransport] seam — OkHttp,
 * raw bytes in/out, never parsing a body. It backs every voidbind-client call this
 * app makes: the weblogin broker (`WebLoginClient`) and the pairing relay
 * (`RelayClient` inside `DevicePairing`, which polls with [sleep]).
 */
class OkHttpVoidbindTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : VoidbindHttpTransport {

    override fun get(url: String): VoidbindResponse = execute(Request.Builder().url(url).get().build())

    override fun post(url: String, body: ByteArray?, contentType: String?): VoidbindResponse {
        val rb = (body ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull())
        return execute(Request.Builder().url(url).post(rb).build())
    }

    override fun put(url: String, body: ByteArray, contentType: String?): VoidbindResponse {
        val rb = body.toRequestBody(contentType?.toMediaTypeOrNull())
        return execute(Request.Builder().url(url).put(rb).build())
    }

    override fun delete(url: String, body: ByteArray?, contentType: String?): VoidbindResponse {
        val rb = body?.toRequestBody(contentType?.toMediaTypeOrNull())
        return execute(Request.Builder().url(url).delete(rb).build())
    }

    override fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun execute(request: Request): VoidbindResponse = client.newCall(request).execute().use { resp ->
        // Debug builds only: the method + URL + status of every voidbind wire call
        // (never a body — a relay slot carries the sealed cert), so the composed
        // relay/broker paths can be checked in logcat against the node's mounts.
        if (BuildConfig.DEBUG) Log.d(TAG, "${request.method} ${request.url} -> ${resp.code}")
        VoidbindResponse(resp.code, resp.body?.bytes() ?: ByteArray(0))
    }

    private companion object {
        const val TAG = "heyarr.voidbind"
    }
}

/**
 * Adapts the app's string-bodied [HttpTransport] to voidbind-client's byte-bodied
 * seam, so the SAME fake transport a unit test scripts for the app's own clients can
 * also drive `WebLoginClient` — and so a caller holding an app transport (the
 * sibling settings/login factory) can build the login client from it. `put` is not
 * part of the app seam and is refused: only the pairing relay PUTs, and that always
 * runs over [OkHttpVoidbindTransport].
 */
class VoidbindTransportAdapter(
    private val http: HttpTransport,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : VoidbindHttpTransport {

    override fun get(url: String): VoidbindResponse = http.get(url).toVoidbind()

    override fun post(url: String, body: ByteArray?, contentType: String?): VoidbindResponse =
        http.post(url, body?.decodeToString(), contentType).toVoidbind()

    override fun put(url: String, body: ByteArray, contentType: String?): VoidbindResponse =
        throw UnsupportedOperationException("the app HttpTransport seam has no PUT")

    override fun delete(url: String, body: ByteArray?, contentType: String?): VoidbindResponse =
        http.delete(url).toVoidbind()

    override fun sleep(millis: Long) = sleeper(millis)

    private fun HttpResponse.toVoidbind() = VoidbindResponse(status, body.encodeToByteArray())
}
