package one.rarebit.heyarr.mobile.consumption

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.mobile.net.ProblemDetail
import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import one.rarebit.heyarr.mobile.search.AcquireClient
import java.net.URLEncoder

/**
 * The write half of consumption (heyarr-core §67, ADR-0024): register this phone as a
 * device once, open a session when something starts, and move it through the state
 * machine as it plays. All three are `write`-scoped — an enrolled, authorised device
 * has that; a QR session does not, and the reporter simply stays quiet then.
 *
 * `baseUrl` and `credential` are read per call: both change over a session.
 */
class ConsumptionClient(
    private val http: HttpTransport,
    private val baseUrl: () -> String,
    private val credential: () -> Credential?,
) {
    sealed interface Outcome {
        data class Ok(val id: String?) : Outcome

        /** The node refused (a 409 is "you are out of date", a 403 "not yours to write"). */
        data class Refused(val status: Int, val message: String) : Outcome
    }

    private fun headers(): Map<String, String>? = credential()?.asHeader()?.plus("Content-Type" to "application/json")

    /** `POST /devices` — returns the device id (a re-registration of the same key answers the same id). */
    fun registerDevice(deviceKey: String, name: String, caps: ClientCapabilities?): Outcome {
        val h = headers() ?: return Outcome.Refused(401, "no credential")
        val resp = http.post(devicesUrl(baseUrl()), registerBody(deviceKey, name, caps), "application/json", h)
        return if (resp.status == 200 || resp.status == 201) {
            Outcome.Ok(JsonScan.rootObject(resp.body)?.let { JsonScan.stringField(it, "id") })
        } else {
            Outcome.Refused(resp.status, ProblemDetail.message(resp.body, resp.status, "register device"))
        }
    }

    /** `POST /consumption/sessions` — a fresh session in `created`; returns its id. */
    fun createSession(assetId: String, deviceId: String, verb: String): Outcome {
        val h = headers() ?: return Outcome.Refused(401, "no credential")
        val resp = http.post(sessionsUrl(baseUrl()), sessionBody(assetId, deviceId, verb), "application/json", h)
        return if (resp.status == 201 || resp.status == 200) {
            Outcome.Ok(JsonScan.rootObject(resp.body)?.let { JsonScan.stringField(it, "id") })
        } else {
            Outcome.Refused(resp.status, ProblemDetail.message(resp.body, resp.status, "open session"))
        }
    }

    /** `POST /consumption/sessions/{id}/transitions` — start/pause/resume/progress/stop/complete, with a position. */
    fun transition(sessionId: String, transition: String, pos: Position?): Outcome {
        val h = headers() ?: return Outcome.Refused(401, "no credential")
        val resp = http.post(transitionUrl(baseUrl(), sessionId), transitionBody(transition, pos), "application/json", h)
        return if (resp.status == 200) {
            Outcome.Ok(sessionId)
        } else {
            Outcome.Refused(resp.status, ProblemDetail.message(resp.body, resp.status, transition))
        }
    }

    companion object {
        fun devicesUrl(base: String) = base.trimEnd('/') + "/api/v1/devices"
        fun sessionsUrl(base: String) = base.trimEnd('/') + "/api/v1/consumption/sessions"
        fun transitionUrl(base: String, id: String) = sessionsUrl(base) + "/" + URLEncoder.encode(id, "UTF-8") + "/transitions"

        /** The `registerDeviceRequest`: key, name, platform and what this phone decodes. Pure. */
        fun registerBody(deviceKey: String, name: String, caps: ClientCapabilities?): String {
            val j = AcquireClient::jsonString
            val profile = caps?.let {
                "{\"containers\":" + arr(it.containers) + ",\"video_codecs\":" + arr(it.video) + ",\"audio_codecs\":" + arr(it.audio) +
                    ",\"max_width\":0,\"max_height\":" + it.maxHeight + ",\"max_bitrate_bps\":0,\"supports_hdr\":false}"
            }
            return "{\"device_key\":" + j(deviceKey) + ",\"name\":" + j(name) + ",\"platform\":\"android\"" +
                (profile?.let { ",\"profile\":$it" } ?: "") + "}"
        }

        fun sessionBody(assetId: String, deviceId: String, verb: String): String =
            "{\"asset_id\":" + AcquireClient.jsonString(assetId) + ",\"device_id\":" + AcquireClient.jsonString(deviceId) + ",\"verb\":" + AcquireClient.jsonString(verb) + "}"

        /** A position rides as `progress {locator, unit}`. Pure. */
        fun transitionBody(transition: String, pos: Position?): String {
            val progress = pos?.let { ",\"progress\":{\"locator\":" + AcquireClient.jsonString(it.locator) + ",\"unit\":" + AcquireClient.jsonString(it.unit) + "}" } ?: ""
            return "{\"transition\":" + AcquireClient.jsonString(transition) + progress + "}"
        }

        /** Seconds as a locator: up to 3 decimals, never scientific. Pure. */

        fun locator(seconds: Double): String {
            val s = seconds.coerceAtLeast(0.0)
            val whole = s.toLong()
            return if (s == whole.toDouble()) whole.toString() else String.format(java.util.Locale.ROOT, "%.3f", s).trimEnd('0').trimEnd('.')
        }

        private fun arr(xs: List<String>) = xs.joinToString(",", "[", "]") { AcquireClient.jsonString(it) }
    }
}
