// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.CharacterCodingException

class TurnRoadCodecTest {
    @Test fun aggregateWayReferencesAreBoundedBeforeConstructingHugeEdgeGraphs() {
        val rows = JSONArray().put(JSONObject().put("type", "node").put("id", 1).put("lat", 0).put("lon", 0))
        repeat(7) { index -> rows.put(JSONObject().put("type", "way").put("id", index + 2)
            .put("nodes", JSONArray(List(10_000) { 1 })).put("tags", JSONObject().put("highway", "residential"))) }
        assertThrows(IllegalArgumentException::class.java) { TurnRoadCodec.parseGraph(JSONObject().put("elements", rows).toString()) }
    }

    @Test fun malformedUtf8IsRejectedRatherThanReplacedInSavedGraphs() {
        assertThrows(CharacterCodingException::class.java) { TurnRoadCodec.decode(byteArrayOf(123, -1, 125)) }
        assertEquals("Café", TurnRoadCodec.decode("Café".toByteArray(Charsets.UTF_8)))
    }

    @Test fun graphLimitAppliesToUtf8BytesIncludingMultibyteNames() {
        val input = "{\"elements\":[],\"padding\":\"" + "é".repeat(TurnRoadCodec.MAX_BYTES / 2) + "\"}"
        assertTrue(input.length < TurnRoadCodec.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { TurnRoadCodec.parseGraph(input) }
    }
}
