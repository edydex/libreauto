// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

/** Conservative visual matching, not a router. All geometry comes from connected OSM nodes. */
object TurnMatcher {
    data class Way(val id: Long, val nodes: List<Long>, val tags: Map<String, String>)
    data class Graph(val nodes: Map<Long, GeoPoint>, val ways: List<Way>)
    data class Request(val point: GeoPoint, val bearing: Double, val accuracy: Double,
                       val instruction: String, val distanceMeters: Double)
    data class Match(val points: List<GeoPoint>, val junction: GeoPoint,
                     val approachSpeedLimitKph: Double?, val key: String)
    data class Turn(val right: Boolean, val road: String, val toward: Boolean = false)
    private data class Edge(val way: Way, val from: Long, val to: Long, val forward: Boolean,
                            val length: Double, val heading: Double)
    private data class Start(val edge: Edge, val point: GeoPoint, val fraction: Double, val distance: Double)
    private val roads = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
        "residential", "living_street", "service", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link")

    fun turn(instruction: String): Turn? {
        val hint = NotificationTurnHint.parse(instruction) ?: return null
        val right = when (hint.kind) {
            NotificationTurnHint.Kind.RIGHT, NotificationTurnHint.Kind.SLIGHT_RIGHT -> true
            NotificationTurnHint.Kind.LEFT, NotificationTurnHint.Kind.SLIGHT_LEFT -> false
            else -> return null
        }
        val road = hint.road
        if (road.length !in 2..180) return null
        val aliases = roadAlternatives(road)
        if (aliases.isEmpty()) return null
        return Turn(right, aliases.joinToString(" / ") { normalize(it) }.takeIf { it.isNotBlank() } ?: return null, hint.toward)
    }

    fun normalize(name: String): String {
        val plain = Normalizer.normalize(name.take(240), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("([a-z])([0-9])"), "$1 $2")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val tokens = mapOf("street" to "st", "avenue" to "ave", "road" to "rd", "boulevard" to "blvd",
            "drive" to "dr", "lane" to "ln", "court" to "ct", "place" to "pl", "parkway" to "pkwy",
            "highway" to "hwy", "north" to "n", "south" to "s", "east" to "e", "west" to "w",
            "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw", "interstate" to "i")
        return plain.split(Regex("\\s+")).joinToString(" ") { tokens[it] ?: it }
    }

    /** Exact street aliases and explicit road references, without fuzzy name matching. */
    fun roadMatches(road: String, offered: Collection<String>): Boolean {
        val desired = roadAlternatives(road).map { normalize(it).removeSuffix(" ramp").trim() }
        if (desired.isEmpty()) return false
        val directions = setOf("n", "s", "e", "w", "ne", "nw", "se", "sw")
        fun direction(value: String) = value.split(' ').let { tokens ->
            tokens.firstOrNull()?.takeIf { it in directions } ?: tokens.lastOrNull()?.takeIf { it in directions }
        }
        fun variants(value: String): Set<String> {
            val normal = normalize(value).removeSuffix(" ramp").trim()
            // Google can omit the ordinal suffix of a numbered street while
            // OSM retains it ("SE 256 St" / "Southeast 256th Street"). Only
            // numeric tokens immediately before a road type get this alias;
            // references, road types and directional words stay authoritative.
            val numberedStreet = normal.replace(
                Regex("\\b([0-9]+)(?:st|nd|rd|th) (?=(?:st|ave|rd|blvd|dr|ln|ct|pl|pkwy|way)\\b)"), "$1 ")
            val route = Regex("^(?:washington state (?:hwy|highway|rte|rt)|wash state (?:hwy|highway|rte|rt)|state (?:route|hwy|highway|rte|rt)|wa|sr) (\\d+[a-z]?)(?: [nsew])?$", RegexOption.IGNORE_CASE)
                .matchEntire(normal)?.groupValues?.get(1)?.let { "state route $it" }
            val trunk = Regex("^(i|us|u s) (\\d+[a-z]?)(?: [nsew])?$").matchEntire(normal)
                ?.let { "${if (it.groupValues[1] == "u s") "us" else it.groupValues[1]} ${it.groupValues[2]}" }
            return setOfNotNull(normal, numberedStreet, route, trunk)
        }
        val actual = offered.flatMap(::roadAlternatives).map { normalize(it).removeSuffix(" ramp").trim() }
        // A matching street alias must not hide an explicitly opposite highway direction.
        if (desired.any { wanted -> actual.any { value ->
            val a = direction(wanted); val b = direction(value)
            a != null && b != null && a != b && variants(wanted).intersect(variants(value)).isNotEmpty()
        } }) return false
        return desired.any { wanted -> actual.any { value -> variants(wanted).intersect(variants(value)).isNotEmpty() } }
    }

