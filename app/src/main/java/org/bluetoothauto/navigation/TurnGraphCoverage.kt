// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

/** Coverage checks for reusing the proved interior of a larger prepared road area. */
internal object TurnGraphCoverage {
    fun full(center: GeoPoint, radius: Double, origin: GeoPoint, requiredRadius: Double): Boolean =
        radius.isFinite() && requiredRadius.isFinite() && requiredRadius > 0 &&
            center.distanceTo(origin) + requiredRadius <= radius

    fun reusableInterior(center: GeoPoint, radius: Double, origin: GeoPoint): Boolean =
        full(center, radius, origin, 50.0)

    /** A partial area never authorizes artwork extending beyond its known interior. */
    fun containsMatch(center: GeoPoint, radius: Double, match: TurnMatcher.Match): Boolean =
        radius.isFinite() && radius > 10 && match.points.size >= 2 &&
            center.distanceTo(match.junction) <= radius - 10 &&
            match.points.all { center.distanceTo(it) <= radius - 10 }
}
