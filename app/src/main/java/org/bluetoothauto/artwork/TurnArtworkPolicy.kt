// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.GeoPoint
import org.bluetoothauto.navigation.NotificationTurnHint
import kotlin.math.*

/** Pure, bounded camera policy. Every highlighted point comes from the supplied real road path. */
internal object TurnArtworkPolicy {
    data class Frame(
        val center: GeoPoint,
        val zoom: Double,
        val geometryKey: String?,
        val path: List<GeoPoint> = emptyList(),
        val junction: GeoPoint? = null,
        val includesPosition: Boolean = true,
        val drawPath: List<GeoPoint> = path,
        val bearingDegrees: Double = 0.0,
        val markJunction: Boolean = false
    ) {
        /** An actual-area view has no proposed route or junction semantics. */
        val currentContext: Boolean get() = includesPosition && geometryKey == null && junction == null &&
            path.isEmpty() && drawPath.isEmpty()

        fun compatible(other: Frame): Boolean = geometryKey == other.geometryKey &&
            abs(zoom - other.zoom) <= 0.3 &&
            bearingDifference(bearingDegrees, other.bearingDegrees) <= 4.0

        /** Before projection exists, bound coalescing without cancelling on every moving fix. */
        fun compatibleInFlight(other: Frame): Boolean = compatible(other) &&
            center.distanceTo(other.center) <= ArtworkPolicy.CACHE_MAX_DISTANCE_METERS
    }

    fun frame(position: Position, geometry: TurnGeometry?, instruction: String? = null): Frame {
        val point = GeoPoint(position.latitude, position.longitude)
        val valid = geometry?.takeIf(::validGeometry)
        if (valid == null) return Frame(point, preferredZoom(null, position.speedMps?.toDouble()), null,
            bearingDegrees = movingCourse(position) ?: 0.0)
        val speedLimit = valid.approachSpeedLimitKph?.takeIf { it.isFinite() && it in 5.0..160.0 }
        // A junction diagram must not change scale as the phone moves or accelerates.
        // Posted speed sizes approach context, but cannot force a confirmed turn to look distant.
        // Highway forks also need a useful view of the road joined when a limit is available.
        val highwayFork = isHighwayFork(instruction)
        val preferred = max(17.3, preferredZoom(speedLimit, null))
        val roadSpeed = speedLimit ?: 40.0
        val approach = when {
            highwayFork && speedLimit == null -> 120.0
            roadSpeed <= 30 -> 28.0
            roadSpeed <= 50 -> 45.0
            roadSpeed <= 70 -> 75.0
            roadSpeed <= 90 -> 120.0
            else -> 120.0
        }
        val focus = valid.artworkFocus ?: valid.junction
        val anchor = junctionAnchor(valid.points, focus)!!
        val bearing = approachBearing(valid.points, anchor) ?: 0.0
        // The route provider already bounds this geometry to the supported maneuver. A fixed
        // departure-distance crop could cut a long ramp in half. Preserve every departure vertex
        // and zoom out enough to show the whole supplied turn; only the approach context is cut.
        val path = maneuverWindow(valid.points, anchor, approach)
        val fitted = fit(path + focus, focus, preferred, bearing, highwayFork)
        // Built-in maps already owns the whole validated route. Pass that same source to MapLibre
        // so every visible route road stays blue, independently of this small camera window.
        return Frame(fitted.first, fitted.second, valid.key, path, focus, false,
            valid.sourceRoute?.points ?: valid.points, bearing,
            markJunction = false)
    }

