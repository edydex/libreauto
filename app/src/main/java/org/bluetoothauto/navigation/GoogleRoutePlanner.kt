// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.TurnGeometry
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.*

/** Validates captured Google steps against real connected roads or an independent route candidate. */
object GoogleRoutePlanner {
    private val CAR_ROADS = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
        "residential", "living_street", "service", "motorway_link", "trunk_link", "primary_link",
        "secondary_link", "tertiary_link")
    enum class StopReason { COMPLETE, INVALID_CAPTURE, INVALID_ANCHOR, MISSING_LEG_DISTANCE,
        UNSUPPORTED_GRAPH_STEP, LEG_REQUIRES_ROUTE_GEOMETRY, GRAPH_NO_UNIQUE_MATCH,
        INVALID_CANDIDATE, CANDIDATE_NO_MATCH, CANDIDATE_AMBIGUOUS, CANDIDATE_EXTRA_TURN,
        CANDIDATE_DISTANCE_MISMATCH, NEEDS_ROAD_DETAIL }
    data class PlannedTurn(val stepIndex: Int, val stepKey: String, val instruction: String,
                           val geometry: TurnGeometry,
                           /** Along the validated path relative to the first planned junction; never a live countdown. */
                           val distanceAlongMeters: Double)
    data class Plan(val fingerprint: String, val turns: List<PlannedTurn>, val complete: Boolean,
                    val stopReason: StopReason, val validatedThroughIndex: Int,
                    val detailRequests: List<DetailRequest> = emptyList())
    data class DetailRequest(val stepIndex: Int, val center: GeoPoint, val radiusMeters: Double = 500.0)

    /** Chains from a confirmed junction; a later phone fix is not invented or injected. */
    fun fromGraph(snapshot: GoogleDirectionsSnapshot, currentStepIndex: Int,
                  confirmed: TurnGeometry, graph: TurnMatcher.Graph): Plan {
        if (!valid(snapshot, currentStepIndex)) return plan(snapshot, emptyList(), StopReason.INVALID_CAPTURE)
        if (confirmed.points.size !in 3..256 ||
            confirmed.points.none { it.distanceTo(confirmed.junction) < .1 })
            return plan(snapshot, emptyList(), StopReason.INVALID_ANCHOR)
        val turns = arrayListOf(planned(snapshot.steps[currentStepIndex], confirmed, 0.0))
        var previous = turns.last()
        for (index in currentStepIndex + 1 until snapshot.steps.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val step = snapshot.steps[index]
            val leg = snapshot.steps[index - 1].followingDistanceMeters
                ?.takeIf { it.isFinite() && it > 0 }
                ?: return plan(snapshot, turns, StopReason.MISSING_LEG_DISTANCE)
            if (TurnMatcher.turn(step.instruction) == null)
                return plan(snapshot, turns, StopReason.UNSUPPORTED_GRAPH_STEP)
            val points = previous.geometry.points
            val junction = previous.geometry.junction
            val junctionIndex = points.indexOfFirst { it.distanceTo(junction) < .1 }
            if (junctionIndex < 0 || junctionIndex >= points.lastIndex)
                return plan(snapshot, turns, StopReason.INVALID_ANCHOR)
            val tail = (junctionIndex + 1..points.lastIndex).sumOf { points[it - 1].distanceTo(points[it]) }
            val remaining = leg - tail
            if (remaining !in 5.0..1200.0)
                return plan(snapshot, turns, StopReason.LEG_REQUIRES_ROUTE_GEOMETRY)
            val anchor = points.last()
            val course = heading(points[points.lastIndex - 1], anchor)
            val match = TurnMatcher.match(graph, TurnMatcher.Request(anchor, course, 5.0, step.instruction, remaining))
                ?: return plan(snapshot, turns, StopReason.GRAPH_NO_UNIQUE_MATCH)
            val matchJunction = match.points.indexOfFirst { it.distanceTo(match.junction) < .1 }
            if (matchJunction < 1) return plan(snapshot, turns, StopReason.GRAPH_NO_UNIQUE_MATCH)
            val matchedLeg = tail + (1..matchJunction).sumOf { match.points[it - 1].distanceTo(match.points[it]) }
            if (!distanceAgrees(matchedLeg, leg, snapshot.steps[index - 1].followingDistanceLabel))
                return plan(snapshot, turns, StopReason.CANDIDATE_DISTANCE_MISMATCH)
            val geometry = TurnGeometry(match.points, match.junction, match.approachSpeedLimitKph, "matched-google-trip", "")
            previous = planned(step, geometry, previous.distanceAlongMeters + matchedLeg)
            turns.add(previous)
        }
        return plan(snapshot, turns, StopReason.COMPLETE)
    }

    /**
     * The provider route is a candidate, never assumed to be Google's route. Every accepted step
     * agrees in kind, explicit road/reference and distance; meaningful extra turns reject alignment.
     * A differing/ambiguous suffix is returned as a partial plan, never silently rendered as Google.
     */
    @JvmOverloads fun fromCandidate(snapshot: GoogleDirectionsSnapshot, currentStepIndex: Int,
                                   candidate: RoutePlan, currentDistanceMeters: Double? = null,
                                   detailGraphs: List<TurnMatcher.Graph> = emptyList()): Plan {
        if (!valid(snapshot, currentStepIndex)) return plan(snapshot, emptyList(), StopReason.INVALID_CAPTURE)
        if (candidate.points.size !in 2..200_000 || candidate.maneuvers.size !in 1..4096 ||
            !candidate.lengthMeters().isFinite() || candidate.lengthMeters() <= 0)
            return plan(snapshot, emptyList(), StopReason.INVALID_CANDIDATE)
        val turns = arrayListOf<PlannedTurn>()
        // Departure is an origin description, not a future junction. A harmless
        // initial connector may have a different compass narrative in the provider.
        val departure = snapshot.steps[currentStepIndex].kind == GoogleDirectionsStepKind.DEPARTURE
        val firstStep = currentStepIndex + if (departure) 1 else 0
        if (firstStep !in snapshot.steps.indices) return plan(snapshot, turns, StopReason.INVALID_CAPTURE)
        var previousManeuver = -1
        var previousAlong = 0.0
        var firstAlong = 0.0
        for (index in firstStep until snapshot.steps.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val step = snapshot.steps[index]
            val expected = if (index == firstStep) currentDistanceMeters ?:
                (if (departure) snapshot.steps[currentStepIndex].followingDistanceMeters else null)
                else snapshot.steps[index - 1].followingDistanceMeters
            if ((index > firstStep || departure) && (expected == null || !expected.isFinite() || expected < 0))
                return plan(snapshot, turns, StopReason.MISSING_LEG_DISTANCE)
            val label = if (index == firstStep && (!departure || currentDistanceMeters != null)) ""
                else snapshot.steps[index - 1].followingDistanceLabel
            val candidates = ArrayList<Int>()
            var sawKindAndRoad = false
            for (maneuverIndex in previousManeuver + 1 until candidate.maneuvers.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val maneuver = candidate.maneuvers[maneuverIndex]
                if (!compatible(step, maneuver) || !geometryAgrees(step, candidate, maneuver.beginIndex)) continue
                sawKindAndRoad = true
                val along = candidate.cumulativeMeters[maneuver.beginIndex]
                val actual = if (previousManeuver < 0) along else along - previousAlong
                if (expected == null || distanceAgrees(actual, expected, label)) candidates.add(maneuverIndex)
            }
            if (candidates.isEmpty()) {
                if (kind(step.instruction) == Kind.STRAIGHT && previousManeuver >= 0 && expected != null) {
                    val wantedAlong = previousAlong + expected
                    val spanEnd = candidate.cumulativeMeters[candidate.maneuvers[previousManeuver].endIndex]
                    if (wantedAlong > previousAlong + 5 && wantedAlong < spanEnd - 5) {
                        val center = pointAt(candidate, wantedAlong)
                        val exactEdges = edgeContinuation(snapshot.steps[index - 1], step, candidate, previousAlong,
                            spanEnd, expected, label)
                        val matches = if (exactEdges.isNotEmpty()) exactEdges else continuation(snapshot.steps[index - 1], step, candidate, previousAlong,
                            spanEnd, expected, label, detailGraphs.take(16))
                        if (matches.size == 1) {
                            val match = matches.single()
                            turns.add(planned(step, geometry(candidate, match.along, match.point), match.along - firstAlong))
                            previousAlong = match.along
                            continue
                        }
                        if (matches.size > 1) return plan(snapshot, turns, StopReason.CANDIDATE_AMBIGUOUS)
                        val context = sameRoadContext(snapshot, index, candidate, previousManeuver,
                            previousAlong, expected, label)
                        if (context != null) {
                            val artwork = geometry(candidate, context.along, context.point)
                                .copy(confidence = "google-route-context")
                            turns.add(planned(step, artwork, context.along - firstAlong))
                            previousAlong = context.along
                            continue
                        }
                        if (detailGraphs.none { graph -> graph.nodes.values.any { it.distanceTo(center) <= 600 } })
                            return plan(snapshot, turns, StopReason.NEEDS_ROAD_DETAIL,
                                listOf(DetailRequest(index, center)))
                    }
                }
                return plan(snapshot, turns,
                    if (sawKindAndRoad) StopReason.CANDIDATE_DISTANCE_MISMATCH else StopReason.CANDIDATE_NO_MATCH)
            }
            if (candidates.zipWithNext().any { (a, b) -> candidate.maneuvers[a].beginIndex == candidate.maneuvers[b].beginIndex })
                return plan(snapshot, turns, StopReason.CANDIDATE_AMBIGUOUS)
            // A later same-road ramp cannot consume an earlier unannounced ramp. Ordered
            // provider events disambiguate rounded long-leg distances without picking closest.
            val admissible = candidates.filter { chosen -> (previousManeuver + 1 until chosen).none { !harmless(candidate, it) } }
            if (admissible.isEmpty()) return plan(snapshot, turns, StopReason.CANDIDATE_EXTRA_TURN)
            if (admissible.size != 1) return plan(snapshot, turns, StopReason.CANDIDATE_AMBIGUOUS)
            val chosen = admissible.single()
            val maneuver = candidate.maneuvers[chosen]
            val along = candidate.cumulativeMeters[maneuver.beginIndex]
            if (turns.isEmpty()) firstAlong = along
            val geometry = geometry(candidate, maneuver.beginIndex)
            turns.add(planned(step, geometry, (along - firstAlong).coerceAtLeast(0.0)))
            previousManeuver = chosen; previousAlong = along
        }
        if ((previousManeuver + 1 until candidate.maneuvers.size).any { !harmless(candidate, it) })
            return plan(snapshot, turns, StopReason.CANDIDATE_EXTRA_TURN)
        return plan(snapshot, turns, StopReason.COMPLETE)
    }

    private enum class Kind { START, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN, STRAIGHT, MERGE,
        EXIT_LEFT, EXIT_RIGHT, EXIT, ROUNDABOUT, ARRIVE, UNKNOWN }

    private fun kind(text: String): Kind {
        val value = text.lowercase(Locale.ROOT).trim()
        return when {
            value.startsWith("head ") -> Kind.START
            value.startsWith("destination ") || value.startsWith("your destination ") || value.startsWith("arriv") ||
                value.startsWith("you have arrived") -> Kind.ARRIVE
            "u-turn" in value || "u turn" in value -> Kind.UTURN
            "roundabout" in value -> Kind.ROUNDABOUT
            Regex("^(?:turn|keep|bear|merge) (?:slight(?:ly)? |sharp(?:ly)? )?left\\b").containsMatchIn(value) ->
                if (value.startsWith("keep") || value.startsWith("bear") || "slight" in value) Kind.SLIGHT_LEFT else Kind.LEFT
            Regex("^(?:turn|keep|bear|merge) (?:slight(?:ly)? |sharp(?:ly)? )?right\\b").containsMatchIn(value) ->
                if (value.startsWith("keep") || value.startsWith("bear") || "slight" in value) Kind.SLIGHT_RIGHT else Kind.RIGHT
            "exit" in value || "ramp" in value -> when {
                "left lane" in value || "on the left" in value || value.startsWith("exit left") -> Kind.EXIT_LEFT
                "right lane" in value || "on the right" in value || value.startsWith("exit right") -> Kind.EXIT_RIGHT
                else -> Kind.EXIT
            }
            value.startsWith("merge") -> Kind.MERGE
            value.startsWith("continue") || value.startsWith("go straight") || value.startsWith("proceed") -> Kind.STRAIGHT
            else -> Kind.UNKNOWN
        }
    }

    private fun candidateKind(maneuver: RoutePlan.Maneuver): Kind = when (maneuver.type) {
        1, 2, 3 -> Kind.START
        4, 5, 6 -> Kind.ARRIVE
        7, 8, 22 -> Kind.STRAIGHT
        9, 23 -> Kind.SLIGHT_RIGHT
        10, 11 -> Kind.RIGHT
        12, 13 -> Kind.UTURN
        14, 15 -> Kind.LEFT
        16, 24 -> Kind.SLIGHT_LEFT
        17 -> Kind.EXIT
        18, 20 -> Kind.EXIT_RIGHT
        19, 21 -> Kind.EXIT_LEFT
        25 -> Kind.MERGE
        26, 27 -> Kind.ROUNDABOUT
        else -> kind(maneuver.instruction)
    }

    private fun compatible(step: GoogleDirectionsStep, candidate: RoutePlan.Maneuver): Boolean {
        val desired = if (step.kind == GoogleDirectionsStepKind.ARRIVAL) Kind.ARRIVE else kind(step.instruction)
        val actual = candidateKind(candidate)
        val same = when (desired) {
            Kind.LEFT -> actual in setOf(Kind.LEFT, Kind.SLIGHT_LEFT, Kind.EXIT_LEFT)
            Kind.RIGHT -> actual in setOf(Kind.RIGHT, Kind.SLIGHT_RIGHT, Kind.EXIT_RIGHT)
            Kind.SLIGHT_LEFT -> actual in setOf(Kind.SLIGHT_LEFT, Kind.EXIT_LEFT)
            Kind.SLIGHT_RIGHT -> actual in setOf(Kind.SLIGHT_RIGHT, Kind.EXIT_RIGHT)
            Kind.EXIT -> actual in setOf(Kind.EXIT, Kind.EXIT_LEFT, Kind.EXIT_RIGHT)
            Kind.EXIT_LEFT -> actual in setOf(Kind.EXIT_LEFT, Kind.SLIGHT_LEFT)
            Kind.EXIT_RIGHT -> actual in setOf(Kind.EXIT_RIGHT, Kind.SLIGHT_RIGHT)
            Kind.MERGE -> actual in setOf(Kind.MERGE, Kind.SLIGHT_LEFT, Kind.SLIGHT_RIGHT)
            else -> desired != Kind.UNKNOWN && desired == actual
        }
        if (!same) return false
        if (desired == Kind.ARRIVE) return true
        val wanted = roadTokens(step.instruction)
        if (wanted.isEmpty()) return true // Explicit bare left/right uses side + bounded leg + ordered neighbours.
        val offered = roadTokens(candidate.instruction) + roadTokens(candidate.spokenInstruction) +
            (candidate.streetNames + candidate.beginStreetNames + candidate.signTexts).flatMap(::nameAliases)
        return wanted.any { it in offered }
    }

    /** Only explicit road wording/references: destination names after 'toward' are not road names. */
    private fun roadTokens(instruction: String): Set<String> {
        val value = instruction.take(600).split(Regex("\\s+towards?\\s+", RegexOption.IGNORE_CASE)).first()
        val road = Regex("\\b(?:onto|on)\\s+(?!the\\s+(?:left|right)\\b)(.+)$", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
            ?.split(Regex("\\s+(?:towards?|to)\\s+", RegexOption.IGNORE_CASE))?.firstOrNull()
            ?.trim()?.trimEnd('.', ',')
        // When an explicit target exists, an earlier route/exit reference does not override it.
        val target = road?.takeIf { it.isNotBlank() } ?: value
        val refs = Regex("\\b(?:I|US|WA|SR)[ -]?\\d+[A-Z]?\\b", RegexOption.IGNORE_CASE).findAll(target)
            .map { TurnMatcher.normalize(it.value) }.toMutableSet()
        refs.addAll(stateReferences(target))
        if (!road.isNullOrBlank()) {
            val normalized = TurnMatcher.normalize(road)
            refs.addAll(nameAliases(normalized))
        }
        return refs.filter { it.isNotBlank() }.toSet()
    }

    private fun nameAliases(name: String): Set<String> {
        val normalized = TurnMatcher.normalize(name)
        return (setOf(normalized, normalized.replace(Regex("\\s+(?:n|s|e|w|northbound|southbound|eastbound|westbound)$"), "")) + stateReferences(name))
            .filter { it.isNotBlank() }.toSet()
    }

    /** Equivalent explicit Washington/state-route labels used by the two providers. */
    private fun stateReferences(value: String): Set<String> = Regex(
        "\\b(?:Washington\\s+State\\s+(?:Hwy|Highway|Rte|Rt)|State\\s+(?:Route|Hwy|Highway|Rte|Rt)|WA|SR)[ -]?(\\d+[A-Z]?)\\b",
        RegexOption.IGNORE_CASE).findAll(value).map { "state route ${it.groupValues[1].lowercase(Locale.ROOT)}" }.toSet()

    private data class Continuation(val point: GeoPoint, val along: Double)
    /** A redundant Continue can describe a path, without claiming an exact junction.
     * Both adjacent Google legs and the next actual turn must already validate against
     * this one provider maneuver. Its focus is only a real route vertex for map framing. */
    private fun sameRoadContext(snapshot: GoogleDirectionsSnapshot, index: Int, route: RoutePlan,
                                previousManeuver: Int, start: Double, expected: Double,
                                label: String): Continuation? {
        val step = snapshot.steps[index]
        val incoming = roadTokens(snapshot.steps[index - 1].instruction)
        val outgoing = roadTokens(step.instruction)
        if (incoming.isEmpty() || outgoing.isEmpty()) return null
        val following = snapshot.steps.getOrNull(index + 1) ?: return null
        val next = route.maneuvers.getOrNull(previousManeuver + 1) ?: return null
        if (!compatible(following, next) || !geometryAgrees(following, route, next.beginIndex)) return null
        val after = step.followingDistanceMeters?.takeIf { it.isFinite() && it > 0 } ?: return null
        val end = route.cumulativeMeters[next.beginIndex]
        if (route.maneuvers[previousManeuver].endIndex != next.beginIndex ||
            !distanceAgrees(end - start, expected + after, label)) return null
        val spans = route.roadSpans.filter { route.cumulativeMeters[it.endIndex] > start &&
            route.cumulativeMeters[it.beginIndex] < end }
        // Two labels (for example a highway reference and its street name) may describe
        // the same road only when BOTH remain valid over every exact span in this leg.
        if (spans.isEmpty() || spans.any { it.travelMode != "drive" || it.use !in CAR_USES ||
            !edgeNamesMatch(incoming, it.names) || !edgeNamesMatch(outgoing, it.names) }) return null
        val wanted = start + expected
        var low = 0; var high = route.points.lastIndex
        while (low < high) { val middle = (low + high) ushr 1
            if (route.cumulativeMeters[middle] < wanted) low = middle + 1 else high = middle }
        val focus = listOf(low - 1, low).filter { it in route.points.indices &&
            route.cumulativeMeters[it] > start + 5 && route.cumulativeMeters[it] < end - 5 }
            .minByOrNull { abs(route.cumulativeMeters[it] - wanted) } ?: return null
        val along = route.cumulativeMeters[focus]
        if (!distanceAgrees(along - start, expected, label) ||
            !distanceAgrees(end - along, after, step.followingDistanceLabel)) return null
        return Continuation(route.points[focus], along)
    }

    private val CAR_USES = setOf("road", "ramp", "turn_channel", "track", "driveway", "alley",
        "parking_aisle", "drive_through", "culdesac", "service_road")
    private fun edgeContinuation(previous: GoogleDirectionsStep, step: GoogleDirectionsStep, route: RoutePlan,
                                 start: Double, end: Double, expected: Double, label: String): List<Continuation> {
        val incoming = roadTokens(previous.instruction)
        val outgoing = roadTokens(step.instruction)
        if (incoming.isEmpty() || outgoing.isEmpty()) return emptyList()
        val result = ArrayList<Continuation>()
        for ((before, after) in route.roadSpans.zipWithNext()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (before.travelMode != "drive" || after.travelMode != "drive" || before.use !in CAR_USES || after.use !in CAR_USES) continue
            // An overlapping alias already present on both sides is not evidence of a transition.
            if (!edgeNamesMatch(incoming, before.names) || edgeNamesMatch(outgoing, before.names) ||
                !edgeNamesMatch(outgoing, after.names)) continue
            val along = route.cumulativeMeters[after.beginIndex]
            if (along !in start + 5..end - 5 || !distanceAgrees(along - start, expected, label)) continue
            if (bend(route, after.beginIndex) > 45) continue
            result.add(Continuation(route.points[after.beginIndex], along))
        }
        return result
    }

    private fun edgeNamesMatch(wanted: Set<String>, names: List<String>): Boolean {
        val offered = names.flatMap(::nameAliases).toSet()
        if (wanted.any(offered::contains)) return true
        val directions = setOf("n", "s", "e", "w", "ne", "nw", "se", "sw")
        // Google sometimes omits the leading cardinal on an otherwise exact road label.
        // Only omit it from the provider side when Google's explicit label has none:
        // an explicit East road must never validate an explicit West road.
        return wanted.any { target -> target.substringBefore(' ') !in directions && offered.any { label ->
            label.substringBefore(' ') in directions && label.substringAfter(' ', "") == target
        } }
    }
    private fun continuation(previous: GoogleDirectionsStep, step: GoogleDirectionsStep, route: RoutePlan,
                             start: Double, end: Double, expected: Double, label: String,
                             graphs: List<TurnMatcher.Graph>): List<Continuation> {
        val incoming = roadTokens(previous.instruction)
        val outgoing = roadTokens(step.instruction)
        if (incoming.isEmpty() || outgoing.isEmpty()) return emptyList()
        val found = linkedMapOf<String, Continuation>()
        for (graph in graphs) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (graph.nodes.size > 30_000 || graph.ways.size > 6000) continue
            fun aliases(way: TurnMatcher.Way) = listOf("name", "name:en", "alt_name", "official_name", "ref")
                .flatMap { way.tags[it].orEmpty().split(';') }.flatMap(::nameAliases).toSet()
            fun usable(way: TurnMatcher.Way) = way.tags["highway"] in CAR_ROADS && way.tags["junction"] != "roundabout" &&
                listOf("access", "vehicle", "motor_vehicle", "motorcar").none { way.tags[it] in setOf("no", "private") }
            val before = graph.ways.filter { usable(it) && aliases(it).any(incoming::contains) }
            val after = graph.ways.filter { usable(it) && aliases(it).any(outgoing::contains) }
            val shared = before.flatMap { it.nodes }.toSet().intersect(after.flatMap { it.nodes }.toSet())
            if (shared.size > 2048) return emptyList()
            for (node in shared) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val point = graph.nodes[node] ?: continue
                val location = projectAlong(route, point, start + 5, end - 5) ?: continue
                if (!distanceAgrees(location - start, expected, label)) continue
                val inHeading = heading(pointAt(route, max(start, location - 25)), point)
                val outHeading = heading(point, pointAt(route, min(end, location + 25)))
                if (abs(((outHeading - inHeading + 540) % 360) - 180) > 45) continue
                fun connects(ways: List<TurnMatcher.Way>, incomingSide: Boolean, course: Double) = ways.any { way ->
                    val one = way.tags["oneway"].orEmpty().lowercase(Locale.ROOT)
                    way.nodes.indices.any { i -> way.nodes[i] == node && listOf(-1, 1).any side@ { delta ->
                        val neighbour = way.nodes.getOrNull(i + delta)?.let(graph.nodes::get) ?: return@side false
                        val forward = if (incomingSide) delta < 0 else delta > 0
                        val allowed = if (forward) one != "-1" else one !in setOf("yes", "true", "1")
                        val actual = if (incomingSide) heading(neighbour, point) else heading(point, neighbour)
                        allowed && abs(((actual - course + 540) % 360) - 180) <= 35
                    } }
                }
                if (connects(before, true, inHeading) && connects(after, false, outHeading)) {
                    found[node.toString()] = Continuation(point, location)
                    if (found.size > 1) return found.values.toList()
                }
            }
        }
        return found.values.toList()
    }

    /** Finds a unique nearby projection in the candidate span, never a coordinate from distance alone. */
    private fun projectAlong(route: RoutePlan, point: GeoPoint, start: Double, end: Double): Double? {
        var bestDistance = 12.0
        var bestAlong: Double? = null
        var ambiguous = false
        fun firstAtLeast(value: Double): Int {
            var low = 0; var high = route.cumulativeMeters.lastIndex
            while (low < high) { val middle = (low + high) ushr 1
                if (route.cumulativeMeters[middle] < value) low = middle + 1 else high = middle }
            return low
        }
        val first = (firstAtLeast(start) - 1).coerceAtLeast(0)
        val last = firstAtLeast(end).coerceAtMost(route.points.lastIndex)
        if (last - first > 20_000) return null
        for (index in first until last) {
            val a = route.points[index]; val b = route.points[index + 1]
            val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dx = (b.longitude - a.longitude) * scale; val dy = b.latitude - a.latitude
            val denominator = dx * dx + dy * dy
            val fraction = if (denominator == 0.0) 0.0 else (((point.longitude - a.longitude) * scale * dx +
                (point.latitude - a.latitude) * dy) / denominator).coerceIn(0.0, 1.0)
            val along = route.cumulativeMeters[index] + fraction * (route.cumulativeMeters[index + 1] - route.cumulativeMeters[index])
            if (along !in start..end) continue
            val projected = pointAt(route, along)
            val distance = projected.distanceTo(point)
            if (distance < bestDistance - .1) { bestDistance = distance; bestAlong = along; ambiguous = false }
            else if (distance <= bestDistance + .1 && bestAlong != null && abs(along - bestAlong) > 10) ambiguous = true
        }
        return bestAlong.takeUnless { ambiguous }
    }

    private fun harmless(route: RoutePlan, index: Int): Boolean {
        val maneuver = route.maneuvers[index]
        return candidateKind(maneuver) == Kind.START ||
            candidateKind(maneuver) == Kind.STRAIGHT && bend(route, maneuver.beginIndex) <= 25.0
    }

    private fun geometryAgrees(step: GoogleDirectionsStep, route: RoutePlan, index: Int): Boolean {
        val desired = kind(step.instruction)
        if (desired !in setOf(Kind.LEFT, Kind.RIGHT, Kind.UTURN, Kind.STRAIGHT)) return true
        val along = route.cumulativeMeters[index]
        if (along < 5 || route.lengthMeters() - along < 5) return desired == Kind.STRAIGHT
        val junction = route.points[index]
        val before = pointAt(route, max(0.0, along - 25))
        val after = pointAt(route, min(route.lengthMeters(), along + 25))
        val angle = ((heading(junction, after) - heading(before, junction) + 540) % 360) - 180
        return when (desired) {
            Kind.LEFT -> angle in -170.0..-15.0
            Kind.RIGHT -> angle in 15.0..170.0
            Kind.UTURN -> abs(angle) >= 120
            Kind.STRAIGHT -> abs(angle) <= 45
            else -> true
        }
    }

    private fun bend(route: RoutePlan, index: Int): Double {
        if (index <= 0 || index >= route.points.lastIndex) return 0.0
        val a = heading(route.points[index - 1], route.points[index])
        val b = heading(route.points[index], route.points[index + 1])
        return abs(((b - a + 540) % 360) - 180)
    }

    private fun distanceAgrees(actual: Double, expected: Double, label: String): Boolean {
        if (!actual.isFinite() || actual < 0 || !expected.isFinite() || expected < 0 || expected > 2_000_000) return false
        val number = Regex("(\\d+)(?:[.,](\\d+))?").find(label)
        val decimals = number?.groupValues?.get(2)?.length ?: 1
        val multiplier = if (Regex("\\bmi(?:le)?s?\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) 1609.344
            else if (Regex("\\bkm\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) 1000.0
            else if (Regex("\\b(?:ft|feet)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) .3048 else 1.0
        val rounding = if (label.isBlank()) 0.0 else .5 * 10.0.pow(-decimals) * multiplier
        return abs(actual - expected) <= max(40.0, min(1500.0, 40 + expected * .10 + rounding))
    }

    private fun geometry(route: RoutePlan, index: Int): TurnGeometry {
        return geometry(route, route.cumulativeMeters[index], route.points[index])
    }
    private fun geometry(route: RoutePlan, junction: Double, exactJunction: GeoPoint): TurnGeometry {
        val start = max(0.0, junction - 500.0)
        val end = min(route.lengthMeters(), junction + 100.0)
        val locations = ((0..253).map { start + (end - start) * it / 253 } + junction).distinct().sorted()
        val points = locations.map { if (it == junction) exactJunction else pointAt(route, it) }
        val limit = route.speedLimitKphAt(max(0.0, junction - 1.0)).takeIf { it.isFinite() && it > 0 && it < 255 }
        return TurnGeometry(points, exactJunction, limit, "validated-google-route", "")
    }

    private fun pointAt(route: RoutePlan, along: Double): GeoPoint {
        var low = 0; var high = route.cumulativeMeters.lastIndex
        while (low < high) { val middle = (low + high) ushr 1; if (route.cumulativeMeters[middle] < along) low = middle + 1 else high = middle }
        val next = low
        if (next == 0) return route.points.first()
        val a = route.points[next - 1]; val b = route.points[next]
        val length = route.cumulativeMeters[next] - route.cumulativeMeters[next - 1]
        val f = if (length > 0) ((along - route.cumulativeMeters[next - 1]) / length).coerceIn(0.0, 1.0) else 0.0
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
    }

    private fun planned(step: GoogleDirectionsStep, geometry: TurnGeometry, along: Double): PlannedTurn {
        val digest = MessageDigest.getInstance("SHA-256")
        fun number(value: Long) { digest.update(ByteBuffer.allocate(8).putLong(value).array()) }
        for (point in geometry.points) { number(round(point.latitude * 1_000_000).toLong()); number(round(point.longitude * 1_000_000).toLong()) }
        number(round(geometry.junction.latitude * 1_000_000).toLong()); number(round(geometry.junction.longitude * 1_000_000).toLong())
        number(java.lang.Double.doubleToLongBits(geometry.approachSpeedLimitKph ?: Double.NaN))
        digest.update(geometry.confidence.toByteArray(Charsets.UTF_8))
        val identity = digest.digest().take(12).joinToString("") { "%02x".format(it) }
        return PlannedTurn(step.index, step.key, step.instruction, geometry.copy(key = "google-trip:${step.key}:$identity"), along)
    }
    private fun plan(snapshot: GoogleDirectionsSnapshot, turns: List<PlannedTurn>, reason: StopReason,
                     details: List<DetailRequest> = emptyList()) = Plan(
        snapshot.fingerprint, turns.toList(), reason == StopReason.COMPLETE && snapshot.complete, reason,
        turns.lastOrNull()?.stepIndex ?: -1, details)
    private fun valid(snapshot: GoogleDirectionsSnapshot, index: Int) = snapshot.complete &&
        snapshot.fingerprint.length in 1..128 && snapshot.steps.size in 1..512 && index in snapshot.steps.indices &&
        snapshot.steps.withIndex().all { it.value.index == it.index && it.value.key.length in 1..200 }
    private fun heading(a: GeoPoint, b: GeoPoint) = (Math.toDegrees(atan2((b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)), b.latitude - a.latitude)) + 360) % 360
}
