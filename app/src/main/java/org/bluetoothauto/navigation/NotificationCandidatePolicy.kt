// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.TurnGeometry
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.*
import org.bluetoothauto.navigation.NotificationTurnHint.Kind

/** Candidate geometry is useful only when the current notification independently constrains it. */
object NotificationCandidatePolicy {
    private data class Projection(val along: Double, val distance: Double)

    fun match(request: TurnMatcher.Request, routes: List<RoutePlan>): TurnGeometry? {
        val hint = NotificationTurnHint.parse(request.instruction) ?: return null
        if (routes.size !in 1..12 || !request.bearing.isFinite() || request.bearing !in 0.0..<360.0 ||
            !request.accuracy.isFinite() || request.accuracy !in 0.0..25.0 ||
            !request.distanceMeters.isFinite() || request.distanceMeters !in 5.0..50_000.0 ||
            abs(request.point.latitude) > 85 || abs(request.point.longitude) > 180) return null
        val matches = routes.mapNotNull { candidate(it, request, hint) }
        val first = matches.firstOrNull() ?: return null
        if (matches.drop(1).any { !equivalent(first, it) }) return null
        // Retain only the shortest shared known horizon before future decisions.
        return matches.minWithOrNull(compareBy<TurnGeometry> { round(departureMeters(it) * 1000) }.thenBy { it.key })
    }

    private fun candidate(route: RoutePlan, request: TurnMatcher.Request, hint: NotificationTurnHint.Hint): TurnGeometry? {
        if (route.points.size !in 3..100_000 || route.maneuvers.size !in 1..4096 ||
            route.points.any { abs(it.latitude) > 85 || abs(it.longitude) > 180 } ||
            !route.lengthMeters().isFinite()) return null
        for ((maneuverIndex, maneuver) in route.maneuvers.withIndex()) {
            if (maneuver.type in 1..3) {
                if (maneuver.beginIndex != 0) return null
                continue
            }
            if (maneuver.type in 4..6) return null
            val compatible = compatible(hint.kind, maneuver)
            val harmless = harmless(route, maneuver)
            val roadAgrees = if (!compatible) false else if (hint.toward) reachesTarget(route, maneuverIndex, hint.road)
                else TurnMatcher.roadMatches(hint.road,
                    maneuver.streetNames + maneuver.beginStreetNames + maneuver.signTexts)
            if (compatible && roadAgrees) {
                val junction = route.cumulativeMeters[maneuver.beginIndex]
                val projection = projectOrigin(route, maneuver.beginIndex, request) ?: return null
                val actual = junction - projection.along
                val tolerance = max(50.0, min(1500.0, request.distanceMeters * .25))
                if (actual >= 5 && abs(actual - request.distanceMeters) <= tolerance &&
                    angleAgrees(hint.kind, angle(route, maneuver.beginIndex)))
                    return geometry(route, maneuverIndex, hint)
            }
            if (!harmless) return null // Never skip an unannounced meaningful turn.
        }
        return null
    }

    private fun harmless(route: RoutePlan, maneuver: RoutePlan.Maneuver) =
        maneuver.type in setOf(7, 8, 22) && abs(angle(route, maneuver.beginIndex) ?: 180.0) <= 25

    private val CAR_USES = setOf("road", "ramp", "turn_channel", "track", "driveway", "alley",
        "parking_aisle", "drive_through", "culdesac", "service_road")

    /** "Toward" names a later road, not the connector entered at this junction. */
    private fun reachesTarget(route: RoutePlan, maneuverIndex: Int, road: String): Boolean {
        val current = route.maneuvers[maneuverIndex]
        val start = route.cumulativeMeters[current.beginIndex]
        val end = min(route.lengthMeters(), start + 300.0)
        if (end <= start) return false
        val following = route.maneuvers.drop(maneuverIndex + 1)
        val barrier = following.firstOrNull { !harmless(route, it) }
            ?.let { route.cumulativeMeters[it.beginIndex] } ?: Double.POSITIVE_INFINITY

        // These spans have already been checked as an exact, complete partition
        // of this route's unchanged geometry. Do not override contradictory edge
        // labels with a sign or a weaker maneuver label.
        if (route.roadSpans.isNotEmpty()) {
            for (span in route.roadSpans) {
                if (Thread.currentThread().isInterrupted) return false
                val until = route.cumulativeMeters[span.endIndex]
                if (until <= start) continue
                val from = max(start, route.cumulativeMeters[span.beginIndex])
                if (from > end || from >= barrier - .01) return false
                if (span.travelMode != "drive" || span.use !in CAR_USES) return false
                if (until > from && TurnMatcher.roadMatches(road, span.names)) return true
                if (span.names.isNotEmpty()) return false
            }
            return false
        }

        // Without named-edge metadata, only actual road labels are evidence.
        // A sign mentioning the target does not establish that the route uses it.
        for (index in maneuverIndex until route.maneuvers.size) {
            if (Thread.currentThread().isInterrupted) return false
            val step = route.maneuvers[index]
            val along = route.cumulativeMeters[step.beginIndex]
            if (along > end || (index > maneuverIndex && !harmless(route, step))) return false
            val names = step.streetNames + step.beginStreetNames
            if (route.cumulativeMeters[step.endIndex] > along && TurnMatcher.roadMatches(road, names)) return true
            if (names.isNotEmpty()) return false
        }
        return false
    }

