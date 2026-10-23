// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import kotlin.math.*

/**
 * A short-lived course hint from a turn already proved against connected roads. This is neither a
 * route prediction nor a location fix: the next cue still needs an independent graph match using
 * the actual GPS point. Call reset on session/source/reroute clear; do not write this hint to GPS.
 */
class NotificationRouteTracker {
    private data class Previous(val instruction: String, val at: Long, val departure: List<GeoPoint>)
    private data class Projection(val distance: Double, val along: Double, val heading: Double)
    private var previous: Previous? = null

    fun reset() { previous = null }

    /** Only the exact junction and directed departure of a successful local road match are retained. */
    fun remember(guidance: Guidance, geometry: TurnGeometry, now: Long) {
        reset()
        if (now < 0 || !google(guidance) || TurnMatcher.turn(guidance.instruction) == null ||
            geometry.confidence !in setOf("matched", "candidate-constrained") || geometry.key.isBlank() || geometry.key.length > 256 ||
            geometry.points.size !in 3..512 || !pointValid(geometry.junction) ||
            geometry.points.any { !pointValid(it) }) return
        val junctions = geometry.points.indices.filter { geometry.points[it].distanceTo(geometry.junction) <= .5 }
        if (junctions.size != 1) return
        val junction = junctions.single()
        if (junction <= 0 || junction >= geometry.points.lastIndex) return
        // Artwork can retain a longer real path. The course handoff still owns
        // only its original short departure corridor, never an extended lease.
        val departure = boundedDeparture(geometry.points.drop(junction)) ?: return
        val lengths = departure.zipWithNext { a, b -> a.distanceTo(b) }
        if (lengths.any { it < .1 || it > 200 } || lengths.sum() !in 20.0..150.0) return
        previous = Previous(TurnMatcher.normalize(guidance.instruction), now, departure)
    }

    private fun boundedDeparture(points: List<GeoPoint>): List<GeoPoint>? {
        val result = arrayListOf(points.first())
        var remaining = 150.0
        for ((a, b) in points.zipWithNext()) {
            val length = a.distanceTo(b)
            if (length < .1) return null
            if (length >= remaining) {
                val fraction = remaining / length
                result.add(GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction,
                    a.longitude + (b.longitude - a.longitude) * fraction))
                break
            }
            result.add(b); remaining -= length
        }
        return result
    }

    /**
     * Fills only an absent course, after a different, fresh Google turn cue and a fresh accurate fix
     * clearly beyond the old junction. No extrapolation beyond the proved departure is accepted.
     */
    fun fallbackCourse(position: Position, guidance: Guidance, now: Long): Double? {
        if (!google(guidance)) { reset(); return null }
        val old = previous ?: return null
        if (now - old.at !in 0..MAX_AGE_MILLIS) { reset(); return null }
        if (position.demo || !position.latitude.isFinite() || abs(position.latitude) > 85 ||
            !position.longitude.isFinite() || abs(position.longitude) > 180 ||
            !position.accuracy.isFinite() || position.accuracy !in 0f..25f ||
            position.receivedAt < 0 || now - position.receivedAt !in 0..15_000 ||
            guidance.receivedAt < 0 || now - guidance.receivedAt !in 0..30_000 ||
            position.speedMps?.let { !it.isFinite() || it !in 0f..80f } == true ||
            position.bearing != null || TurnMatcher.turn(guidance.instruction) == null ||
            TurnMatcher.normalize(guidance.instruction) == old.instruction) return null
        val actual = GeoPoint(position.latitude, position.longitude)
        val tolerance = max(6.0, min(20.0, position.accuracy * 1.5))
        val minimumProgress = max(12.0, position.accuracy * 2.0)
        val candidates = arrayListOf<Projection>()
        var travelled = 0.0
        old.departure.zipWithNext { a, b ->
            val length = a.distanceTo(b)
            projection(actual, a, b)?.let { (projected, fraction) ->
                val error = actual.distanceTo(projected)
                val progress = travelled + fraction * length
                if (error <= tolerance && progress >= minimumProgress)
                    candidates.add(Projection(error, progress, heading(a, b)))
            }
            travelled += length
        }
        val best = candidates.minByOrNull { it.distance } ?: return null
        // A looping/crossing departure cannot establish a unique direction from one stopped fix.
        if (candidates.any { abs(delta(best.heading, it.heading)) > 25 || abs(best.along - it.along) > 30 }) return null
        return best.heading
    }

    private fun google(guidance: Guidance) = !guidance.demo && guidance.source == "Google Maps" &&
        guidance.instruction.isNotBlank() && guidance.instruction.length <= 500 &&
        !Regex("recalculat|rerout|off[ -]route|waiting", RegexOption.IGNORE_CASE).containsMatchIn(guidance.instruction)
    private fun pointValid(point: GeoPoint) = point.latitude.isFinite() && abs(point.latitude) <= 85 &&
        point.longitude.isFinite() && abs(point.longitude) <= 180
    private fun projection(point: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<GeoPoint, Double>? {
        val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2))
        val dx = (b.longitude - a.longitude) * scale
        val dy = b.latitude - a.latitude
        val denominator = dx * dx + dy * dy
        if (denominator <= 0) return null
        val fraction = ((point.longitude - a.longitude) * scale * dx + (point.latitude - a.latitude) * dy) / denominator
        if (fraction !in 0.0..1.0) return null
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction,
            a.longitude + (b.longitude - a.longitude) * fraction) to fraction
    }
    private fun heading(a: GeoPoint, b: GeoPoint) =
        (Math.toDegrees(atan2((b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)),
            b.latitude - a.latitude)) + 360) % 360
    private fun delta(from: Double, to: Double) = ((to - from + 540) % 360) - 180

    companion object { const val MAX_AGE_MILLIS = 5 * 60_000L }
}