    /** Google sometimes gives one highway reference and its street alias, never two arbitrary roads. */
    fun roadAlternatives(road: String): List<String> {
        if ('/' !in road) return listOf(road)
        if (road.length > 200) return emptyList()
        val pieces = road.split('/').map { it.trim() }
        if (pieces.size != 2 || pieces.any { it.length !in 2..180 }) return emptyList()
        val reference = Regex("^(?:i|us|u s|wa|sr|(?:washington |wash )?state (?:route|hwy|rte|rt)) \\d+[a-z]?(?: [nsew])?$")
        val street = Regex("\\b(?:st|ave|rd|blvd|dr|ln|ct|pl|pkwy|way)(?: (?:n|s|e|w|ne|nw|se|sw))?$")
        val normalized = pieces.map(::normalize)
        val refIndex = normalized.indices.filter { reference.matches(normalized[it]) }.singleOrNull() ?: return emptyList()
        if (!street.containsMatchIn(normalized[1 - refIndex])) return emptyList()
        return listOf(pieces[refIndex], pieces[1 - refIndex])
    }

    /** Only a numeric posted value or explicit 'none'; never infer limits from road category. */
    fun speedLimit(tags: Map<String, String>, forward: Boolean): Double? {
        if (tags.keys.any { it.startsWith("maxspeed") && (it.contains("conditional") || it.contains("variable") || it.contains("lanes")) }) return null
        val raw = tags[if (forward) "maxspeed:forward" else "maxspeed:backward"] ?: tags["maxspeed"] ?: return null
        val value = raw.trim().lowercase(Locale.ROOT)
        if (value == "none") return Double.POSITIVE_INFINITY
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(mph|km/h|kmh|kph)?$").matchEntire(value) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val kph = number * if (match.groupValues[2] == "mph") 1.609344 else 1.0
        return kph.takeIf { it.isFinite() && it > 0 && it < 250 }
    }