    private fun compatible(kind: Kind, maneuver: RoutePlan.Maneuver): Boolean {
        val type = maneuver.type
        return when (kind) {
            Kind.LEFT -> type in setOf(14, 15, 16, 19, 21, 24)
            Kind.RIGHT -> type in setOf(9, 10, 11, 18, 20, 23)
            Kind.SLIGHT_LEFT, Kind.KEEP_LEFT -> type in setOf(16, 19, 21, 24)
            Kind.SLIGHT_RIGHT, Kind.KEEP_RIGHT -> type in setOf(9, 18, 20, 23)
            Kind.EXIT, Kind.RAMP -> type in setOf(17, 18, 19, 20, 21, 23, 24)
            Kind.EXIT_LEFT, Kind.RAMP_LEFT -> type in setOf(19, 21, 24)
            Kind.EXIT_RIGHT, Kind.RAMP_RIGHT -> type in setOf(18, 20, 23)
            Kind.MERGE -> type in setOf(25, 37, 38)
            Kind.MERGE_LEFT -> type in setOf(25, 38)
            Kind.MERGE_RIGHT -> type in setOf(25, 37)
            Kind.STRAIGHT -> type in setOf(7, 8, 22)
        }
    }

    private fun angleAgrees(kind: Kind, angle: Double?): Boolean {
        angle ?: return false
        return when (kind) {
            Kind.LEFT -> angle in -170.0..-10.0
            Kind.RIGHT -> angle in 10.0..170.0
            Kind.SLIGHT_LEFT, Kind.KEEP_LEFT -> angle in -90.0..-3.0
            Kind.SLIGHT_RIGHT, Kind.KEEP_RIGHT -> angle in 3.0..90.0
            Kind.EXIT_LEFT, Kind.RAMP_LEFT -> angle in -135.0..-3.0
            Kind.EXIT_RIGHT, Kind.RAMP_RIGHT -> angle in 3.0..135.0
            Kind.EXIT, Kind.RAMP -> abs(angle) <= 135
            Kind.MERGE -> abs(angle) <= 90
            Kind.MERGE_LEFT -> angle in -90.0..-3.0
            Kind.MERGE_RIGHT -> angle in 3.0..90.0
            Kind.STRAIGHT -> abs(angle) <= 35
        }
    }

    private fun angle(route: RoutePlan, index: Int): Double? {
        val along = route.cumulativeMeters[index]
        if (along < 5 || route.lengthMeters() - along < 5) return null
        return delta(heading(pointAt(route, max(0.0, along - 25)), route.points[index]),
            heading(route.points[index], pointAt(route, min(route.lengthMeters(), along + 25))))
    }

    private fun projectOrigin(route: RoutePlan, endIndex: Int, request: TurnMatcher.Request): Projection? {
        val matches = arrayListOf<Projection>()
        for (index in 0 until endIndex) {
            val a = route.points[index]; val b = route.points[index + 1]
            if (abs(delta(request.bearing, heading(a, b))) > 40) continue
            val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dx = (b.longitude - a.longitude) * scale; val dy = b.latitude - a.latitude
            val denominator = dx * dx + dy * dy
            if (denominator <= 0) continue
            val fraction = (((request.point.longitude - a.longitude) * scale * dx +
                (request.point.latitude - a.latitude) * dy) / denominator).coerceIn(0.0, 1.0)
            val projected = interpolate(a, b, fraction)
            val distance = request.point.distanceTo(projected)
            if (distance <= 25) matches.add(Projection(route.cumulativeMeters[index] +
                fraction * (route.cumulativeMeters[index + 1] - route.cumulativeMeters[index]), distance))
        }
        val closest = matches.minByOrNull { it.distance } ?: return null
        if (matches.any { it.distance <= closest.distance + max(5.0, request.accuracy) && abs(it.along - closest.along) > 30 }) return null
        return closest
    }

