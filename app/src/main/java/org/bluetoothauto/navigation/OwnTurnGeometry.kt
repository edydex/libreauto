// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.os.SystemClock
import org.bluetoothauto.model.TurnGeometry
import kotlin.math.*

/** Exact current-route geometry only: no inferred Google junction or straight-line route. */
object OwnTurnGeometry {
    fun from(snapshot: OwnNavigation.Snapshot): TurnGeometry? = from(snapshot, SystemClock.elapsedRealtime())

    internal fun from(snapshot: OwnNavigation.Snapshot, nowElapsed: Long): TurnGeometry? {
        val route = snapshot.route ?: return null
        val progress = snapshot.progress ?: return null
        val position = snapshot.position ?: return null
        val age = nowElapsed - position.receivedAt
        if (snapshot.routing || position.demo || age !in 0..30_000L ||
            !position.accuracy.isFinite() || position.accuracy !in 0f..20f ||
            progress.offRoute || progress.arrived || !progress.usableFix || !progress.matched ||
            !progress.alongMeters.isFinite() || progress.alongMeters !in 0.0..route.lengthMeters()) return null
        val maneuver = route.maneuvers.getOrNull(progress.maneuverIndex) ?: return null
        val junctionAlong = route.cumulativeMeters[maneuver.beginIndex]
        if (junctionAlong + 2 < progress.alongMeters) return null
        // Keep close-turn context around the car; distant turns preview only their final approach.
        val start = max(0.0, max(progress.alongMeters - 15.0, junctionAlong - MAX_APPROACH_METERS))
        val joined = joinedRamp(route, progress.maneuverIndex)
        val end = joined?.endAlong ?: departureEnd(route, progress.maneuverIndex)
        if (end - start < 2) return null
        val points = clip(route, start, end, junctionAlong)
        if (points.size < 2) return null
        val limit = route.speedLimitKphAt(progress.alongMeters).takeIf { it.isFinite() && it > 0 && it < 255 }
        // Metadata copies preserve point/maneuver objects, so limits alone cannot move the frame.
        val identity = System.identityHashCode(route.points.first()).toUInt().toString(16) + ":" +
            System.identityHashCode(route.maneuvers.first()).toUInt().toString(16)
        return TurnGeometry(points, route.points[maneuver.beginIndex], limit, "exact-route",
            "own:$identity:${progress.maneuverIndex}:${maneuver.beginIndex}" +
                (joined?.let { ":join:${it.joinIndex}" } ?: ""),
            sourceRoute = route, artworkFocus = joined?.focusIndex?.let { route.points[it] })
    }

    private data class JoinedRamp(val joinIndex: Int, val endAlong: Double, val focusIndex: Int?)

