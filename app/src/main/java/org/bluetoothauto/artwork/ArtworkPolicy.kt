// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.navigation.GeoPoint
import kotlin.math.*

/** Monotonic, Android-free bounds for initial geometry and failed-map retry work. */
internal object ArtworkPolicy {
    const val POSITION_FRESH_MILLIS = 30_000L
    const val SNAPSHOT_TIMEOUT_MILLIS = 12_000L
    const val FAILURE_BACKOFF_MILLIS = 30_000L
    const val CACHE_MAX_DISTANCE_METERS = 160.0

    fun validPosition(latitude: Double, longitude: Double, accuracy: Double, receivedAt: Long, now: Long): Boolean =
        latitude.isFinite() && abs(latitude) <= 85.0 && longitude.isFinite() && abs(longitude) <= 180.0 &&
            accuracy.isFinite() && accuracy in 0.0..100.0 && receivedAt >= 0 && now >= receivedAt &&
            now - receivedAt <= POSITION_FRESH_MILLIS

    fun meters(latitude: Double, longitude: Double, otherLatitude: Double, otherLongitude: Double): Double {
        val a = Math.toRadians(latitude); val b = Math.toRadians(otherLatitude)
        val d = Math.toRadians(otherLatitude - latitude); val e = Math.toRadians(otherLongitude - longitude)
        val haversine = (sin(d / 2).pow(2) + cos(a) * cos(b) * sin(e / 2).pow(2)).coerceIn(0.0, 1.0)
        return 6_371_008.8 * 2 * atan2(sqrt(haversine), sqrt(1 - haversine))
    }

    fun delay(now: Long, nextAllowedAt: Long): Long = if (now >= nextAllowedAt) 0 else nextAllowedAt - now

    /** Value comparison permits fresh immutable geometry wrappers without rebuilding identical art. */
    fun sameRoadLayer(first: List<GeoPoint>?, firstJunction: GeoPoint?, second: List<GeoPoint>, secondJunction: GeoPoint?): Boolean {
        if (first == null || first.size != second.size || !samePoint(firstJunction, secondJunction)) return false
        return first.indices.all { samePoint(first[it], second[it]) }
    }

    private fun samePoint(first: GeoPoint?, second: GeoPoint?): Boolean = if (first == null || second == null)
        first == null && second == null else first.latitude == second.latitude && first.longitude == second.longitude

}