    private fun geometry(route: RoutePlan, maneuverIndex: Int, hint: NotificationTurnHint.Hint): TurnGeometry? {
        val index = route.maneuvers[maneuverIndex].beginIndex
        val junctionAlong = route.cumulativeMeters[index]
        val start = max(0.0, junctionAlong - 1000)
        // This notification validates one turn. The next intersection may be an
        // endpoint, but its unannounced outgoing branch must not enter the map.
        val nextTurn = route.maneuvers.drop(maneuverIndex + 1).firstOrNull { !harmless(route, it) }
            ?.let { route.cumulativeMeters[it.beginIndex] } ?: route.lengthMeters()
        var end = departureEnd(route, index, hint, nextTurn)
        if (end <= junctionAlong) return null
        fun window(until: Double) = (route.cumulativeMeters.filter { it > start && it < until } +
            listOf(start, junctionAlong, until)).distinct().sorted()
        var positions = window(end)
        if (positions.size > 512) {
            // An optional long-ramp extension must not erase a previously
            // renderable turn. Retain the former exact 500 m window instead
            // of dropping vertices or shortcutting the denser curve.
            end = min(min(route.lengthMeters(), junctionAlong + 500), nextTurn)
            positions = window(end)
        }
        if (positions.size !in 3..512) return null
        val points = positions.map { pointAt(route, it) }
        val limit = route.speedLimitKphAt(max(0.0, junctionAlong - 1)).takeIf { it.isFinite() && it in .1..254.9 }
        val digest = MessageDigest.getInstance("SHA-256")
        fun number(value: Long) { digest.update(ByteBuffer.allocate(8).putLong(value).array()) }
        points.forEach { number(round(it.latitude * 1e6).toLong()); number(round(it.longitude * 1e6).toLong()) }
        number(round(route.points[index].latitude * 1e6).toLong()); number(round(route.points[index].longitude * 1e6).toLong())
        number(java.lang.Double.doubleToLongBits(limit ?: Double.NaN))
        digest.update("${hint.kind}:${hint.toward}:${TurnMatcher.normalize(hint.road)}".toByteArray(Charsets.UTF_8))
        val key = "current-candidate:" + digest.digest().take(16).joinToString("") { "%02x".format(it) }
        return TurnGeometry(points, route.points[index], limit, "candidate-constrained", key)
    }

    /** Keep a long ramp's actual curve, without adding kilometres of settled motorway. */
    private fun departureEnd(route: RoutePlan, index: Int, hint: NotificationTurnHint.Hint,
                             nextTurn: Double): Double {
        val start = route.cumulativeMeters[index]
        val baseline = min(min(route.lengthMeters(), start + 500), nextTurn)
        val ramp = hint.kind in setOf(Kind.EXIT, Kind.EXIT_LEFT, Kind.EXIT_RIGHT,
            Kind.RAMP, Kind.RAMP_LEFT, Kind.RAMP_RIGHT)
        val highwayContinuation = hint.kind in setOf(Kind.KEEP_LEFT, Kind.KEEP_RIGHT,
            Kind.MERGE, Kind.MERGE_LEFT, Kind.MERGE_RIGHT) && TurnMatcher.roadAlternatives(hint.road).any {
            Regex("^(?:i|us|u s|wa|sr|(?:washington |wash )?state (?:route|hwy|rte|rt)) \\d+[a-z]?(?: [nsew])?$")
                .matches(TurnMatcher.normalize(it))
        }
        if (!ramp && !highwayContinuation) return baseline
        val limit = min(min(route.lengthMeters(), start + 2000), nextTurn)
        if (limit <= baseline) return baseline
        val headings = ArrayList<Pair<Double, Double>>()
        var previous: Double? = null
        var unwrapped = 0.0
        var curveEnd: Double? = null
        var along = start
        while (along < limit - 1) {
            if (Thread.currentThread().isInterrupted) return baseline
            val end = min(limit, along + 25)
            val course = heading(pointAt(route, along), pointAt(route, end))
            previous?.let { unwrapped += delta(it, course) }
            previous = course
            headings.add(along to unwrapped)
            // Circularly unwrapped headings detect broad curves as well as
            // tight ramps. Sampling chooses an endpoint, never new geometry.
            val recent = headings.takeLastWhile { along - it.first <= 150 }
            if (recent.maxOf { it.second } - recent.minOf { it.second } >= 3) curveEnd = end
            along = end
        }
        return min(limit, max(baseline, (curveEnd ?: return baseline) + 200))
    }

