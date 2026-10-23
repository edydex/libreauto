// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.Locale
import javax.net.ssl.SSLException

class TurnGeometryTransportTest {
    private val utf8 = StandardCharsets.UTF_8
    private fun body(query: String) = String(TurnGeometryTransport.formBody(query), utf8)

    @Test fun officialFormEncodingRoundTripsTheEntireQuery() {
        val query = "[out:json];\nway[\"name\"=\"Café + Pine & Cedar\"](0,0,1,1);out;"
        val encoded = body(query)
        assertTrue(encoded.startsWith("data="))
        assertFalse(encoded.contains('&'))
        assertEquals(query, URLDecoder.decode(encoded.removePrefix("data="), utf8.name()))
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", TurnGeometryTransport.CONTENT_TYPE)
    }

    @Test fun queryCannotInjectAnAdditionalFormField() {
        val encoded = body("out;&data=another+query%20\n")
        assertEquals(1, encoded.split('&').size)
        assertTrue(encoded.contains("%26data%3D"))
        assertTrue(encoded.contains("%2B"))
        assertTrue(encoded.contains("%25"))
    }

    @Test fun maximumQueryIsBoundedByUtf8Bytes() {
        assertTrue(TurnGeometryTransport.formBody("x".repeat(16_384)).isNotEmpty())
        assertTrue(TurnGeometryTransport.formBody("é".repeat(8_192)).isNotEmpty())
        for (query in listOf("x".repeat(16_385), "é".repeat(8_193))) {
            assertThrows(IllegalArgumentException::class.java) { TurnGeometryTransport.formBody(query) }
        }
    }

    @Test fun emptyOrWhitespaceQueryIsRejected() {
        for (query in listOf("", " ", "\n\t"))
            assertThrows(IllegalArgumentException::class.java) { TurnGeometryTransport.formBody(query) }
    }

    @Test fun onlyBoundedHttpCodesEnterStatus() {
        for (code in listOf(100, 200, 429, 504, 599))
            assertEquals("HTTP_$code", TurnGeometryTransport.httpStatus(code))
        for (code in listOf(-1, 0, 99, 600, Int.MAX_VALUE))
            assertEquals("HTTP_ERROR", TurnGeometryTransport.httpStatus(code))
    }

    @Test fun providerHttpFailureRemainsDistinctFromGenericIo() {
        assertEquals("HTTP_429", TurnGeometryTransport.failureStatus(
            TurnGeometryTransport.Failure(TurnGeometryTransport.State.IO, 429)))
        assertEquals("HTTP_ERROR", TurnGeometryTransport.failureStatus(
            TurnGeometryTransport.Failure(TurnGeometryTransport.State.IO, -1)))
        assertEquals("IO", TurnGeometryTransport.failureStatus(IOException("provider response")))
    }

    @Test fun socketAndHardDeadlineTimeoutsShareSafeStatus() {
        assertEquals("TIMEOUT", TurnGeometryTransport.failureStatus(SocketTimeoutException("connection details")))
        assertEquals("TIMEOUT", TurnGeometryTransport.failureStatus(IOException("disconnect details"), deadlineExpired = true))
        assertEquals("TIMEOUT", TurnGeometryTransport.failureStatus(
            TurnGeometryTransport.Failure(TurnGeometryTransport.State.TIMEOUT)))
    }

    @Test fun cancellationWinsOverConcurrentDeadline() {
        assertEquals("CANCELLED", TurnGeometryTransport.failureStatus(InterruptedException(), deadlineExpired = true))
        assertEquals("CANCELLED", TurnGeometryTransport.failureStatus(CancellationException(), deadlineExpired = true))
    }

    @Test fun parserFailuresAreClassifiedWithoutProviderDetails() {
        assertEquals("PARSE", TurnGeometryTransport.failureStatus(JSONException("private provider body")))
        assertEquals("PARSE", TurnGeometryTransport.failureStatus(IllegalArgumentException("invalid geometry details")))
        assertEquals("PARSE", TurnGeometryTransport.failureStatus(
            TurnGeometryTransport.Failure(TurnGeometryTransport.State.PARSE)))
    }

    @Test fun dnsAndTlsFailuresRemainDiagnosable() {
        assertEquals("NETWORK", TurnGeometryTransport.failureStatus(UnknownHostException("private.example")))
        assertEquals("TLS", TurnGeometryTransport.failureStatus(SSLException("peer details")))
    }

    @Test fun boundedResponseAndAreaFailuresRemainDistinct() {
        for (state in listOf(TurnGeometryTransport.State.TOO_LARGE, TurnGeometryTransport.State.UNSUPPORTED_AREA))
            assertEquals(state.name, TurnGeometryTransport.failureStatus(TurnGeometryTransport.Failure(state)))
    }

