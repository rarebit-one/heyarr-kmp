package one.rarebit.heyarr.mobile.net
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import java.util.concurrent.TimeUnit

/** The Android/OkHttp actual of [HttpTransport]. */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            return HttpResponse(resp.code, resp.body?.string().orEmpty())
        }
    }

    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        val media = (contentType ?: "application/json").toMediaTypeOrNull()
        val reqBody = (body ?: "").toRequestBody(media)
        val builder = Request.Builder().url(url).post(reqBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            return HttpResponse(resp.code, resp.body?.string().orEmpty())
        }
    }

    override fun patch(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        val media = (contentType ?: "application/json").toMediaTypeOrNull()
        val reqBody = (body ?: "").toRequestBody(media)
        val builder = Request.Builder().url(url).patch(reqBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            return HttpResponse(resp.code, resp.body?.string().orEmpty())
        }
    }

    override fun delete(url: String, headers: Map<String, String>): HttpResponse {
        val builder = Request.Builder().url(url).delete()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            return HttpResponse(resp.code, resp.body?.string().orEmpty())
        }
    }
}
