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
import org.bluetoothauto.navigation.GeoPoint
import org.bluetoothauto.navigation.OwnNavigation
import org.bluetoothauto.navigation.OwnTurnGeometry
import org.bluetoothauto.navigation.RoutePlan
import org.bluetoothauto.navigation.RouteTracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Explicit local full-route diagnostic. No GPS, singleton navigation, preferences or player changes. */
object OwnRouteArtworkAcceptance {
    fun run(test: Instrumentation, baseline: Boolean = false): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run the own-route diagnostic on the instrumentation thread" }
        val cache = test.targetContext.cacheDir
        val file = File(cache, "own-route-artwork-input.json")
        check(file.isFile && file.length() in 1..12_000_000L) { "Operator-owned full-route fixture is missing or too large" }
        val input = JSONObject(file.readText())
        val prefix = if (baseline) "own-route-baseline" else "own-route"
        val route = route(input.getJSONObject("route"))
        val poseIndex = input.optInt("poseIndex", 496)
        val expectedManeuver = input.optInt("expectedManeuverIndex", 5)
        val joinIndex = input.optInt("joinIndex", 536)
        val expectedFocus = input.optInt("expectedFocusIndex", 510)
        check(poseIndex in 0 until route.points.lastIndex && joinIndex in route.points.indices &&
            expectedFocus in route.points.indices && expectedManeuver in route.maneuvers.indices) { "Own-route fixture indices are invalid" }
        val point = route.points[poseIndex]
        val bearing = heading(point, route.points[poseIndex + 1]).toFloat()
        val now = SystemClock.elapsedRealtime()
        val position = Position(point.latitude, point.longitude, bearing, 1f, now, speedMps = 8f)
        val progress = RouteTracker(route).update(point, 1.0, now, bearing)
        check(progress.matched && !progress.offRoute && progress.maneuverIndex == expectedManeuver) {
            "Operator route point did not independently match the intended maneuver"
        }
        val state = OwnNavigation.Snapshot(route = route, progress = progress, position = position)
        val geometryStarted = SystemClock.elapsedRealtimeNanos()
        val geometry = checkNotNull(OwnTurnGeometry.from(state)) { "Production own-route geometry was unavailable" }
        val geometryMillis = (SystemClock.elapsedRealtimeNanos() - geometryStarted) / 1_000_000.0
        if (!baseline) check(geometry.sourceRoute === route) { "Built-in artwork lost the complete route source" }
        check(geometry.junction.distanceTo(route.points[route.maneuvers[expectedManeuver].beginIndex]) < .01) {
            "Current guidance junction was changed to a later ramp fork"
        }
        if (!baseline) {
            check(geometry.points.any { it.distanceTo(route.points[joinIndex]) < .01 }) { "Actual highway join was omitted" }
            check(geometry.points.last().distanceTo(route.points[joinIndex]) > 100.0) { "Joined highway has no useful outgoing context" }
            checkNotNull(geometry.artworkFocus) { "Chained ramp has no explicit real-road artwork focus" }
            check(geometry.artworkFocus!!.distanceTo(route.points[expectedFocus]) < .01) { "Chained ramp focused the wrong road point" }
        }
        val instruction = route.maneuvers[expectedManeuver].instruction
        val guidance = Guidance(instruction, progress.nextManeuverMeters, source = "Own maps")
        val frame = TurnArtworkPolicy.frame(position, geometry, instruction)
        if (!baseline) {
            check(frame.drawPath === route.points) { "Native artwork received a shortened or recreated route instead of the actual whole route" }
            check((frame.junction?.distanceTo(route.points[expectedFocus]) ?: Double.POSITIVE_INFINITY) < .01) { "Camera still frames the long straight ramp prelude" }
        }
        val (focusX, focusY) = project(frame, checkNotNull(frame.junction))
        check(focusX in 100.0..412.0 && focusY in 80.0..288.0) { "Ramp focus left the central usable map area" }

