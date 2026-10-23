// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import org.bluetoothauto.artwork.MapArtworkRenderer
import org.bluetoothauto.artwork.TurnArtworkPolicy
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.GeoPoint
import org.bluetoothauto.navigation.TurnGeometryResolver
import org.bluetoothauto.navigation.TurnMatcher
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Explicit opt-in, operator-supplied local diagnostic. Does not inject GPS or change app state. */
object PrivateTurnArtworkAcceptance {
    fun run(test: Instrumentation): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run the diagnostic on the instrumentation thread" }
        val context = test.targetContext
        val graphFile = File(context.cacheDir, "diagnostic-turn-graph.json")
        val inputFile = File(context.cacheDir, "diagnostic-turn-input.json")
        val geometryFile = File(context.cacheDir, "diagnostic-turn-geometry.json")
        // Explicit local renderer fixture mode. It neither invokes nor proves live route matching.
        val geometryMode = geometryFile.isFile
        val graph = if (geometryMode) null else try { TurnGeometryResolver.parseGraph(readBounded(graphFile, 3_000_000)) }
            catch (_: Exception) { error("Diagnostic graph fixture is invalid") }
        val input = try { JSONObject(readBounded(inputFile, 8192)) }
            catch (_: Exception) { error("Diagnostic turn input is invalid") }
        val request = try {
            TurnMatcher.Request(GeoPoint(input.getDouble("latitude"), input.getDouble("longitude")),
                input.getDouble("bearing"), input.getDouble("accuracy"),
                input.getString("instruction"), input.getDouble("distanceMeters"))
        } catch (_: Exception) { error("Diagnostic turn input fields are invalid") }
        val geometry = if (geometryMode) readGeometry(geometryFile) else {
            val matched = TurnMatcher.match(checkNotNull(graph), request)
                ?: error("Diagnostic road graph did not yield one confirmed junction")
            TurnGeometry(matched.points, matched.junction, matched.approachSpeedLimitKph, "matched", matched.key)
        }
        check(TurnArtworkPolicy.validGeometry(geometry)) { "Diagnostic geometry failed renderer admission" }
        val position = Position(request.point.latitude, request.point.longitude, request.bearing.toFloat(),
            request.accuracy.toFloat(), receivedAt = SystemClock.elapsedRealtime(), speedMps = 0f)
        val guidance = Guidance(request.instruction, request.distanceMeters, source = "Diagnostic fixture")
        val ready = CountDownLatch(1)
        var renderer: MapArtworkRenderer? = null
        var first: Bitmap? = null
        var updated: Bitmap? = null
        var callbacks = 0
        var stableUpper = false
        var changedFooter = false
        var bluePixels = 0
        var routeVerticesCovered = 0
        var junctionX = Double.NaN
        var junctionY = Double.NaN
        var finalStats: MapArtworkRenderer.Diagnostics? = null
        try {
            test.runOnMainSync {
                renderer = MapArtworkRenderer(context)
                renderer!!.render(position, guidance, "IMPERIAL", geometry) { bitmap, status ->
                    callbacks++
                    if (status == "Upcoming turn") {
                        first = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        ready.countDown()
                    }
                }
            }
            check(ready.await(25, TimeUnit.SECONDS)) { "Native diagnostic turn map was unavailable" }
            val initial = first ?: error("Native diagnostic turn bitmap is missing")
            check(initial.width == 512 && initial.height == 512) { "Diagnostic turn artwork dimensions changed" }
            for (y in 0 until 368) for (x in 0 until 512) {
                val color = initial.getPixel(x, y)
                if (blue(color)) bluePixels++
            }
            check(bluePixels > 100) { "Confirmed road guidance was not drawn on the native map" }
            val frame = TurnArtworkPolicy.frame(position, geometry, guidance.instruction)
            val junctionPixel = project(frame, checkNotNull(frame.junction))
            junctionX = junctionPixel.first; junctionY = junctionPixel.second
            check(junctionX in 127.9..384.1 && junctionY in 103.9..272.1) {
                "Maneuver framing anchor escaped the central map window"
            }
            check((-8..8).any { dy -> (-8..8).any { dx -> blue(initial.getPixel(junctionX.roundToInt()+dx,junctionY.roundToInt()+dy)) } }) {
                "Native blue route is missing at the real framing anchor"
            }
            routeVerticesCovered = frame.path.count { point ->
                val (x, y) = project(frame, point)
                check(x in 22.0..490.0 && y in 16.0..352.0) { "A maneuver vertex was cropped from the map window" }
                val px = x.roundToInt(); val py = y.roundToInt()
                (-12..12).any { dy -> (-12..12).any { dx ->
                    val xx = px + dx; val yy = py + dy
                    if (xx !in 0 until initial.width || yy !in 0 until 368 || dx*dx+dy*dy>144) false
                    else blue(initial.getPixel(xx, yy))
                } }
            }
            check(routeVerticesCovered == frame.path.size) {
                "Native blue route covered only $routeVerticesCovered of ${frame.path.size} maneuver vertices"
            }
            File(context.cacheDir, "diagnostic-turn-artwork.png").outputStream().use {
                check(initial.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Diagnostic artwork could not be saved" }
            }
            SystemClock.sleep(40)
            test.runOnMainSync {
                val moved = position.copy(latitude = (position.latitude + .0002).coerceAtMost(89.9),
                    longitude = (position.longitude + .0002).coerceAtMost(179.9),
                    bearing = ((request.bearing + 135) % 360).toFloat(),
                    receivedAt = SystemClock.elapsedRealtime())
                renderer!!.render(moved, guidance.copy(distanceMeters = request.distanceMeters * .6,
                    receivedAt = SystemClock.elapsedRealtime()), "IMPERIAL", geometry) { bitmap, status ->
                    callbacks++
                    if (status == "Upcoming turn") updated = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                finalStats = renderer!!.diagnostics()
            }
            val second = updated ?: error("Cached distance artwork was not returned synchronously")
            val before = IntArray(512 * 320)
            val after = IntArray(512 * 320)
            initial.getPixels(before, 0, 512, 0, 0, 512, 320)
            second.getPixels(after, 0, 512, 0, 0, 512, 320)
            stableUpper = before.contentEquals(after)
            changedFooter = !initial.sameAs(second)
            val stats = finalStats!!
            check(stableUpper) { "Cached turn map moved when the input position/course changed" }
            check(changedFooter) { "Smaller turn distance did not update the footer" }
            check(stats.nativeRouteSnapshots == 1L) { "Confirmed route was not rendered by native map source/layers" }
            check(stats.snapshotRequests == 1L && stats.cacheHits >= 1L && stats.staticLayerBuilds == 1L) {
                "Distance-only update did not reuse exactly one native turn map"
            }
            File(context.cacheDir, "diagnostic-turn-distance-artwork.png").outputStream().use {
                check(second.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Diagnostic distance artwork could not be saved" }
            }
            test.runOnMainSync { renderer!!.close() }
            val countAtClose = callbacks
            SystemClock.sleep(150)
            test.runOnMainSync { check(callbacks == countAtClose) { "Closed diagnostic renderer emitted a callback" } }
            val receipt = JSONObject()
                .put("geometry_source", if (geometryMode) "operator_supplied_route_geometry_fixture" else "operator_supplied_graph_match")
                .put("graph_source", if (geometryMode) "not_used" else "operator_supplied_one_time_approved_diagnostic")
                .put("production_route_matching_established", false)
                .put("production_overpass_success_established", false).put("matched", !geometryMode).put("path_points", geometry.points.size)
                .put("graph_nodes", graph?.nodes?.size ?: 0).put("graph_ways", graph?.ways?.size ?: 0)
                .put("camera_route_vertices", frame.path.size).put("native_blue_route_vertices_covered", routeVerticesCovered)
                .put("junction_pixel_x", junctionX).put("junction_pixel_y", junctionY)
                .put("native_route_at_framing_anchor", true)
                .put("framing_anchor_in_central_safe_zone", true)
                .put("posted_speed_present", geometry.approachSpeedLimitKph != null)
                .put("native_snapshot_requests", stats.snapshotRequests).put("cached_map_hits", stats.cacheHits)
                .put("static_layer_builds", stats.staticLayerBuilds).put("compositions", stats.compositionCount)
                .put("native_route_snapshots", stats.nativeRouteSnapshots).put("canvas_route_overlay", false)
                .put("blue_guidance_pixels", bluePixels).put("upper_320px_identical", stableUpper)
                .put("distance_footer_changed", changedFooter).put("renderer_closed", true)
                .put("app_state_changed", false).put("preferences_changed", false).put("gps_injected", false)
            File(context.cacheDir, "diagnostic-turn-artwork-result.json").writeText(receipt.toString(2) + "\n")
            return "operator-supplied ${if (geometryMode) "route geometry fixture" else "diagnostic graph matched"}; native map rendered; every camera route vertex blue; one cached snapshot; fixed upper pixels; distance footer changed; no GPS/app-state changes; production route matching not established"
        } finally {
            test.runOnMainSync { renderer?.close() }
            first?.recycle(); updated?.recycle()
        }
    }

    private fun readGeometry(file: File): TurnGeometry {
        val json = try { JSONObject(readBounded(file, 131_072)) }
            catch (_: Exception) { error("Diagnostic geometry fixture is invalid") }
        val coordinates = json.getJSONArray("points")
        check(coordinates.length() in 2..512) { "Diagnostic geometry vertex count is outside renderer bounds" }
        fun point(pair: org.json.JSONArray): GeoPoint {
            check(pair.length() == 2) { "A diagnostic coordinate must contain latitude and longitude" }
            return GeoPoint(pair.getDouble(0), pair.getDouble(1))
        }
        val points = (0 until coordinates.length()).map { point(coordinates.getJSONArray(it)) }
        val speed = if (json.has("approachSpeedLimitKph") && !json.isNull("approachSpeedLimitKph"))
            json.getDouble("approachSpeedLimitKph") else null
        return TurnGeometry(points, point(json.getJSONArray("junction")), speed,
            "operator-supplied-renderer-fixture", json.getString("key"))
    }

    private fun blue(color: Int): Boolean = Color.blue(color) > 180 && Color.red(color) < 70 && Color.green(color) in 70..170

    /** Expected snapshot pixel location at zero pitch, independently checked against actual blue pixels. */
    private fun project(frame: TurnArtworkPolicy.Frame, point: GeoPoint): Pair<Double, Double> {
        fun y(latitude: Double): Double { val sine = sin(Math.toRadians(latitude)); return .5 - ln((1+sine)/(1-sine))/(4*Math.PI) }
        var dx=(point.longitude-frame.center.longitude)/360
        if(dx>.5)dx-=1
        if(dx<-.5)dx+=1
        val dy=y(point.latitude)-y(frame.center.latitude)
        val angle=Math.toRadians(frame.bearingDegrees);val scale=512*2.0.pow(frame.zoom)
        return (256+(cos(angle)*dx+sin(angle)*dy)*scale) to (256+(-sin(angle)*dx+cos(angle)*dy)*scale)
    }

    private fun readBounded(file: File, maximum: Int): String {
        check(file.isFile && file.length() in 1..maximum.toLong()) { "Diagnostic fixture is missing or too large" }
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(out.size() + count <= maximum) { "Diagnostic fixture exceeded its size limit" }
                out.write(buffer, 0, count)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }
}