    /**
     * A motorway connection may contain two signed forks before the actual merge. Valhalla's
     * first maneuver ends at the second fork, not at the joined motorway. Use its exact ramp
     * edges and signed road references to show that complete connection plus a little mainline.
     * Ordinary street turns, different-road forks and later motorway exits remain separate.
     */
    private fun joinedRamp(route: RoutePlan, maneuverIndex: Int): JoinedRamp? {
        val maneuver = route.maneuvers[maneuverIndex]
        if (maneuver.type !in RAMP_ENTRY_TYPES || route.roadSpans.isEmpty()) return null
        val references = highwayReferences(maneuver.streetNames + maneuver.beginStreetNames + maneuver.signTexts)
        if (references.isEmpty()) return null
        val firstSpan = route.roadSpans.indexOfFirst { maneuver.beginIndex in it.beginIndex until it.endIndex }
        if (firstSpan < 0) return null
        var spanIndex = firstSpan
        while (spanIndex < route.roadSpans.size) {
            val span = route.roadSpans[spanIndex]
            if (span.travelMode != "drive" || span.use != "ramp") break
            spanIndex++
        }
        if (spanIndex == firstSpan || spanIndex >= route.roadSpans.size) return null
        val joinedRoad = route.roadSpans[spanIndex]
        val joinedReferences = highwayReferences(joinedRoad.names)
        if (joinedRoad.travelMode != "drive" || joinedRoad.use != "road" ||
            !sameHighway(references, joinedReferences)) return null
        val joinIndex = joinedRoad.beginIndex
        val start = route.cumulativeMeters[maneuver.beginIndex]
        val joinAlong = route.cumulativeMeters[joinIndex]
        if (joinAlong <= start || joinAlong - start > MAX_HIGHWAY_DEPARTURE_METERS) return null
        var focusIndex: Int? = null
        for (step in route.maneuvers.drop(maneuverIndex + 1).takeWhile { it.beginIndex < joinIndex }) {
            if (isContinuation(route, step)) continue
            val labels = highwayReferences(step.streetNames + step.beginStreetNames + step.signTexts)
            val compatibleLabels = sameHighway(references, labels) && sameHighway(joinedReferences, labels)
            if (step.type in MERGE_TYPES && (labels.isEmpty() || compatibleLabels)) continue
            if (step.type !in ASSOCIATED_FORK_TYPES || !compatibleLabels) return null
            val prelude = route.cumulativeMeters[step.beginIndex] - start
            // A long straight entry can put its first sign almost a kilometre before the real
            // interchange bend. Focus the provider's following same-highway fork while retaining
            // the original guidance junction and the complete native blue route underneath.
            if (focusIndex == null && prelude >= 250.0 &&
                route.points[maneuver.beginIndex].distanceTo(route.points[step.beginIndex]) >= prelude * 0.9)
                focusIndex = step.beginIndex
        }
        val nextDecision = route.maneuvers.drop(maneuverIndex + 1).firstOrNull { step ->
            step.beginIndex >= joinIndex && !isContinuation(route, step) &&
                !(step.beginIndex == joinIndex && step.type in MERGE_TYPES)
        }?.let { route.cumulativeMeters[it.beginIndex] } ?: route.lengthMeters()
        return JoinedRamp(joinIndex, min(min(joinAlong + JOINED_ROAD_CONTEXT_METERS, nextDecision),
            min(route.lengthMeters(), start + MAX_HIGHWAY_DEPARTURE_METERS)), focusIndex)
    }

    private data class Highway(val key: String, val direction: String?)

    private fun highwayReferences(labels: List<String>): Set<Highway> = labels.mapNotNull { label ->
        HIGHWAY_REFERENCE.matchEntire(label.trim())?.let { match ->
            val prefix = match.groupValues[1].lowercase().replace(Regex("[ .]"), "")
            val family = when (prefix) {
                "i", "interstate" -> "i"
                "us", "usroute", "us-highway" -> "us"
                "sr", "stateroute", "wa" -> "sr"
                else -> return@let null
            }
            Highway(family + ":" + match.groupValues[2].lowercase(),
                match.groupValues[3].lowercase().firstOrNull()?.toString())
        }
    }.toSet()

    private fun sameHighway(first: Set<Highway>, second: Set<Highway>): Boolean =
        first.map { it.key }.toSet().intersect(second.map { it.key }.toSet()).any { key ->
            val a = first.filter { it.key == key }.mapNotNull { it.direction }.toSet()
            val b = second.filter { it.key == key }.mapNotNull { it.direction }.toSet()
            a.isEmpty() || b.isEmpty() || a.intersect(b).isNotEmpty()
        }

    private fun isContinuation(route: RoutePlan, step: RoutePlan.Maneuver): Boolean =
        step.type in setOf(7, 8, 22) && abs(turnAngle(route, step.beginIndex) ?: 180.0) <= 25

    private fun clip(route: RoutePlan, start: Double, end: Double, junction: Double): List<GeoPoint> {
        val first = firstGreater(route.cumulativeMeters, start)
        val afterLast = firstGreater(route.cumulativeMeters, end)
        val positions = buildList {
            add(start)
            for (index in first until afterLast) {
                val along = route.cumulativeMeters[index]
                if (along < end) add(along)
            }
            add(end)
            if (junction in start..end) add(junction)
        }.distinct().sorted()
        val bounded = if (positions.size <= MAX_POINTS) positions else {
            // Never shortcut a dense curve by joining evenly sampled points. Retain the complete
            // departure and exact junction, shortening only the optional distant approach context.
            val departure = positions.filter { it >= junction }
            if (departure.size >= MAX_POINTS) return emptyList()
            positions.filter { it < junction }.takeLast(MAX_POINTS - departure.size) + departure
        }
        return bounded.map { along -> pointAt(route, along) }
    }