    fun match(graph: Graph, request: Request): Match? {
        val turn = turn(request.instruction) ?: return null
        if (!request.bearing.isFinite() || request.bearing !in 0.0..<360.0 || !request.accuracy.isFinite() ||
            request.accuracy !in 0.0..25.0 || !request.distanceMeters.isFinite() || request.distanceMeters !in 5.0..1200.0 ||
            graph.nodes.size > 30_000 || graph.ways.size > 6000) return null
        val outgoing = HashMap<Long, MutableList<Edge>>()
        val edges = ArrayList<Edge>()
        val nameCache = graph.ways.associate { it.id to aliases(it) }
        fun names(way: Way) = nameCache[way.id].orEmpty()
        val targetCache = HashMap<Long, Boolean>()
        fun target(way: Way) = targetCache.getOrPut(way.id) { roadMatches(turn.road, names(way)) }
        for (way in graph.ways) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (way.nodes.any { it !in graph.nodes }) return null
            if (!drivable(way) || way.nodes.size !in 2..10_000) continue
            val oneway = way.tags["oneway"].orEmpty().lowercase(Locale.ROOT)
            for (i in 0 until way.nodes.size - 1) {
                val a = way.nodes[i]; val b = way.nodes[i + 1]
                if (a == b) continue
                val pa = graph.nodes.getValue(a); val pb = graph.nodes.getValue(b); val length = pa.distanceTo(pb)
                if (length < .1 || length > 5000) continue
                fun add(from: Long, to: Long, forward: Boolean) {
                    val edge = Edge(way, from, to, forward, length, heading(graph.nodes.getValue(from), graph.nodes.getValue(to)))
                    edges.add(edge); outgoing.getOrPut(from) { arrayListOf() }.add(edge)
                }
                if (oneway != "-1") add(a, b, true)
                if (oneway !in setOf("yes", "true", "1")) add(b, a, false)
            }
        }
        if (edges.size > 60_000) return null
        // The reported accuracy is not a hard geometric road snap: a modest floor
        // tolerates map-centerline/GPS offsets, while the whole turn still has to
        // prove the approach. Never admit a road more than 25m from the real fix.
        val threshold = max(15.0, min(25.0, request.accuracy * 2.0))
        val starts = edges.asSequence().filter { (turn.toward || names(it.way).isNotEmpty()) && abs(delta(request.bearing, it.heading)) <= 40 }
            .map { edge -> project(request.point, graph.nodes.getValue(edge.from), graph.nodes.getValue(edge.to)).let {
                Start(edge, it.first, it.second, request.point.distanceTo(it.first))
            } }.filter { it.distance <= threshold }.sortedBy { it.distance }.take(17).toList()
        if (starts.isEmpty() || starts.size > 16) return null
        val tolerance = max(50.0, request.distanceMeters * .25)
        val maximum = request.distanceMeters + tolerance
        data class Proven(val match: Match, val start: Start, val corridor: List<Long>)
        val found = linkedMapOf<String, Proven>()
        data class Walk(val start: Start, val current: Edge, val distance: Double,
                        val prefix: List<GeoPoint>, val corridor: List<Long>, val visited: Set<Long>)
        val pending = java.util.ArrayDeque<Walk>()
        for (start in starts) if (!target(start.edge.way)) pending.add(Walk(start, start.edge,
            (1 - start.fraction) * start.edge.length, listOf(start.point, graph.nodes.getValue(start.edge.to)),
            listOf(start.edge.from, start.edge.to), setOf(start.edge.from)))
        var totalSteps = 0
        var scheduled = pending.size
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val state = pending.removeFirst()
            if (state.distance > maximum) continue
            if (++totalSteps > 4096 || state.prefix.size > 256) return null
            val start = state.start; val current = state.current
            if (current.to in state.visited) continue
            val choices = outgoing[current.to].orEmpty().filter { it.to != current.from }
            if (abs(state.distance - request.distanceMeters) <= tolerance) {
                val departures = choices.filter { edge ->
                    (target(edge.way) || turn.toward && names(edge.way).isEmpty()) && abs(delta(current.heading, edge.heading)) in 25.0..155.0 &&
                        (delta(current.heading, edge.heading) > 0) == turn.right
                }
                if (departures.size > 1 && !turn.toward) return null
                departures.forEach { depart ->
                    val tail = if (turn.toward) towardDeparture(graph, outgoing, depart, turn.road)
                        else departure(graph, outgoing, depart, turn.road)
                    if (tail == null) return@forEach
                    val key = "${current.to}:${depart.way.id}:${depart.to}${if (turn.toward) ":toward" else ""}"
                    val proof = Proven(Match((state.prefix + tail.drop(1)).toList(), graph.nodes.getValue(current.to),
                        speedLimit(start.edge.way.tags, start.edge.forward), key), start, state.corridor)
                    val old = found[key]
                    if (old != null) {
                        val short = if (old.corridor.size <= proof.corridor.size) old.corridor else proof.corridor
                        val long = if (old.corridor.size > proof.corridor.size) old.corridor else proof.corridor
                        if (long.takeLast(short.size) != short ||
                            names(old.start.edge.way).none { it in names(start.edge.way) } &&
                            !(turn.toward && names(old.start.edge.way).isEmpty() && names(start.edge.way).isEmpty())) return null
                        if (start.distance < old.start.distance) found[key] = proof
                    } else found[key] = proof
                    if (found.size > 1) return null
                }
            }
            val notTarget = choices.filter { edge -> !target(edge.way) }
            val nextEdges = notTarget.filter { edge ->
                val angle = abs(delta(current.heading, edge.heading))
                angle <= 60 && (names(current.way).any { it in names(edge.way) } || angle <= 30 || notTarget.size == 1)
            }
            // Explore every admissible forward corridor. Google's named road, turn
            // side and distance must eliminate every competing connected path.
            for (next in nextEdges) {
                if (next.to in state.visited || next.to == current.to) continue
                if (++scheduled > 4096 || pending.size >= 128) return null
                pending.add(Walk(start, next, state.distance + next.length,
                    state.prefix + graph.nodes.getValue(next.to), state.corridor + next.to,
                    state.visited + current.to))
            }
        }
        return found.values.singleOrNull()?.match
    }

    private fun departure(graph: Graph, outgoing: Map<Long, List<Edge>>, first: Edge, road: String): List<GeoPoint>? {
        val points = arrayListOf(graph.nodes.getValue(first.from))
        val visited = hashSetOf(first.from)
        var edge = first; var remaining = 100.0; var travelled = 0.0
        repeat(64) {
            val a = graph.nodes.getValue(edge.from); val b = graph.nodes.getValue(edge.to)
            if (edge.length >= remaining) {
                points.add(interpolate(a, b, remaining / edge.length)); return points
            }
            points.add(b); remaining -= edge.length; travelled += edge.length
            if (!visited.add(edge.to)) return null
            val next = outgoing[edge.to].orEmpty().filter { it.to != edge.from && roadMatches(road, aliases(it.way)) && abs(delta(edge.heading, it.heading)) <= 60 }
            if (next.size != 1) return if (travelled >= 25) points else null
            edge = next.single()
        }
        return null
    }

    /** Prove a short unnamed connector reaches the exact street, without re-labelling it as that street. */
    private fun towardDeparture(graph: Graph, outgoing: Map<Long, List<Edge>>, first: Edge, road: String): List<GeoPoint>? {
        if (roadMatches(road, aliases(first.way))) return departure(graph, outgoing, first, road)
        if (aliases(first.way).isNotEmpty()) return null
        data class Connector(val edge: Edge, val distance: Double, val points: List<GeoPoint>, val visited: Set<Long>)
        val pending = java.util.ArrayDeque<Connector>()
        pending.add(Connector(first, first.length,
            listOf(graph.nodes.getValue(first.from), graph.nodes.getValue(first.to)), setOf(first.from)))
        var steps = 0
        var result: List<GeoPoint>? = null
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val current = pending.removeFirst()
            if (++steps > 512 || current.points.size > 64) return null
            if (current.distance > 300 || current.edge.to in current.visited) continue
            val choices = outgoing[current.edge.to].orEmpty().filter {
                it.to != current.edge.from && it.to !in current.visited &&
                    abs(delta(current.edge.heading, it.heading)) <= 60
            }
            for (next in choices) {
                if (roadMatches(road, aliases(next.way))) {
                    val end = departure(graph, outgoing, next, road) ?: continue
                    val proof = current.points + end.drop(1)
                    if (result != null) return null
                    result = cropDeparture(proof, 100.0)
                } else if (aliases(next.way).isEmpty()) {
                    if (pending.size >= 128) return null
                    pending.add(Connector(next, current.distance + next.length,
                        current.points + graph.nodes.getValue(next.to), current.visited + current.edge.to))
                }
            }
        }
        return result
    }

    private fun cropDeparture(points: List<GeoPoint>, maximum: Double): List<GeoPoint> {
        val result = arrayListOf(points.first())
        var remaining = maximum
        for ((a, b) in points.zipWithNext()) {
            val length = a.distanceTo(b)
            if (length >= remaining) { result.add(interpolate(a, b, remaining / length)); break }
            result.add(b); remaining -= length
        }
        return result
    }
    private fun drivable(way: Way): Boolean = way.tags["highway"] in roads && way.tags["junction"] != "roundabout" &&
        listOf("access", "vehicle", "motor_vehicle", "motorcar").none { way.tags[it] in setOf("no", "private") }
    private fun aliases(way: Way): Set<String> = listOf("name", "name:en", "alt_name", "official_name", "short_name", "ref")
        .flatMap { way.tags[it].orEmpty().split(';') }.filter { it.isNotBlank() }.map(::normalize).toSet()
    private fun project(point: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<GeoPoint, Double> {
        val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2))
        val dx = (b.longitude - a.longitude) * scale; val dy = b.latitude - a.latitude
        val denominator = dx * dx + dy * dy
        val fraction = if (denominator <= 0) 0.0 else (((point.longitude - a.longitude) * scale * dx + (point.latitude - a.latitude) * dy) / denominator).coerceIn(0.0, 1.0)
        return interpolate(a, b, fraction) to fraction
    }
    private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double) = GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction, a.longitude + (b.longitude - a.longitude) * fraction)
    private fun heading(a: GeoPoint, b: GeoPoint): Double = (Math.toDegrees(atan2((b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)), b.latitude - a.latitude)) + 360) % 360
    private fun delta(from: Double, to: Double) = ((to - from + 540) % 360) - 180
}