        val ready = CountDownLatch(1)
        var renderer: MapArtworkRenderer? = null
        var initial: Bitmap? = null
        var updated: Bitmap? = null
        var callbacks = 0
        var finalStats: MapArtworkRenderer.Diagnostics? = null
        try {
            val requestedAt = SystemClock.elapsedRealtime()
            test.runOnMainSync {
                renderer = MapArtworkRenderer(test.targetContext)
                renderer!!.render(position, guidance, "IMPERIAL", geometry) { bitmap, status ->
                    callbacks++
                    if (status == "Upcoming turn") {
                        initial?.recycle()
                        initial = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        ready.countDown()
                    }
                }
            }
            check(ready.await(25, TimeUnit.SECONDS)) { "Native full-route ramp picture did not finish" }
            val readyMillis = SystemClock.elapsedRealtime() - requestedAt
            val bitmap = checkNotNull(initial)
            check(bitmap.width == 512 && bitmap.height == 512) { "Own-route artwork dimensions changed" }
            // Preserve the actual native image even if a later visual assertion exposes a bug.
            File(cache, "$prefix-artwork.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Own-route picture could not be saved" }
            }
            var verticesCovered = 0
            for (vertex in frame.path) {
                val (x, y) = project(frame, vertex)
                check(x in 20.0..492.0 && y in 12.0..356.0) { "A real ramp or joined-highway camera vertex was cropped" }
                check(nearBlue(bitmap, x, y)) { "A real ramp or joined-highway camera vertex was not highlighted by MapLibre" }
                verticesCovered++
            }
            val (joinX, joinY) = project(frame, route.points[joinIndex])
            val joinVisible = joinX in 12.0..500.0 && joinY in 12.0..356.0
            val joinHighlighted = joinVisible && nearBlue(bitmap, joinX, joinY)
            if (!baseline) check(joinHighlighted) { "Actual I-5 highway join is not visible and highlighted" }
            val firstRoad = checkNotNull(route.roadSpans.firstOrNull { it.beginIndex == joinIndex }) {
                "The native fixture does not include the authoritative joined-highway edge"
            }
            check(firstRoad.use == "road" && firstRoad.names.isNotEmpty()) { "Expected joined road metadata is absent" }
            var visibleMainline = 0
            var visibleBeyondSlice = 0
            val sliceEnd = geometry.points.last()
            var afterSlice = false
            // Test real mainline vertices and interpolated points on the same source segments.
            // Any route beyond the small geometry window that is visible must stay blue too.
            for (index in joinIndex until min(route.points.lastIndex, joinIndex + 16)) {
                val a = route.points[index]; val b = route.points[index + 1]
                val count = ceil(a.distanceTo(b) / 20.0).toInt().coerceIn(1, 1000)
                for (step in 0..count) {
                    val sample = interpolate(a, b, step.toDouble() / count)
                    if (!afterSlice && a.distanceTo(sliceEnd) + b.distanceTo(sliceEnd) <= a.distanceTo(b) + 1 &&
                        a.distanceTo(sample) > a.distanceTo(sliceEnd) + 1) afterSlice = true
                    val (x, y) = project(frame, sample)
                    if (x !in 20.0..492.0 || y !in 20.0..352.0) continue
                    if (!baseline) check(nearBlue(bitmap, x, y)) { "Visible joined-highway route stops before the map edge" }
                    visibleMainline++
                    if (afterSlice) visibleBeyondSlice++
                }
            }
            if (!baseline) check(visibleMainline >= 4) { "Highway picture lacks a useful visible stretch after the ramp" }
            val fixedUpper = IntArray(512 * 320)
            bitmap.getPixels(fixedUpper, 0, 512, 0, 0, 512, 320)
            test.runOnMainSync {
                val moved = position.copy(bearing = ((bearing + 135) % 360), receivedAt = SystemClock.elapsedRealtime())
                renderer!!.render(moved, guidance.copy(distanceMeters = progress.nextManeuverMeters * .6,
                    receivedAt = SystemClock.elapsedRealtime()), "IMPERIAL", geometry) { output, status ->
                    callbacks++
                    if (status == "Upcoming turn") updated = output.copy(Bitmap.Config.ARGB_8888, false)
                }
                finalStats = renderer!!.diagnostics()
            }
            val second = checkNotNull(updated) { "Distance-only update did not synchronously reuse the turn picture" }
            val changedUpper = IntArray(512 * 320)
            second.getPixels(changedUpper, 0, 512, 0, 0, 512, 320)
            check(fixedUpper.contentEquals(changedUpper)) { "Distance-only update moved or regenerated the ramp picture" }
            check(!bitmap.sameAs(second)) { "Distance footer did not change" }
            val stats = checkNotNull(finalStats)
            check(stats.nativeRouteSnapshots == 1L && stats.snapshotRequests == 1L && stats.cacheHits >= 1L &&
                stats.staticLayerBuilds == 1L) { "Own-route distance refresh did not retain exactly one native route picture" }
            File(cache, "$prefix-distance-artwork.png").outputStream().use {
                check(second.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Updated own-route picture could not be saved" }
            }
            val serialized = JSONObject().put("points", coordinates(geometry.points)).put("junction", coordinate(geometry.junction))
                .put("artworkFocus", if (baseline) JSONObject.NULL else coordinate(checkNotNull(geometry.artworkFocus))).put("key", geometry.key)
                .put("approachSpeedLimitKph", geometry.approachSpeedLimitKph ?: JSONObject.NULL)
                .put("sourceRoutePointCount", route.points.size)
            File(cache, "$prefix-turn-geometry.json").writeText(serialized.toString(2) + "\n")
            val receipt = JSONObject().put("result", "PASS").put("geometry_source", "production_OwnTurnGeometry_from_operator_full_route")
                .put("baseline_mode", baseline)
                .put("live_route_recalculated", false).put("route_points", route.points.size).put("road_spans", route.roadSpans.size)
                .put("maneuver_index", progress.maneuverIndex).put("pose_index", poseIndex).put("joined_highway_index", joinIndex)
                .put("artwork_focus_index", if (baseline) route.maneuvers[expectedManeuver].beginIndex else expectedFocus).put("geometry_points", geometry.points.size)
                .put("whole_route_source_identity_preserved", frame.drawPath === route.points).put("native_source_points", frame.drawPath.size)
                .put("joined_highway_visible", joinVisible).put("joined_highway_highlighted", joinHighlighted)
                .put("camera_route_vertices", frame.path.size).put("native_blue_route_vertices_covered", verticesCovered)
                .put("visible_joined_highway_samples", visibleMainline).put("visible_samples_beyond_geometry_slice", visibleBeyondSlice)
                .put("camera_zoom", frame.zoom).put("camera_bearing", frame.bearingDegrees)
                .put("focus_pixel_x", focusX).put("focus_pixel_y", focusY).put("join_pixel_x", joinX).put("join_pixel_y", joinY)
                .put("local_geometry_ms", geometryMillis).put("artwork_ready_ms", readyMillis).put("native_snapshot_ms", stats.lastSnapshotMs)
                .put("native_route_snapshots", stats.nativeRouteSnapshots).put("native_snapshot_requests", stats.snapshotRequests)
                .put("cached_map_hits", stats.cacheHits).put("upper_320px_identical", true).put("distance_footer_changed", true)
                .put("canvas_route_overlay", false).put("gps_injected", false).put("app_state_changed", false).put("preferences_changed", false)
            File(cache, "$prefix-artwork-result.json").writeText(receipt.toString(2) + "\n")
            test.runOnMainSync { renderer!!.close() }
            val countAtClose = callbacks
            SystemClock.sleep(150)
            test.runOnMainSync { check(callbacks == countAtClose) { "Closed own-route renderer emitted a stale callback" } }
            return "operator-owned full route; matched on-route point; production ramp selection; native full-route source; visible joined highway; one cached picture; distance-only footer; GPS/app state/preferences unchanged"
        } finally {
            test.runOnMainSync { renderer?.close() }
            initial?.recycle(); updated?.recycle()
        }
    }

    private fun route(json: JSONObject): RoutePlan {
        val rawPoints = json.getJSONArray("points")
        check(rawPoints.length() in 2..200_000)
        val points = (0 until rawPoints.length()).map { index ->
            val pair = rawPoints.getJSONArray(index); check(pair.length() == 2)
            GeoPoint(pair.getDouble(0), pair.getDouble(1))
        }
        val rawManeuvers = json.getJSONArray("maneuvers")
        check(rawManeuvers.length() in 1..4000)
        val maneuvers = (0 until rawManeuvers.length()).map { index ->
            val item = rawManeuvers.getJSONObject(index)
            RoutePlan.Maneuver(item.getInt("beginIndex"), item.getInt("endIndex"), item.getString("instruction"),
                item.optString("spokenInstruction", ""), item.getInt("type"), number(item, "seconds"),
                labels(item.optJSONArray("streetNames")), labels(item.optJSONArray("beginStreetNames")), labels(item.optJSONArray("signTexts")))
        }
        var result = RoutePlan(points, maneuvers, json.getDouble("seconds"), json.optString("destinationName", "Operator route"))
        val cumulative = json.optJSONArray("cumulativeSeconds")
        val times = cumulative?.takeIf { it.length() == points.size && (0 until it.length()).all { index -> !it.isNull(index) } }
            ?.let { DoubleArray(points.size - 1) { index -> it.getDouble(index + 1) - it.getDouble(index) } }
        val rawLimits = json.optJSONArray("segmentSpeedLimitsKph")
        val limits = rawLimits?.let { check(it.length() == points.size - 1); DoubleArray(it.length()) { index ->
            when { it.isNull(index) -> Double.NaN; it.optString(index) == "Infinity" -> Double.POSITIVE_INFINITY; else -> it.getDouble(index) }
        } }
        if (limits != null || times != null) result = result.withRoadMetadata(times, limits)
        json.optJSONArray("roadSpans")?.let { spans ->
            check(spans.length() <= 20_000)
            result = result.withRoadSpans((0 until spans.length()).map { index ->
                val span = spans.getJSONObject(index)
                RoutePlan.RoadSpan(span.getInt("beginIndex"), span.getInt("endIndex"), labels(span.optJSONArray("names")),
                    span.getString("travelMode"), span.getString("use"))
            })
        }
        if (json.optBoolean("offline", false)) result = result.withOffline()
        return result
    }

    private fun labels(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        check(array.length() <= 64)
        return (0 until array.length()).map { array.getString(it) }
    }
    private fun number(json: JSONObject, key: String): Double = if (json.isNull(key)) Double.NaN else json.optDouble(key, Double.NaN)
    private fun coordinate(point: GeoPoint) = JSONArray().put(point.latitude).put(point.longitude)
    private fun coordinates(points: List<GeoPoint>) = JSONArray().also { result -> points.forEach { result.put(coordinate(it)) } }
    private fun heading(a: GeoPoint, b: GeoPoint): Double = (Math.toDegrees(atan2(
        (b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)), b.latitude - a.latitude)) + 360) % 360
    private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double) = GeoPoint(
        a.latitude + (b.latitude - a.latitude) * fraction, a.longitude + (b.longitude - a.longitude) * fraction)
    private fun project(frame: TurnArtworkPolicy.Frame, point: GeoPoint): Pair<Double, Double> {
        fun y(latitude: Double): Double { val sine = sin(Math.toRadians(latitude)); return .5 - ln((1+sine)/(1-sine))/(4*Math.PI) }
        var dx = (point.longitude - frame.center.longitude) / 360
        if (dx > .5) dx -= 1
        if (dx < -.5) dx += 1
        val dy = y(point.latitude) - y(frame.center.latitude)
        val angle = Math.toRadians(frame.bearingDegrees); val scale = 512 * 2.0.pow(frame.zoom)
        return (256 + (cos(angle)*dx + sin(angle)*dy)*scale) to (256 + (-sin(angle)*dx + cos(angle)*dy)*scale)
    }
    private fun nearBlue(bitmap: Bitmap, x: Double, y: Double): Boolean {
        val px = x.roundToInt(); val py = y.roundToInt()
        for (dy in -9..9) for (dx in -9..9) {
            val xx = px + dx; val yy = py + dy
            if (dx*dx + dy*dy > 81 || xx !in 0 until bitmap.width || yy !in 0 until 368) continue
            val color = bitmap.getPixel(xx, yy)
            if (Color.blue(color) > 180 && Color.red(color) < 70 && Color.green(color) in 70..170) return true
        }
        return false
    }
}
