// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class TurnRoadStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_700_000_000_000L
    private val center = GeoPoint(0.0, 0.0)
    private fun directory() = File(temporary.root, "prepared-roads")
    private fun store() = TurnRoadStore(directory()) { now }
    private fun graph(padding: Int = 0): String = JSONObject().put("elements", JSONArray()
        .put(JSONObject().put("type", "node").put("id", 1).put("lat", 0).put("lon", 0))
        .put(JSONObject().put("type", "node").put("id", 2).put("lat", 0).put("lon", .001))
        .put(JSONObject().put("type", "way").put("id", 3).put("nodes", JSONArray(listOf(1, 2)))
            .put("tags", JSONObject().put("highway", "residential").put("name", "Fixture Street"))))
        .also { if (padding > 0) it.put("padding", "x".repeat(padding)) }.toString()
    private fun envelope(at: Long = now, radius: Double = 1300.0) = JSONObject()
        .put("format", TurnRoadStore.FORMAT).put("version", 1)
        .put("center", JSONObject().put("latitude", 0).put("longitude", 0))
        .put("radiusMeters", radius).put("downloadedAtMillis", at).put("graph", JSONObject(graph()))

    @Test fun successfullySavedRoadsSurviveNewStoreAndProcessState() {
        val first = store().save(center, 1300.0, graph())
        assertEquals(TurnRoadStore.OVERPASS, first.source)
        val restored = store().load(center, 1200.0)!!
        assertEquals(2, restored.graph.nodes.size)
        assertEquals(1, restored.graph.ways.size)
        assertEquals(now, restored.savedAt)
        assertEquals(1, store().summary().areaCount)
    }

    @Test fun coverageRequiresEntireRequestedCircleInsideSavedArea() {
        val store = store(); store.save(center, 1300.0, graph())
        assertNotNull(store.load(center, 1300.0))
        assertNotNull(store.load(GeoPoint(0.0, .002), 1000.0))
        assertNull(store.load(GeoPoint(0.0, .003), 1000.0))
        assertNull(store.load(center, 1300.01))
    }

    @Test fun expiredGraphsAreMissedAndRemoved() {
        val store = store(); store.save(center, 1300.0, graph())
        now += TurnRoadStore.MAX_AGE_MILLIS
        assertNotNull(store.load(center, 100.0))
        now++
        assertNull(store.load(center, 100.0))
        assertEquals(0, store.summary().areaCount)
    }

    @Test fun importPreservesDataAgeAndAlwaysUsesApprovedSource() {
        val old = now - 86_400_000
        val store = store()
        val imported = store.importArea(envelope(old).put("source", "OVERPASS").toString())
        assertEquals(old, imported.savedAt)
        assertEquals(TurnRoadStore.USER_APPROVED_OSM, imported.source)
        val exported = JSONObject(store.exportArea(imported))
        assertEquals(old, exported.getLong("downloadedAtMillis"))
        assertEquals(TurnRoadStore.FORMAT, exported.getString("format"))
        assertEquals(old, store.importArea(exported.toString()).savedAt)
    }

    @Test fun importRejectsStaleFutureOrFractionalTimestampsWithoutFreshening() {
        val store = store(); store.save(center, 1300.0, graph())
        for (at in listOf(now - TurnRoadStore.MAX_AGE_MILLIS - 1, now + TurnRoadStore.FUTURE_TOLERANCE_MILLIS + 1))
            assertThrows(IllegalArgumentException::class.java) { store.importArea(envelope(at).toString()) }
        assertThrows(IllegalArgumentException::class.java) { store.importArea(envelope().put("downloadedAtMillis", now + .5).toString()) }
        assertThrows(IllegalArgumentException::class.java) { store.importArea(envelope().put("downloadedAtMillis", now.toString()).toString()) }
        assertEquals(1, store.summary().areaCount)
    }

    @Test fun smallFutureClockSkewDoesNotDeleteTheImportedArea() {
        val store = store()
        store.importArea(envelope(now + TurnRoadStore.FUTURE_TOLERANCE_MILLIS).toString())
        assertNotNull(store.load(center, 1200.0))
    }

    @Test fun corruptJsonAndMalformedUtf8AreGracefulMisses() {
        val store = store(); store.save(center, 1300.0, graph())
        val saved = directory().listFiles()!!.single()
        saved.writeText("{truncated")
        assertNull(store.load(center, 100.0)); assertFalse(saved.exists())
        store.save(center, 1300.0, graph())
        val second = directory().listFiles()!!.single()
        second.writeBytes(byteArrayOf(123, -1, 125))
        assertNull(store.load(center, 100.0)); assertFalse(second.exists())
    }

    @Test fun malformedMetadataIsNeverTreatedAsCoverage() {
        val store = store()
        for ((key, value) in listOf("version" to 2, "radius" to -1, "radius" to "1300", "source" to "https://private.invalid", "savedAt" to -1)) {
            store.save(center, 1300.0, graph())
            val file = directory().listFiles()!!.single()
            file.writeText(JSONObject(file.readText()).put(key, value).toString())
            assertNull(store.load(center, 100.0))
        }
    }

    @Test fun invalidImportOrCoverageMetadataDoesNotReplaceExistingData() {
        val store = store(); store.save(center, 1300.0, graph())
        for (bad in listOf(envelope().put("format", "unknown"), envelope().put("version", 2), envelope(radius = 99.0), envelope(radius = 5001.0)))
            assertThrows(IllegalArgumentException::class.java) { store.importArea(bad.toString()) }
        assertThrows(IllegalArgumentException::class.java) { store.save(center, Double.NaN, graph()) }
        assertThrows(IllegalArgumentException::class.java) { store.save(center, 1300.0, graph(), "UNTRUSTED") }
        assertEquals(1, store.summary().areaCount)
        assertTrue(directory().listFiles()!!.none { it.extension == "tmp" })
    }

    @Test fun missingNodesPartialResponsesAndDuplicateIdsCannotBeSaved() {
        val store = store()
        val missing = JSONObject(graph()).also { it.getJSONArray("elements").remove(0) }
        val duplicate = JSONObject(graph()).also { it.getJSONArray("elements").put(it.getJSONArray("elements").get(0)) }
        val partial = JSONObject(graph()).put("remark", "runtime error: incomplete")
        for (raw in listOf(missing, duplicate, partial))
            assertThrows(IllegalArgumentException::class.java) { store.save(center, 1300.0, raw.toString()) }
        assertEquals(0, store.summary().areaCount)
    }

    @Test fun emptyAndWhollyUnrelatedGraphCannotClaimPreparedCoverage() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.save(center, 1300.0, "{\"elements\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { store.save(GeoPoint(10.0, 10.0), 1300.0, graph()) }
        assertEquals(0, store.summary().areaCount)
    }

    @Test fun countIsBoundedAcrossRestartsAndSaveEvictsOldestArea() {
        val store = store()
        repeat(10) { store.save(center, 1300.0, graph()); now++ }
        assertEquals(TurnRoadStore.MAX_AREAS, store.summary().areaCount)
        assertEquals(TurnRoadStore.MAX_AREAS, directory().listFiles()!!.count { it.extension == "json" })
        assertEquals(now - 1, store().load(center, 100.0)!!.savedAt)
    }

    @Test fun publishingNewAreaDoesNotLoseItToFileTimestampTiesOrSkew() {
        val store = store()
        repeat(TurnRoadStore.MAX_AREAS) { store.save(center, 1300.0, graph()); now++ }
        directory().listFiles()!!.forEach { assertTrue(it.setLastModified(System.currentTimeMillis() + 60_000)) }
        val newest = now
        store.save(center, 1300.0, graph())
        assertEquals(TurnRoadStore.MAX_AREAS, store.summary().areaCount)
        assertEquals(newest, store().load(center, 100.0)!!.savedAt)
    }

    @Test fun aggregateByteBudgetIsEnforcedWithoutDroppingNewestArea() {
        val store = store(); val large = graph(2_700_000)
        repeat(7) { store.save(center, 1300.0, large); now++ }
        val summary = store.summary()
        assertTrue(summary.totalBytes <= TurnRoadStore.MAX_TOTAL_BYTES)
        assertTrue(summary.areaCount < 7)
        assertEquals(now - 1, store().load(center, 100.0)!!.savedAt)
    }

    @Test fun oversizedFileOrImportCannotAllocateUnboundedGraph() {
        val store = store(); store.save(center, 1300.0, graph())
        val file = directory().listFiles()!!.single()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(TurnRoadStore.MAX_AREA_BYTES.toLong() + 1) }
        assertNull(store.load(center, 100.0))
        assertThrows(IllegalArgumentException::class.java) { store.importArea("x".repeat(TurnRoadStore.MAX_IMPORT_BYTES + 1)) }
    }

    @Test fun interruptedSaveDoesNotPublishPartialArea() {
        val store = store()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { store.save(center, 1300.0, graph()) }
        } finally { Thread.interrupted() }
        assertEquals(0, store.summary().areaCount)
    }

    @Test fun restartRemovesUnpublishedFilesLeftByKilledWriter() {
        val store = store(); store.save(center, 1300.0, graph())
        val orphan = File(directory(), "00000000-0000-0000-0000-000000000000.tmp").apply { writeText("unfinished") }
        assertNotNull(store().load(center, 100.0))
        assertFalse(orphan.exists())
        assertEquals(1, store.summary().areaCount)
    }

    @Test fun clearRemovesPreparedGraphsButPreservesUnrelatedFiles() {
        val store = store(); store.save(center, 1300.0, graph())
        val unrelated = File(directory(), "keep.txt").apply { writeText("unrelated") }
        assertEquals(1, store.clear())
        assertTrue(unrelated.exists())
        assertEquals(0, store.summary().areaCount)
    }

    @Test fun repeatedUncoveredMissesUseOnlyMetadataWithoutReadingGraphsAgain() {
        val writer = store()
        repeat(3) { writer.save(center, 1300.0, graph()); now++ }
        val reader = store()
        assertNull(reader.load(GeoPoint(1.0, 1.0), 250.0))
        val initial = reader.diagnostics()
        assertEquals(3L, initial.graphReads)
        assertEquals(1L, initial.catalogRefreshes)
        repeat(40) { assertNull(reader.load(GeoPoint(1.0, 1.0), 250.0)) }
        assertEquals(initial, reader.diagnostics())
        assertEquals(3, reader.summary().areaCount)
        assertEquals(initial, reader.diagnostics())
    }

    @Test fun selectedHitsDecodeOnlySelectedAreaAfterCatalogIsKnown() {
        val writer = store()
        repeat(3) { writer.save(center, 1300.0, graph()); now++ }
        val reader = store()
        assertNull(reader.load(GeoPoint(1.0, 1.0), 250.0))
        val before = reader.diagnostics()
        assertNotNull(reader.load(center, 1000.0))
        assertEquals(before.graphReads + 1, reader.diagnostics().graphReads)
        assertEquals(before.catalogRefreshes, reader.diagnostics().catalogRefreshes)
    }

    @Test fun anotherStoreImportInvalidatesCatalogThroughFileInventory() {
        val reader = store()
        assertNull(reader.load(center, 1000.0))
        val initial = reader.diagnostics()
        store().importArea(envelope().toString())
        assertNotNull(reader.load(center, 1000.0))
        assertEquals(initial.catalogRefreshes + 1, reader.diagnostics().catalogRefreshes)
        assertEquals(1, reader.summary().areaCount)
    }

    @Test fun changedOrCorruptFileInvalidatesPreviouslyValidCatalog() {
        val store = store(); store.save(center, 1300.0, graph())
        assertNull(store.load(GeoPoint(1.0, 1.0), 250.0))
        val before = store.diagnostics()
        directory().listFiles()!!.single().writeText("{corrupt")
        assertNull(store.load(center, 1000.0))
        assertEquals(before.catalogRefreshes + 1, store.diagnostics().catalogRefreshes)
        assertEquals(0, store.summary().areaCount)
    }

    @Test fun expiryInvalidatesCatalogEvenWhenFileInventoryDoesNotChange() {
        val store = store(); store.save(center, 1300.0, graph())
        assertNull(store.load(GeoPoint(1.0, 1.0), 250.0))
        val before = store.diagnostics()
        now += TurnRoadStore.MAX_AGE_MILLIS + 1
        assertNull(store.load(center, 1000.0))
        assertEquals(before.catalogRefreshes + 1, store.diagnostics().catalogRefreshes)
        assertEquals(before.graphReads, store.diagnostics().graphReads)
        assertEquals(0, store.summary().areaCount)
    }
}
