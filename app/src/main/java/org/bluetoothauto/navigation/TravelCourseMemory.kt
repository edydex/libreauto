// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Position
import kotlin.math.*

/** Remembers direction of actual travel, never the phone's physical compass orientation. */
class TravelCourseMemory {
    private data class Course(val bearing: Float, val fix: Position)
    private var course: Course? = null
    private var lastFix: Position? = null
    private var derivationAnchor: Position? = null

    fun reset() { course = null; lastFix = null; derivationAnchor = null }

    /**
     * All times are the fix's monotonic receivedAt; borrowed direction never refreshes its lease.
     * trustedStationaryCourse is only for an explicitly verified simulator/mock heading.
     * The caller still checks fix freshness, mock status and reported bearing accuracy.
     */
    fun update(position: Position, trustedStationaryCourse: Boolean = false): Position {
        val withoutBearing = position.copy(bearing = null)
        if (!valid(position)) return withoutBearing
        val previous = lastFix
        if (previous != null && (position.receivedAt < previous.receivedAt ||
                position.receivedAt == previous.receivedAt && position.accuracy >= previous.accuracy)) return withoutBearing
        if (previous != null) {
            val seconds = (position.receivedAt - previous.receivedAt) / 1000.0
            val displacement = distance(previous, position)
            val tolerance = previous.accuracy + position.accuracy
            val explicitlyStationary = position.speedMps?.let { it < MIN_MOVING_SPEED } == true
            if (displacement > MAX_MEMORY_DISTANCE &&
                (displacement > MAX_SPEED * seconds + tolerance || explicitlyStationary)) {
                // A relocated GPS/simulator must establish fresh motion at its new position.
                reset(); lastFix = position; derivationAnchor = position
                return withoutBearing
            }
        }
        lastFix = position
        // Two providers can refine one timestamp. This is a better location,
        // not a second moment of travel, so restart displacement measurement.
        if (previous?.receivedAt == position.receivedAt) derivationAnchor = position
        val supplied = position.bearing?.takeIf { it.isFinite() && it in 0f..<360f &&
            position.speedMps?.let { speed -> speed <= MAX_SPEED &&
                (speed >= MIN_MOVING_SPEED || trustedStationaryCourse) } == true }
        if (supplied != null) {
            course = Course(supplied, position); derivationAnchor = position
            return position.copy(bearing = supplied)
        }

        val stationary = position.speedMps?.let { it < MIN_MOVING_SPEED } == true
        val anchor = derivationAnchor
        if (stationary) {
            // Do not turn GPS drift at a red light into an invented direction of travel.
            derivationAnchor = position
        } else if (anchor == null || position.receivedAt - anchor.receivedAt > MAX_DERIVATION_AGE) {
            derivationAnchor = position
        } else {
            val seconds = (position.receivedAt - anchor.receivedAt) / 1000.0
            val displacement = distance(anchor, position)
            val threshold = max(MIN_DERIVATION_DISTANCE, anchor.accuracy.toDouble() + position.accuracy)
            val impliedSpeed = if (seconds > 0) displacement / seconds else Double.POSITIVE_INFINITY
            if (seconds >= .2 && displacement >= threshold && impliedSpeed in MIN_MOVING_SPEED..MAX_SPEED) {
                val derived = bearing(anchor, position)
                course = Course(derived, position); derivationAnchor = position
                return position.copy(bearing = derived)
            }
        }
        val remembered = course ?: return withoutBearing
        if (position.receivedAt - remembered.fix.receivedAt !in 0..MAX_MEMORY_AGE ||
            distance(remembered.fix, position) > MAX_MEMORY_DISTANCE) {
            course = null
            return withoutBearing
        }
        return withoutBearing.copy(bearing = remembered.bearing)
    }

    private fun valid(position: Position): Boolean = position.latitude.isFinite() && position.longitude.isFinite() &&
        position.latitude in -90.0..90.0 && position.longitude in -180.0..180.0 &&
        position.accuracy.isFinite() && position.accuracy in 0f..25f && position.receivedAt >= 0 &&
        (position.speedMps == null || position.speedMps.isFinite() && position.speedMps in 0f..MAX_SPEED.toFloat())

    private fun distance(a: Position, b: Position): Double {
        val first = Math.toRadians(a.latitude); val second = Math.toRadians(b.latitude)
        val dLat = second - first; val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(first) * cos(second) * sin(dLon / 2).pow(2)
        return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    private fun bearing(a: Position, b: Position): Float {
        val first = Math.toRadians(a.latitude); val second = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        return ((Math.toDegrees(atan2(sin(dLon) * cos(second),
            cos(first) * sin(second) - sin(first) * cos(second) * cos(dLon))) + 360) % 360).toFloat()
    }
    companion object {
        const val MAX_MEMORY_AGE = 5 * 60_000L
        const val MAX_MEMORY_DISTANCE = 60.0
        const val MAX_DERIVATION_AGE = 60_000L
        const val MIN_DERIVATION_DISTANCE = 8.0
        const val MIN_MOVING_SPEED = 1.3
        const val MAX_SPEED = 80.0
    }
}