    @Test fun unknownFailuresNeverExposeMessagesOrIdentifiers() {
        val secret = "https://private.example/?point=47.123,-122.456 phone-id-test"
        val cases = listOf(IOException(secret), IllegalStateException(secret), JSONException(secret),
            UnknownHostException(secret), SSLException(secret), SocketTimeoutException(secret))
        for (failure in cases) {
            val status = TurnGeometryTransport.failureStatus(failure)
            assertTrue(status.matches(Regex("[A-Z_]+")))
            assertFalse(status.contains("private"))
            assertFalse(status.contains("47.123"))
            assertFalse(status.contains("phone"))
        }
    }
    @Test fun getRequestRoundTripsUnicodePunctuationAndAnEntireSingleQueryField() {
        val query="[out:json];\nway[\"name\"=\"Café + Pine & Cedar #?%\"](0,0,1,1);out;"
        val uri=TurnGeometryTransport.getUrl(query)
        assertEquals("https",uri.scheme);assertEquals("overpass-api.de",uri.host);assertEquals("/api/interpreter",uri.path)
        assertNull(uri.rawFragment);assertNull(uri.rawUserInfo);assertEquals(-1,uri.port)
        assertTrue(uri.rawQuery.startsWith("data="));assertEquals(1,uri.rawQuery.split('&').size)
        assertEquals(query,URLDecoder.decode(uri.rawQuery.removePrefix("data="),utf8.name()))
    }
    @Test fun getQueryCannotInjectExtraParametersOrAUrlFragment() {
        val uri=TurnGeometryTransport.getUrl("out;&data=another+query#fragment?key=value%20")
        assertNull(uri.fragment);assertEquals(1,uri.rawQuery.split('&').size)
        assertTrue(uri.rawQuery.contains("%26data%3D"));assertTrue(uri.rawQuery.contains("%23fragment%3F"))
    }
    @Test fun maximumGetUrlIs4096CharactersIncludingEndpointAndParameterPrefix() {
        val overhead=TurnGeometryTransport.ENDPOINT.length+"?data=".length
        val query="x".repeat(4096-overhead)
        assertEquals(4096,TurnGeometryTransport.getUrl(query).toASCIIString().length)
        assertThrows(IllegalArgumentException::class.java){TurnGeometryTransport.getUrl(query+"x")}
    }
    @Test fun percentExpansionHasItsOwnGetLimitBelowTheRawFormByteLimit() {
        val query="é".repeat(1000)
        assertTrue(TurnGeometryTransport.formBody(query).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java){TurnGeometryTransport.getUrl(query)}
    }
    @Test fun getRejectsBlankAndOversizedRawQueriesBeforeBuildingAUri() {
        for(query in listOf(""," \n\t","x".repeat(16_385)))
            assertThrows(IllegalArgumentException::class.java){TurnGeometryTransport.getUrl(query)}
    }
    @Test fun areaQueryFramesTheRequestedPublicPointAndRadiusInASingleBoundingBox() {
        val point=GeoPoint(47.6062,-122.3321);val radius=1000.0
        val query=TurnGeometryTransport.areaQuery(point,radius)
        val values=query.substringAfterLast('(').substringBefore(')').split(',').map(String::toDouble)
        val lat=radius/111_195.08;val lon=lat/kotlin.math.cos(Math.toRadians(point.latitude))
        assertEquals(4,values.size)
        assertEquals(point.latitude-lat,values[0],0.00000051);assertEquals(point.longitude-lon,values[1],0.00000051)
        assertEquals(point.latitude+lat,values[2],0.00000051);assertEquals(point.longitude+lon,values[3],0.00000051)
        assertTrue(query.contains("[timeout:8][maxsize:16777216]"));assertTrue(query.endsWith(";out body;>;out skel qt;"))
        assertTrue(TurnGeometryTransport.getUrl(query).toASCIIString().length<=4096)
    }
    @Test fun areaRadiusBoundsAreInclusiveAndNonfiniteValuesAreRejected() {
        val point=GeoPoint(0.0,0.0)
        for(radius in listOf(100.0,1500.0))assertTrue(TurnGeometryTransport.areaQuery(point,radius).isNotBlank())
        for(radius in listOf(0.0,-1.0,99.999,1500.001,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java){TurnGeometryTransport.areaQuery(point,radius)}
    }
    @Test fun areaOriginsOutsideSupportedLatitudeOrDateLineBoundsAreRejected() {
        for(point in listOf(GeoPoint(80.0,0.0),GeoPoint(-80.0,0.0),GeoPoint(90.0,0.0),
                GeoPoint(0.0,179.90001),GeoPoint(0.0,-179.90001)))
            assertThrows(IllegalArgumentException::class.java){TurnGeometryTransport.areaQuery(point,100.0)}
        for(point in listOf(GeoPoint(79.999,179.9),GeoPoint(-79.999,-179.9)))
            assertTrue(TurnGeometryTransport.areaQuery(point,1500.0).isNotBlank())
    }
    @Test fun nonfiniteOriginCannotEnterAnAreaQuery() {
        for(values in listOf(Double.NaN to 0.0,Double.POSITIVE_INFINITY to 0.0,0.0 to Double.NaN,0.0 to Double.NEGATIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java){TurnGeometryTransport.areaQuery(GeoPoint(values.first,values.second),100.0)}
    }
    @Test fun areaCoordinatesUseLocaleIndependentDecimalPoints() {
        val original=Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val query=TurnGeometryTransport.areaQuery(GeoPoint(47.6062,-122.3321),100.0)
            val values=query.substringAfterLast('(').substringBefore(')').split(',')
            assertEquals(4,values.size);assertTrue(values.all{it.matches(Regex("-?\\d+\\.\\d{6}"))})
        }finally{Locale.setDefault(original)}
    }
}