    /** Route maneuver codes and its own geometry are authoritative; no notification text is parsed. */
    private fun departureEnd(route: RoutePlan, maneuverIndex: Int): Double {
        val maneuver = route.maneuvers[maneuverIndex]
        val start = route.cumulativeMeters[maneuver.beginIndex]
        val nextDecision = route.maneuvers.drop(maneuverIndex + 1).firstOrNull { step ->
            !isContinuation(route, step)
        }?.let { route.cumulativeMeters[it.beginIndex] } ?: route.lengthMeters()
        val baseline = min(min(route.lengthMeters(), start + DEPARTURE_METERS), nextDecision)
        // Provider ramp, exit, stay/fork and merge codes may describe broad motorway curves.
        val highwayManeuver = maneuver.type in setOf(17, 18, 19, 20, 21, 23, 24, 25, 37, 38)
        val maximum = if (highwayManeuver) MAX_HIGHWAY_DEPARTURE_METERS else 500.0
        val limit = min(min(route.lengthMeters(), start + maximum), nextDecision)
        if (limit <= baseline) return baseline
        val headings = ArrayList<Pair<Double, Double>>()
        var previous: Double? = null
        var unwrapped = 0.0
        var curveEnd: Double? = null
        var along = start
        while (along < limit - 1) {
            val end = min(limit, along + 25)
            val course = heading(pointAt(route, along), pointAt(route, end))
            previous?.let { unwrapped += difference(it, course) }
            previous = course
            headings.add(along to unwrapped)
            val recent = headings.takeLastWhile { along - it.first <= 150 }
            if (recent.maxOf { it.second } - recent.minOf { it.second } >= 3) curveEnd = end
            along = end
        }
        val settlingContext = if (highwayManeuver) 200.0 else 80.0
        return min(limit, max(baseline, (curveEnd ?: return baseline) + settlingContext))
    }

    private fun turnAngle(route: RoutePlan, index: Int): Double? {
        val along = route.cumulativeMeters[index]
        if (along < 5 || route.lengthMeters() - along < 5) return null
        return difference(heading(pointAt(route, max(0.0, along - 25)), route.points[index]),
            heading(route.points[index], pointAt(route, min(route.lengthMeters(), along + 25))))
    }

    private fun heading(a: GeoPoint, b: GeoPoint): Double {
        var longitude = b.longitude - a.longitude
        if (longitude > 180) longitude -= 360
        if (longitude < -180) longitude += 360
        val x = longitude * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        return (Math.toDegrees(atan2(x, b.latitude - a.latitude)) + 360) % 360
    }

    private fun difference(a: Double, b: Double): Double = (b - a + 540) % 360 - 180

    private fun pointAt(route: RoutePlan, along: Double): GeoPoint {
        val next = firstGreater(route.cumulativeMeters, along)
        if (next == 0) return route.points.first()
        if (next >= route.points.size) return route.points.last()
        val previous = next - 1
        if (route.cumulativeMeters[previous] == along) return route.points[previous]
        val a = route.points[previous]; val b = route.points[next]
        val length = route.cumulativeMeters[next] - route.cumulativeMeters[previous]
        val fraction = if (length > 0) ((along - route.cumulativeMeters[previous]) / length).coerceIn(0.0, 1.0) else 0.0
        var delta = b.longitude - a.longitude
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        var longitude = a.longitude + delta * fraction
        if (longitude > 180) longitude -= 360
        if (longitude < -180) longitude += 360
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction, longitude)
    }

    private fun firstGreater(values: DoubleArray, value: Double): Int {
        var low = 0; var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] <= value) low = middle + 1 else high = middle
        }
        return low
    }

    private const val MAX_POINTS = 256
    private const val MAX_APPROACH_METERS = 1500.0
    private const val DEPARTURE_METERS = 80.0
    private const val MAX_HIGHWAY_DEPARTURE_METERS = 2000.0
    private const val JOINED_ROAD_CONTEXT_METERS = 150.0
    private val RAMP_ENTRY_TYPES = setOf(17, 18, 19, 20, 21, 22, 23, 24)
    private val ASSOCIATED_FORK_TYPES = setOf(17, 18, 19, 22, 23, 24)
    private val MERGE_TYPES = setOf(25, 37, 38)
    private val HIGHWAY_REFERENCE = Regex(
        "(Interstate|I|U\\.?S\\.?|US Route|US-Highway|State Route|SR|WA)[ -]?(\\d+[A-Za-z]?)(?:[ -]+(North|South|East|West|N|S|E|W))?",
        RegexOption.IGNORE_CASE)
}