    private fun equivalent(a: TurnGeometry, b: TurnGeometry): Boolean {
        if (a.junction.distanceTo(b.junction) > 5) return false
        val first=parts(a) ?: return false
        val second=parts(b) ?: return false
        if(!sameOrdered(first.first.asReversed(),second.first.asReversed()))return false
        val common=min(length(first.second),length(second.second))
        if(common<25.0)return false
        return sameOrdered(prefix(first.second,common),prefix(second.second,common))
    }

    private fun parts(geometry:TurnGeometry):Pair<List<GeoPoint>,List<GeoPoint>>? {
        val junction=geometry.points.indices.filter { geometry.points[it].distanceTo(geometry.junction)<=.5 }.singleOrNull() ?: return null
        if(junction==0 || junction==geometry.points.lastIndex)return null
        return geometry.points.take(junction+1) to geometry.points.drop(junction)
    }
    private fun length(points:List<GeoPoint>)=points.zipWithNext { a,b->a.distanceTo(b) }.sum()
    private fun departureMeters(geometry:TurnGeometry)=parts(geometry)?.second?.let(::length) ?: Double.POSITIVE_INFINITY
    private fun prefix(points:List<GeoPoint>,meters:Double):List<GeoPoint> {
        val result=arrayListOf(points.first());var remaining=meters
        for((a,b)in points.zipWithNext()) {
            val segment=a.distanceTo(b)
            if(segment>=remaining) { if(remaining>0)result.add(interpolate(a,b,remaining/segment));break }
            result.add(b);remaining-=segment
        }
        return result
    }
    /** Compare distance along directed curves, so loops cannot match in a different order. */
    private fun sameOrdered(first:List<GeoPoint>,second:List<GeoPoint>):Boolean {
        fun cumulative(points:List<GeoPoint>):List<Double> {
            var total=0.0;val result=arrayListOf(0.0)
            points.zipWithNext { a,b->total+=a.distanceTo(b);result.add(total) }
            return result
        }
        if(first.first().distanceTo(second.first())>5 || first.last().distanceTo(second.last())>5)return false
        val a=cumulative(first);val b=cumulative(second)
        if(abs(a.last()-b.last())>5)return false
        fun at(points:List<GeoPoint>,distances:List<Double>,meters:Double):GeoPoint {
            if(meters>=distances.last())return points.last()
            val exact=distances.binarySearch(meters)
            if(exact>=0)return points[exact]
            val next=(-exact-1).coerceIn(1,points.lastIndex)
            val span=distances[next]-distances[next-1]
            return if(span<=0)points[next] else interpolate(points[next-1],points[next],(meters-distances[next-1])/span)
        }
        return (a+b).filter { it<=min(a.last(),b.last()) }.all { distance->
            at(first,a,distance).distanceTo(at(second,b,distance))<=5
        }
    }

    private fun distanceToSegment(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2))
        val dx = (b.longitude - a.longitude) * scale; val dy = b.latitude - a.latitude
        val denominator = dx * dx + dy * dy
        val fraction = if (denominator <= 0) 0.0 else (((point.longitude - a.longitude) * scale * dx +
            (point.latitude - a.latitude) * dy) / denominator).coerceIn(0.0, 1.0)
        return point.distanceTo(interpolate(a,b,fraction))
    }
    private fun pointAt(route: RoutePlan, along: Double): GeoPoint {
        val exact = route.cumulativeMeters.binarySearch(along)
        if (exact >= 0) return route.points[exact]
        val next = (-exact - 1).coerceIn(1, route.points.lastIndex)
        val length = route.cumulativeMeters[next] - route.cumulativeMeters[next - 1]
        val fraction = if (length <= 0) 0.0 else ((along - route.cumulativeMeters[next - 1]) / length).coerceIn(0.0,1.0)
        return interpolate(route.points[next - 1], route.points[next], fraction)
    }
    private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double) =
        GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction, a.longitude + (b.longitude - a.longitude) * fraction)
    private fun heading(a: GeoPoint, b: GeoPoint) = (Math.toDegrees(atan2(
        (b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)), b.latitude - a.latitude)) + 360) % 360
    private fun delta(from: Double, to: Double) = ((to - from + 540) % 360) - 180
}
