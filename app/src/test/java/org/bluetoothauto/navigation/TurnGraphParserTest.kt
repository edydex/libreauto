// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test

class TurnGraphParserTest {
    private val rows = """{"elements":[{"type":"way","id":10,"nodes":[1,2],"tags":{"name":"James St","highway":"residential","maxspeed":"25 mph"}},{"type":"node","id":1,"lat":47.6,"lon":-122.3},{"type":"node","id":2,"lat":47.6001,"lon":-122.3}]}"""
    @Test fun parsesStandardTaggedWaysAndSharedNodeIds() {
        val graph = TurnGeometryResolver.parseGraph(rows)
        assertEquals(2, graph.nodes.size); assertEquals(1, graph.ways.size)
        assertEquals(listOf(1L, 2L), graph.ways.single().nodes)
        assertEquals("25 mph", graph.ways.single().tags["maxspeed"])
    }
    @Test fun partialServerOutputCannotClaimUniqueGeometry() {
        val bad = rows.replace("\"elements\":", "\"remark\":\"runtime limit\",\"elements\":")
        try { TurnGeometryResolver.parseGraph(bad); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun missingReferencedNodeIsRejected() {
        try { TurnGeometryResolver.parseGraph(rows.replace("[1,2]", "[1,3]")); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun duplicateNodeIdsAndInvalidCoordinatesAreRejected() {
        for (bad in listOf(rows.replace("\"id\":2,\"lat\"", "\"id\":1,\"lat\""), rows.replace("47.6001", "147.6001")))
            try { TurnGeometryResolver.parseGraph(bad); fail() } catch (_: IllegalArgumentException) { }
    }
}
