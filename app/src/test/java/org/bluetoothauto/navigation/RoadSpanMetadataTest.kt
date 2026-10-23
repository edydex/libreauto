// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoadSpanMetadataTest {
    private fun route() = RoutePlan(listOf(GeoPoint(0.0, 0.0), GeoPoint(.001, 0.0),
        GeoPoint(.001, .001), GeoPoint(.001, .002)), listOf(RoutePlan.Maneuver(0, 3, "Head north", "", 1)), 100.0, "Fixture")
    private fun edge(begin: Int, end: Int, name: String = "First Road") = JSONObject()
        .put("begin_shape_index", begin).put("end_shape_index", end).put("names", JSONArray(listOf(name)))
        .put("travel_mode", "drive").put("use", "road")
    private fun response(edges: JSONArray = JSONArray().put(edge(0, 1)).put(edge(1, 2, "Second Road")).put(edge(2, 3, "Third Road"))) =
        JSONObject().put("shape", RoutePlan.encodePolyline6(route().points)).put("edges", edges)

    @Test fun exactNamedEdgesAreRetainedEvenWithoutKnownPostedLimits() {
        val result = OwnMapProvider.parseRoadSpeedLimits(response().toString(), route())
        assertEquals(3, result.roadSpans.size)
        assertTrue(result.metadataEnriched)
        assertTrue(result.speedLimitKphAt(0.0).isNaN())
        assertEquals("Second Road", result.roadSpans[1].names.single())
    }

    @Test fun edgeNamesCannotBindToChangedRouteGeometry() {
        val wrong = response().put("shape", RoutePlan.encodePolyline6(route().points.mapIndexed { index, p ->
            if (index == 1) GeoPoint(p.latitude + .000001, p.longitude) else p }))
        assertThrows(IllegalArgumentException::class.java) { OwnMapProvider.parseRoadSpeedLimits(wrong.toString(), route()) }
    }

    @Test fun gapsOverlapsAndPartialRoadCoverageAreRejected() {
        val bad = listOf(JSONArray().put(edge(0, 1)).put(edge(2, 3)),
            JSONArray().put(edge(0, 2)).put(edge(1, 3)), JSONArray().put(edge(0, 2)),
            JSONArray().put(edge(1, 3)), JSONArray().put(edge(0, 4)))
        for (edges in bad) assertThrows(IllegalArgumentException::class.java) {
            OwnMapProvider.parseRoadSpeedLimits(response(edges).toString(), route())
        }
    }

    @Test fun stringAndFractionalIndicesAreRejected() {
        for (value in listOf<Any>("0", .5)) {
            val edges = JSONArray().put(edge(0, 3).put("begin_shape_index", value))
            assertThrows(IllegalArgumentException::class.java) { OwnMapProvider.parseRoadSpeedLimits(response(edges).toString(), route()) }
        }
    }

    @Test fun zeroLengthConnectorDoesNotInventNamedShapeSegment() {
        val result = OwnMapProvider.parseRoadSpeedLimits(response(JSONArray().put(edge(0, 1))
            .put(edge(1, 1, "No traversed distance")).put(edge(1, 3, "Second Road"))).toString(), route())
        assertEquals(2, result.roadSpans.size)
        assertEquals(1, result.roadSpans[1].beginIndex)
    }

    @Test fun metadataAndOfflineCopiesPreserveExactRoadSpans() {
        val enriched = OwnMapProvider.parseRoadSpeedLimits(response().toString(), route())
        val limits = enriched.withRoadMetadata(null, doubleArrayOf(30.0, 40.0, 50.0))
        assertEquals(enriched.roadSpans.map { it.names }, limits.roadSpans.map { it.names })
        assertEquals(limits.roadSpans, limits.withOffline().roadSpans)
        assertSame(enriched.points, enriched.withOffline().points)
    }

    @Test fun suppliedAndReturnedRoadLabelListsCannotMutateStoredMetadata() {
        val names = arrayListOf("First Road")
        val spans = arrayListOf(RoutePlan.RoadSpan(0, 3, names, "drive", "road"))
        val result = route().withRoadSpans(spans)
        names.clear(); spans.clear()
        assertEquals("First Road", result.roadSpans.single().names.single())
        assertThrows(UnsupportedOperationException::class.java) { result.roadSpans.clear() }
        assertThrows(UnsupportedOperationException::class.java) { result.roadSpans.single().names.clear() }
    }

    @Test fun malformedOrExcessiveNamesDoNotBecomeRoadProof() {
        for (names in listOf<Any>("not-an-array", JSONArray(listOf(123)), JSONArray(List(65) { "Road" }))) {
            val edges = JSONArray().put(edge(0, 3).put("names", names))
            assertThrows(IllegalArgumentException::class.java) { OwnMapProvider.parseRoadSpeedLimits(response(edges).toString(), route()) }
        }
    }
}
