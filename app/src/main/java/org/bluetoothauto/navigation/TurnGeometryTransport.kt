// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.json.JSONException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.URI
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Pure framing and allowlisted diagnostics: provider messages/URLs never become status text. */
internal object TurnGeometryTransport {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    const val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
    enum class State {
        IDLE, CLOSED, CANCELLED, WAITING_FIX, WAITING_COURSE, WAITING_DISTANCE,
        UNSUPPORTED_INSTRUCTION, DEMO, RATE_LIMITED, FETCHING, CACHED_MATCHING,
        MATCHED, CACHED_MATCH, NO_MATCH, TIMEOUT, PARSE, TOO_LARGE, UNSUPPORTED_AREA,
        NETWORK, TLS, IO, ERROR, PREPARING, PERSISTED_MATCHING, PERSISTED_MATCH
    }
    class Failure(val state: State, val responseCode: Int? = null) : IOException()

    fun formBody(query: String): ByteArray {
        require(query.isNotBlank() && query.toByteArray(StandardCharsets.UTF_8).size <= 16_384)
        return ("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name())).toByteArray(StandardCharsets.UTF_8)
    }
    /** The public interpreter supports GET; keep the entire query in its single encoded field. */
    fun getUrl(query: String): URI {
        val url = ENDPOINT + "?" + String(formBody(query), StandardCharsets.UTF_8)
        require(url.length <= 4096)
        return URI.create(url)
    }
    fun areaQuery(point: GeoPoint, radius: Double): String {
        require(abs(point.latitude) < 80 && abs(point.longitude) <= 179.9 && radius.isFinite() && radius in 100.0..1500.0)
        val latitude = radius / 111_195.08
        val longitude = latitude / cos(Math.toRadians(point.latitude))
        val box = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", point.latitude - latitude,
            point.longitude - longitude, point.latitude + latitude, point.longitude + longitude)
        return "[out:json][timeout:8][maxsize:16777216];way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"]($box);out body;>;out skel qt;"
    }
    fun httpStatus(code: Int): String = if (code in 100..599) "HTTP_$code" else "HTTP_ERROR"
    fun failureStatus(error: Throwable, deadlineExpired: Boolean = false): String = when {
        error is InterruptedException || error is CancellationException -> State.CANCELLED.name
        deadlineExpired || error is SocketTimeoutException -> State.TIMEOUT.name
        error is Failure -> error.responseCode?.let(::httpStatus) ?: error.state.name
        error is JSONException || error is IllegalArgumentException -> State.PARSE.name
        error is UnknownHostException -> State.NETWORK.name
        error is SSLException -> State.TLS.name
        error is IOException -> State.IO.name
        else -> State.ERROR.name
    }
}
