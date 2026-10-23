// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.bluetoothauto.navigation.GoogleDirectionsAlignment
import org.bluetoothauto.navigation.GoogleDirectionsParser
import org.bluetoothauto.navigation.GoogleDirectionsRow
import org.bluetoothauto.navigation.GoogleDirectionsStepKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Opt-in real Maps tree/parser proof. Opens no screen, performs no click, injects no app state. */
object GoogleDirectionsCaptureAcceptance {
    fun run(test: Instrumentation): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run on the instrumentation thread" }
        // Keep the real optional service available rather than suppressing it during this read.
        val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        val flags = info.flags
        val expires = SystemClock.elapsedRealtime() + 10_000
        var visited = 0
        var characters = 0
        fun budget(depth: Int) {
            check(depth < 48 && ++visited <= 18_000 && SystemClock.elapsedRealtime() < expires) { "Maps tree exceeded its diagnostic bound" }
        }
        fun text(node: AccessibilityNodeInfo, values: MutableList<String>, descriptions: MutableList<String>, depth: Int) {
            budget(depth)
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                check(it.length <= 600 && values.size < 32); characters += it.length; values.add(it)
            }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                check(it.length <= 600 && descriptions.size < 32); characters += it.length; descriptions.add(it)
            }
            check(characters <= GoogleDirectionsParser.MAX_TOTAL_TEXT_CHARS && node.childCount <= 64)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { text(child, values, descriptions, depth + 1) } finally { recycle(child) }
            }
        }
        fun find(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            budget(depth)
            if (node.packageName?.toString() != MAPS) return null
            if (node.viewIdResourceName == "$MAPS:id/recycler_view") {
                @Suppress("DEPRECATION") return AccessibilityNodeInfo.obtain(node)
            }
            check(node.childCount <= 1024)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { find(child, depth + 1)?.let { return it } } finally { recycle(child) }
            }
            return null
        }
        try {
            info.flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            val windows = automation.windows
            var list: AccessibilityNodeInfo? = null
            try {
                check(windows.size <= 32) { "Too many windows for this diagnostic" }
                for (window in windows) {
                    val root = window.root ?: continue
                    try {
                        if (root.packageName?.toString() == MAPS) list = find(root, 0)
                    } finally { recycle(root) }
                    if (list != null) break
                }
            } finally { windows.forEach { @Suppress("DEPRECATION") it.recycle() } }
            val recycler = list ?: error("Open Google Maps Directions before running this read-only check")
            val rows = try {
                check(recycler.childCount in 1..GoogleDirectionsParser.MAX_ROWS) { "Directions list row count is unsupported" }
                (0 until recycler.childCount).map { index ->
                    val row = recycler.getChild(index) ?: error("Directions list has an incomplete row")
                    try {
                        val order = row.collectionItemInfo?.rowIndex ?: error("Directions row order is unavailable")
                        val values = arrayListOf<String>(); val descriptions = arrayListOf<String>()
                        text(row, values, descriptions, 0)
                        GoogleDirectionsRow(order, values.toList(), descriptions.toList())
                    } finally { recycle(row) }
                }
            } finally { recycle(recycler) }
            val at = SystemClock.elapsedRealtime()
            val snapshot = GoogleDirectionsParser.parse(rows, at) ?: error("Google Directions layout could not be parsed completely")
            check(snapshot.complete && snapshot.steps.last().kind == GoogleDirectionsStepKind.ARRIVAL) { "Directions capture did not reach the destination" }
            check(snapshot.steps.map { it.index } == snapshot.steps.indices.toList()) { "Captured Directions order is not contiguous" }
            check(snapshot.steps.map { it.key }.distinct().size == snapshot.steps.size) { "Distinct step keys were lost" }
            check(snapshot.steps.dropLast(1).all { it.followingDistanceMeters != null } && snapshot.steps.last().followingDistanceMeters == null)
            check(GoogleDirectionsParser.parse(rows, at + 1)?.fingerprint == snapshot.fingerprint) { "Capture time changed trip identity" }
            var currentIndex: Int? = null
            test.runOnMainSync {
                currentIndex = GuidanceHub.current?.let { GoogleDirectionsAlignment.find(snapshot, it.instruction) }
            }
            val serializedRows = JSONArray()
            rows.forEach { serializedRows.put(JSONObject().put("rowIndex", it.rowIndex)
                .put("texts", JSONArray(it.texts)).put("descriptions", JSONArray(it.descriptions))) }
            val serializedSteps = JSONArray()
            snapshot.steps.forEach { step -> serializedSteps.put(JSONObject().put("index", step.index).put("rowIndex", step.rowIndex)
                .put("instruction", step.instruction).put("followingDistanceMeters", step.followingDistanceMeters ?: JSONObject.NULL)
                .put("followingDistanceLabel", step.followingDistanceLabel).put("kind", step.kind.name).put("key", step.key)) }
            val value = JSONObject().put("fingerprint", snapshot.fingerprint).put("steps", serializedSteps)
                .put("capturedAtElapsed", snapshot.capturedAtElapsed).put("complete", snapshot.complete)
                .put("destinationQuery", snapshot.destinationQuery ?: JSONObject.NULL)
                .put("currentStepIndex", currentIndex ?: JSONObject.NULL)
                .put("provenance", "actual Google Maps accessibility tree parsed by production parser")
            writePrivate(File(test.targetContext.cacheDir, "google-directions-rows.json"), serializedRows.toString())
            writePrivate(File(test.targetContext.cacheDir, "google-directions-snapshot.json"), value.toString())
            val receipt = JSONObject().put("rows", rows.size).put("steps", snapshot.steps.size).put("treeNodesRead", visited)
                .put("complete", true).put("currentStepAligned", currentIndex != null)
                .put("productionReaderAvailable", false).put("uiActions", 0).put("appStateInjected", false).put("gpsInjected", false)
                .put("snapshotKeptInPrivateCache", true)
            writePrivate(File(test.targetContext.cacheDir, "google-directions-capture-receipt.json"), receipt.toString())
            return "PASS: ${snapshot.steps.size} ordered Google route entries; ${rows.size} rows; complete=true; aligned=${currentIndex != null}; UI actions=0; state/GPS injection=false"
        } finally {
            info.flags = flags; automation.serviceInfo = info
        }
    }

    private fun writePrivate(file: File, value: String) {
        check(value.toByteArray(Charsets.UTF_8).size <= 4_000_000) { "Private diagnostic output exceeded its bound" }
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.outputStream().use { output -> output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
    private const val MAPS = "com.google.android.apps.maps"
}
