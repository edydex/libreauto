// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedDestinationsCodecTest {
    private fun row(name: String, lat: Double = 47.3, lon: Double = -122.1) = JSONObject().put("name", name).put("lat", lat).put("lon", lon)
    private fun host(name: String = "Church") = JSONArray().put(row(name)).toString()

    @Test fun existingSavedAndRecentPlacesAreVisibleWithStableIdsWithoutWritingOrRenaming() {
        val saved = host()
        val recent = host("Recent")
        val first = SharedDestinationsCodec.toProjection(saved, recent, null)
        val again = SharedDestinationsCodec.toProjection(saved, recent, null)
        assertEquals(first, again)
        val root = JSONObject(first)
        assertEquals("Church", root.getJSONArray("saved").getJSONObject(0).getString("name"))
        assertEquals("Recent", root.getJSONArray("recent").getJSONObject(0).getString("name"))
        assertEquals(saved, host())
    }

    @Test fun projectionAddressShortcutAndStableIdentitySurviveEditingNameOnHost() {
        val root = JSONObject(SharedDestinationsCodec.toProjection(host(), "[]", null))
        val place = root.getJSONArray("saved").getJSONObject(0)
        place.put("address", "22975 24th Ave S, Des Moines").put("shortcut", "HOME")
        val converted = SharedDestinationsCodec.toHost(root.toString())
        val updated = JSONObject(SharedDestinationsCodec.toProjection(host("Renamed church"), converted.recent, converted.details))
            .getJSONArray("saved").getJSONObject(0)
        assertEquals(place.getString("id"), updated.getString("id"))
        assertEquals("Renamed church", updated.getString("name"))
        assertEquals(place.getString("address"), updated.getString("address"))
        assertEquals("HOME", updated.getString("shortcut"))
    }

    @Test fun removingPlaceInHostDoesNotResurrectItFromProjectionDetails() {
        val old = SharedDestinationsCodec.toProjection(host(), host("Recent"), null)
        val root = JSONObject(SharedDestinationsCodec.toProjection("[]", "[]", old))
        assertEquals(0, root.getJSONArray("saved").length())
        assertEquals(0, root.getJSONArray("recent").length())
    }

    @Test fun projectionWriteKeepsBothHostListsAndRichMetadataTogether() {
        val canonical = SharedDestinationsCodec.toProjection(host(), host("Recent"), null)
        val converted = SharedDestinationsCodec.toHost(canonical)
        assertEquals("Church", JSONArray(converted.saved).getJSONObject(0).getString("name"))
        assertEquals("Recent", JSONArray(converted.recent).getJSONObject(0).getString("name"))
        assertEquals(canonical, converted.details)
        assertEquals(canonical, SharedDestinationsCodec.toProjection(converted.saved, converted.recent, converted.details))
    }

    @Test fun malformedDetailsDoNotHideExistingHostPlaces() {
        assertEquals("Church", JSONObject(SharedDestinationsCodec.toProjection(host(), "[]", "broken"))
            .getJSONArray("saved").getJSONObject(0).getString("name"))
    }

    @Test fun invalidCoordinatesAndRepeatedPinsAreNotOfferedAsDestinations() {
        val rows = JSONArray().put(row("Good")).put(row("Duplicate"))
            .put(row("Outside map", 100.0)).put(row("Control\nname", 47.4))
        assertEquals(1, JSONObject(SharedDestinationsCodec.toProjection(rows.toString(), "[]", null))
            .getJSONArray("saved").length())
    }

    @Test fun entireProjectionReplacementIsRejectedWhenAnyEntryIsInvalid() {
        val root = JSONObject(SharedDestinationsCodec.toProjection(host(), "[]", null))
        root.getJSONArray("saved").getJSONObject(0).put("latitude", 1234)
        try { SharedDestinationsCodec.toHost(root.toString()); fail("Invalid replacement must not reach preferences") }
        catch (_: IllegalArgumentException) { }
    }
}
