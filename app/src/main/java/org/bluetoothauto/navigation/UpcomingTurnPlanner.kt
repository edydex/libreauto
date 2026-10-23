// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.os.SystemClock
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry

/** Off-screen preparation input; position remains the real current fix, never a future GPS fix. */
data class PreparedTurn(val position: Position, val guidance: Guidance, val geometry: TurnGeometry)

/** Bounded lookahead from an existing exact Open maps route. Does no routing or global mutation. */
object UpcomingTurnPlanner {
    private const val MAX_TURNS = 3
    private const val MAX_INSPECTED_MANEUVERS = 16

    /** Includes the current upcoming maneuver, followed by at most two later maneuvers. */
    fun plan(snapshot: OwnNavigation.Snapshot, maxTurns: Int = MAX_TURNS): List<PreparedTurn> =
        plan(snapshot, maxTurns, SystemClock.elapsedRealtime())

    internal fun plan(snapshot: OwnNavigation.Snapshot, maxTurns: Int, nowElapsed: Long): List<PreparedTurn> {
        val count = maxTurns.coerceIn(0, MAX_TURNS)
        if (count == 0) return emptyList()
        val route = snapshot.route ?: return emptyList()
        val progress = snapshot.progress ?: return emptyList()
        val position = snapshot.position ?: return emptyList()
        val announced = snapshot.guidance
        if (announced != null && (announced.demo || announced.source != "Own maps")) return emptyList()
        if (progress.maneuverIndex !in route.maneuvers.indices) return emptyList()
        // Apply exactly the production freshness, matching and reroute/arrival gates first.
        val currentGeometry = OwnTurnGeometry.from(snapshot, nowElapsed) ?: return emptyList()
        val prepared = ArrayList<PreparedTurn>(count)
        var index = progress.maneuverIndex
        var inspected = 0
        while (index < route.maneuvers.size && prepared.size < count && inspected++ < MAX_INSPECTED_MANEUVERS) {
            val maneuver = route.maneuvers[index]
            val junctionAlong = route.cumulativeMeters[maneuver.beginIndex]
            val distance = (junctionAlong - progress.alongMeters).coerceAtLeast(0.0)
            val geometry = if (index == progress.maneuverIndex) currentGeometry else {
                // This immutable derived selection does not advance a tracker or assert that the
                // phone reached a future position. Geometry still comes from the returned route.
                val selected = RouteTracker.Progress(progress.snapped, progress.alongMeters,
                    progress.remainingMeters, progress.crossTrackMeters, distance, index,
                    progress.offRoute, progress.arrived, progress.usableFix, progress.matched,
                    progress.remainingSeconds, progress.speedLimitKph)
                OwnTurnGeometry.from(snapshot.copy(progress = selected), nowElapsed)?.let { turn ->
                    val limit = route.speedLimitKphAt((junctionAlong - 1.0).coerceAtLeast(0.0))
                        .takeIf { it.isFinite() && it > 0 && it < 255 }
                    turn.copy(approachSpeedLimitKph = limit)
                }
            }
            if (geometry != null) {
                val guidance = Guidance(maneuver.instruction, distanceMeters = distance,
                    receivedAt = position.receivedAt, source = "Own maps")
                prepared.add(PreparedTurn(position, guidance, geometry))
            }
            ++index
        }
        return prepared.toList()
    }
}
