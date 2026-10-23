// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.os.Looper
import android.os.SystemClock
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.TurnGeometryResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Operator-supplied production lookup benchmark. No app state, GPS or preferences are changed. */
object PrivateRouteLookupAcceptance {
    fun run(test: Instrumentation): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run lookup diagnostic on the instrumentation thread" }
        val cache = test.targetContext.cacheDir
        val input = try { JSONObject(readBounded(File(cache, "private-route-lookup-input.json"), 8192)) }
            catch (_: Exception) { error("Private lookup input is missing or invalid") }
        val expected = setOf("latitude", "longitude", "bearing", "accuracy", "instruction", "distanceMeters")
        check(input.keys().asSequence().toSet() == expected) { "Private lookup input fields are invalid" }
        fun number(name: String, minimum: Double, maximum: Double): Double {
            val raw = input.get(name)
            check(raw is Number) { "Private lookup numeric field is invalid" }
            val value = raw.toDouble()
            check(value.isFinite() && value in minimum..maximum) { "Private lookup numeric field is outside bounds" }
            return value
        }
        val latitude = number("latitude", -85.0, 85.0)
        val longitude = number("longitude", -179.9, 179.9)
        val bearing = number("bearing", 0.0, 359.9999).toFloat()
        val accuracy = number("accuracy", 0.0, 25.0).toFloat()
        val distance = number("distanceMeters", 5.0, 50_000.0)
        val instruction = input.get("instruction") as? String ?: error("Private lookup instruction is invalid")
        check(instruction.length in 2..500 && instruction.none { it.code < 32 || it.code == 127 }) {
            "Private lookup instruction is outside bounds"
        }
        val done = CountDownLatch(1)
        var resolver: TurnGeometryResolver? = null
        var geometry: TurnGeometry? = null
        var diagnostics: TurnGeometryResolver.Diagnostics? = null
        var started = 0L
        var completedAt = 0L
        var completed = false
        var closed = false
        try {
            test.runOnMainSync {
                // Null context intentionally excludes saved road areas and disk caches.
                resolver = TurnGeometryResolver(null)
                val now = SystemClock.elapsedRealtime()
                val position = Position(latitude, longitude, bearing, accuracy, receivedAt = now, demo = false, speedMps = 0f)
                val guidance = Guidance(instruction, distance, receivedAt = now, source = "Google Maps", demo = false)
                started = SystemClock.elapsedRealtime()
                resolver!!.resolve(position, guidance) { result ->
                    geometry = result
                    completedAt = SystemClock.elapsedRealtime()
                    done.countDown()
                }
            }
            completed = done.await(40, TimeUnit.SECONDS)
            check(completed) { "Private production lookup exceeded 40 seconds" }
        } finally {
            test.runOnMainSync {
                try { diagnostics = resolver?.diagnostics() }
                finally { resolver?.close(); closed = true }
            }
            val elapsed = if (started == 0L) 0L else ((completedAt.takeIf { it != 0L } ?: SystemClock.elapsedRealtime()) - started).coerceAtLeast(0)
            val resolved = geometry
            val output = JSONObject().put("matched", resolved != null)
            if (resolved != null) {
                check(resolved.points.size in 2..512) { "Private lookup geometry exceeds output bounds" }
                check(resolved.key.length in 1..512 && resolved.confidence.length in 1..80) { "Private lookup geometry labels exceed output bounds" }
                output.put("points", JSONArray().also { points -> resolved.points.forEach { point ->
                    points.put(JSONArray().put(point.latitude).put(point.longitude))
                } })
                    .put("junction", JSONArray().put(resolved.junction.latitude).put(resolved.junction.longitude))
                    .put("approachSpeedLimitKph", resolved.approachSpeedLimitKph ?: JSONObject.NULL)
                    .put("confidence", resolved.confidence).put("key", resolved.key)
            } else output.put("points", JSONArray())
            File(cache, "private-route-lookup-geometry.json").writeText(output.toString(2) + "\n")
            val stats = diagnostics
            fun status(value: String?) = value?.takeIf { Regex("^[A-Z0-9_]{1,64}$").matches(it) } ?: "NOT_AVAILABLE"
            val receipt = JSONObject()
                .put("lookup_completed", completed).put("matched", resolved != null)
                .put("elapsed_ms", elapsed).put("resolver_elapsed_ms", stats?.lastElapsedMillis ?: 0)
                .put("status", status(stats?.status)).put("pending_at_close", stats?.pending ?: false)
                .put("candidate_status", status(stats?.candidateStatus))
                .put("candidate_network_requests", stats?.candidateNetworkRequests ?: 0)
                .put("candidate_cache_hits", stats?.candidateCacheHits ?: 0)
                .put("road_network_attempts", stats?.networkAttempts ?: 0)
                .put("graph_cache_hits", stats?.cacheHits ?: 0)
                .put("geometry_vertices", resolved?.points?.size ?: 0)
                .put("resolver_closed", closed).put("saved_road_context_used", false)
                .put("app_state_changed", false).put("preferences_changed", false).put("gps_changed", false)
            File(cache, "private-route-lookup-diagnostic.json").writeText(receipt.toString(2) + "\n")
        }
        val stats = checkNotNull(diagnostics) { "Private lookup diagnostics were unavailable" }
        val elapsed = (completedAt - started).coerceAtLeast(0)
        val safeStatus = stats.status.takeIf { Regex("^[A-Z0-9_]{1,64}$").matches(it) } ?: "NOT_AVAILABLE"
        return "lookup_completed=true matched=${geometry != null} elapsed_ms=$elapsed candidate_network_requests=${stats.candidateNetworkRequests} status=$safeStatus resolver_closed=$closed; no app-state/preferences/GPS changes"
    }

    private fun readBounded(file: File, maximum: Int): String {
        check(file.isFile && file.length() in 1..maximum.toLong()) { "Private lookup input size is invalid" }
        return file.inputStream().use { input ->
            ByteArrayOutputStream().use { output ->
                val chunk = ByteArray(1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    check(output.size() + count <= maximum) { "Private lookup input exceeded its size limit" }
                    output.write(chunk, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }
}