    private fun isHighwayFork(instruction: String?): Boolean {
        val hint = instruction?.let(NotificationTurnHint::parse) ?: return false
        if (hint.kind !in setOf(NotificationTurnHint.Kind.EXIT, NotificationTurnHint.Kind.EXIT_LEFT,
                NotificationTurnHint.Kind.EXIT_RIGHT, NotificationTurnHint.Kind.RAMP,
                NotificationTurnHint.Kind.RAMP_LEFT, NotificationTurnHint.Kind.RAMP_RIGHT,
                NotificationTurnHint.Kind.KEEP_LEFT, NotificationTurnHint.Kind.KEEP_RIGHT,
                NotificationTurnHint.Kind.MERGE, NotificationTurnHint.Kind.MERGE_LEFT,
                NotificationTurnHint.Kind.MERGE_RIGHT)) return false
        return Regex("\\b(?:Interstate|I|U\\.?S\\.?|WA|SR|State (?:Route|Hwy|Highway|Rte|Rt)|(?:Washington|Wash) State (?:Hwy|Highway|Rte|Rt))[ -]?\\d+[A-Za-z]?\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(hint.road)
    }

    fun validGeometry(geometry: TurnGeometry): Boolean {
        if (geometry.key.isBlank() || geometry.key.length > 256 || geometry.confidence.isBlank() || geometry.points.size !in 2..512) return false
        if (!validPoint(geometry.junction) || geometry.points.any { !validPoint(it) }) return false
        geometry.artworkFocus?.let {
            if (!validPoint(it) || junctionAnchor(geometry.points, it) == null) return false
        }
        var total = 0.0
        var different = false
        for (i in 1 until geometry.points.size) {
            val distance = geometry.points[i - 1].distanceTo(geometry.points[i])
            if (!distance.isFinite() || distance > 20_000) return false
            total += distance
            different = different || distance >= 0.5
        }
        return different && total <= 100_000 && junctionAnchor(geometry.points, geometry.junction) != null
    }

    /** A GPS course is direction of travel, not a stationary compass or guessed north. */
    fun movingCourse(position: Position): Double? {
        val speed = position.speedMps?.toDouble() ?: return null
        val bearing = position.bearing?.toDouble() ?: return null
        return bearing.takeIf { speed.isFinite() && speed in 1.4..70.0 && speed > 1.4 && it.isFinite() && it in 0.0..<360.0 }
    }

    fun bearingDifference(first: Double, second: Double): Double = abs((first - second + 540.0) % 360.0 - 180.0)

    private fun junctionAnchor(points: List<GeoPoint>, junction: GeoPoint): Int? {
        val distances = points.map { it.distanceTo(junction) }
        val nearest = distances.minOrNull() ?: return null
        if (nearest > 8.0) return null
        val indices = points.indices.filter { distances[it] <= nearest + 0.10 }
        // Adjacent duplicate vertices are harmless; repeated visits after a loop are ambiguous.
        if (indices.zipWithNext().any { (first, second) -> second != first + 1 }) return null
        return indices.lastOrNull()
    }

    private fun approachBearing(points: List<GeoPoint>, anchor: Int): Double? {
        val end = points[anchor]
        var start = end
        var travelled = 0.0
        for (index in anchor - 1 downTo 0) {
            val near = points[index + 1]; val far = points[index]
            val distance = near.distanceTo(far)
            if (distance <= 0.01) continue
            val needed = 25.0 - travelled
            if (distance >= needed) { start = interpolate(near, far, needed / distance); travelled = 25.0; break }
            start = far; travelled += distance
        }
        if (travelled < 1.0 || start.distanceTo(end) < 0.5) return null
        var dx = (end.longitude - start.longitude) / 360.0
        if (dx > 0.5) dx -= 1
        if (dx < -0.5) dx += 1
        val dy = mercatorY(end.latitude) - mercatorY(start.latitude)
        return (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
    }

    fun preferredZoom(limitKph: Double?, speedMps: Double?): Double {
        val limit = limitKph?.takeIf { it.isFinite() && it in 5.0..160.0 }
        val speed = limit ?: speedMps?.takeIf { it.isFinite() && it in 0.0..70.0 }?.times(3.6) ?: return 16.8
        return when {
            speed <= 30 -> 17.7
            speed <= 50 -> 17.3
            speed <= 70 -> 16.8
            speed <= 90 -> 16.4
            speed <= 110 -> 16.0
            else -> 15.6
        }
    }

    private fun validPoint(point: GeoPoint) = point.latitude.isFinite() && abs(point.latitude) <= 85 &&
        point.longitude.isFinite() && abs(point.longitude) <= 180

    /** Cut only along existing segments; never connect a phone fix to an unrelated road. */
    private fun maneuverWindow(points: List<GeoPoint>, anchor: Int, before: Double): List<GeoPoint> {
        val approach = ArrayList<GeoPoint>(); approach.add(points[anchor])
        var remaining = before
        for (i in anchor - 1 downTo 0) {
            val near = points[i + 1]; val far = points[i]; val distance = near.distanceTo(far)
            if (distance > remaining) { approach.add(interpolate(near, far, remaining / distance)); break }
            approach.add(far); remaining -= distance
            if (remaining <= 0) break
        }
        approach.reverse()
        approach.addAll(points.subList(anchor + 1, points.size))
        return approach
    }

    private fun interpolate(from: GeoPoint, to: GeoPoint, amount: Double): GeoPoint {
        var longitude = to.longitude - from.longitude
        if (longitude > 180) longitude -= 360
        if (longitude < -180) longitude += 360
        return GeoPoint(from.latitude + (to.latitude - from.latitude) * amount,
            longitude(from.longitude + longitude * amount))
    }

    private fun fit(points: List<GeoPoint>, anchor: GeoPoint, preferred: Double, bearing: Double,
                    asymmetric: Boolean = false): Pair<GeoPoint, Double> {
        val anchorX = (anchor.longitude + 180) / 360
        val anchorY = mercatorY(anchor.latitude)
        val angle = Math.toRadians(bearing); val cosine = cos(angle); val sine = sin(angle)
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (point in points) {
            var x = (point.longitude + 180) / 360
            while (x - anchorX > 0.5) x -= 1
            while (x - anchorX < -0.5) x += 1
            val dx = x - anchorX; val dy = mercatorY(point.latitude) - anchorY
            val rotatedX = cosine * dx + sine * dy
            val rotatedY = -sine * dx + cosine * dy
            minX = min(minX, rotatedX); maxX = max(maxX, rotatedX)
            minY = min(minY, rotatedY); maxY = max(maxY, rotatedY)
        }
        // Compact turns retain the familiar junction-centered view. Long one-sided ramps can
        // use the empty half of the map, while a central safe zone keeps the junction readable.
        val extentX = max(abs(minX), abs(maxX))
        val extentY = max(abs(minY), abs(maxY))
        val zoomX = if (extentX > 0) log2(215.0 / (512 * extentX)) else 18.0
        val zoomY = if (extentY > 0) log2((ArtworkLayout.ROAD_VISIBLE_HEIGHT / 2) / (512 * extentY)) else 18.0
        val centeredZoom = min(preferred, min(zoomX, zoomY)).coerceIn(0.0, 18.0)
        var zoom = centeredZoom
        var anchorPixelX = 256.0
        var anchorPixelY = ArtworkLayout.ROAD_CENTER_Y
        if (asymmetric && centeredZoom + 1e-8 < preferred) {
            val left = 32.0; val right = 480.0; val top = 24.0; val bottom = 344.0
            val anchorLeft = 128.0; val anchorRight = 384.0
            val anchorTop = 104.0; val anchorBottom = 272.0
            fun permittedScale(minimum: Double, maximum: Double, edgeStart: Double,
                               edgeEnd: Double, anchorStart: Double, anchorEnd: Double): Double {
                var scale = Double.POSITIVE_INFINITY
                if (maximum > minimum) scale = min(scale, (edgeEnd - edgeStart) / (maximum - minimum))
                if (maximum > 0) scale = min(scale, (edgeEnd - anchorStart) / maximum)
                if (minimum < 0) scale = min(scale, (anchorEnd - edgeStart) / -minimum)
                return scale
            }
            val allowedScale = min(permittedScale(minX, maxX, left, right, anchorLeft, anchorRight),
                permittedScale(minY, maxY, top, bottom, anchorTop, anchorBottom))
            zoom = min(preferred, log2(allowedScale / 512)).coerceIn(0.0, 18.0)
            val scale = 512 * 2.0.pow(zoom)
            fun anchorPixel(minimum: Double, maximum: Double, edgeStart: Double,
                            edgeEnd: Double, anchorStart: Double, anchorEnd: Double): Double {
                val low = max(anchorStart, edgeStart - minimum * scale)
                val high = min(anchorEnd, edgeEnd - maximum * scale)
                val middle = (edgeStart + edgeEnd - (minimum + maximum) * scale) / 2
                return middle.coerceIn(low, max(low, high))
            }
            anchorPixelX = anchorPixel(minX, maxX, left, right, anchorLeft, anchorRight)
            anchorPixelY = anchorPixel(minY, maxY, top, bottom, anchorTop, anchorBottom)
        }
        val scale = 512 * 2.0.pow(zoom)
        val rotatedCenterX = (256.0 - anchorPixelX) / scale
        val rotatedCenterY = (256.0 - anchorPixelY) / scale
        // Invert the screen rotation so the same card clearance works at every approach angle.
        val centerX = anchorX + cosine * rotatedCenterX - sine * rotatedCenterY
        val centerY = anchorY + sine * rotatedCenterX + cosine * rotatedCenterY
        val latitude = Math.toDegrees(atan(sinh(Math.PI * (1 - 2 * centerY))))
        val center = GeoPoint(latitude, longitude(centerX * 360 - 180))
        return center to zoom
    }

    private fun longitude(value: Double): Double = ((value + 180) % 360 + 360) % 360 - 180

    private fun mercatorY(latitude: Double): Double {
        val sine = sin(Math.toRadians(latitude))
        return 0.5 - ln((1 + sine) / (1 - sine)) / (4 * Math.PI)
    }
}
