// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

/** Travel course for the map puck. No compass, sensors, timer, route or renderer dependency. */
public final class TravelHeading {
    public static final double MOVING_METERS_PER_SECOND = 1.4;
    private static final double MAX_POSITION_ACCURACY_METERS = 25;
    private static final long MIN_DISPLACEMENT_MILLIS = 800;
    private static final long MAX_DISPLACEMENT_MILLIS = 3000;
    private static final long RESET_SMOOTHING_MILLIS = 4000;
    private static final double SMOOTHING_MILLIS = 600;
    private GeoPoint anchor;
    private long anchorMillis, lastMillis = -1, lastHeadingMillis = -1;
    private double anchorAccuracy, bearing;
    private boolean known;

    public boolean hasHeading() { return known; }
    /** North is zero, east is 90. Check hasHeading before drawing a directional arrow. */
    public double bearingDegrees() { return bearing; }

    /** New projection/source session; a hidden tab alone does not need a reset. */
    public void reset() {
        anchor = null;
        anchorMillis = 0;
        lastMillis = lastHeadingMillis = -1;
        anchorAccuracy = bearing = 0;
        known = false;
    }

    /** Observe one fresh monotonic fix; callers own wall-clock freshness checks.
     * Pass NaN for a missing bearing, speed or speed accuracy. Returns true if the
     * drawable course changed, including its first acquisition. At rest, retain it.
     * Bearing-less course needs clear displacement over roughly one to three seconds;
     * uncertain motion intentionally takes longer or leaves the course unchanged. */
    public boolean update(GeoPoint point, long elapsedMillis, double accuracyMeters,
                          double observedBearingDegrees, double speedMetersPerSecond,
                          double speedAccuracyMetersPerSecond) {
        if (elapsedMillis < 0 || elapsedMillis <= lastMillis) return false;
        lastMillis = elapsedMillis;
        if (point == null || !Double.isFinite(accuracyMeters) || accuracyMeters < 0
                || accuracyMeters > MAX_POSITION_ACCURACY_METERS) {
            anchor = null;
            return false;
        }
        boolean speedValid = Double.isFinite(speedMetersPerSecond)
                && speedMetersPerSecond >= 0 && speedMetersPerSecond <= 100;
        if (speedValid && speedMetersPerSecond <= MOVING_METERS_PER_SECOND) {
            remember(point, elapsedMillis, accuracyMeters);
            return false;
        }
        boolean speedAccuracyValid = Double.isNaN(speedAccuracyMetersPerSecond)
                || Double.isFinite(speedAccuracyMetersPerSecond) && speedAccuracyMetersPerSecond >= 0
                && speedMetersPerSecond - speedAccuracyMetersPerSecond > MOVING_METERS_PER_SECOND;
        if (speedValid && speedAccuracyValid && Double.isFinite(observedBearingDegrees)
                && observedBearingDegrees >= 0 && observedBearingDegrees < 360) {
            remember(point, elapsedMillis, accuracyMeters);
            return accept(observedBearingDegrees, elapsedMillis);
        }
        if (anchor == null) {
            remember(point, elapsedMillis, accuracyMeters);
            return false;
        }
        long age = elapsedMillis - anchorMillis;
        if (age > MAX_DISPLACEMENT_MILLIS) {
            remember(point, elapsedMillis, accuracyMeters);
            return false;
        }
        if (age < MIN_DISPLACEMENT_MILLIS) return false;
        double distance = anchor.distanceTo(point);
        double speed = distance * 1000 / age;
        if (speed > 80) {
            // Rebase after a position jump rather than turn the arrow across it.
            remember(point, elapsedMillis, accuracyMeters);
            return false;
        }
        double requiredDistance = Math.max(4, 1.5 * Math.hypot(anchorAccuracy, accuracyMeters));
        if (distance < requiredDistance || speed <= MOVING_METERS_PER_SECOND) return false;
        double latitude1 = Math.toRadians(anchor.latitude), latitude2 = Math.toRadians(point.latitude);
        double longitudeDelta = Math.toRadians(point.longitude - anchor.longitude);
        double course = Math.toDegrees(Math.atan2(Math.sin(longitudeDelta) * Math.cos(latitude2),
                Math.cos(latitude1) * Math.sin(latitude2)
                        - Math.sin(latitude1) * Math.cos(latitude2) * Math.cos(longitudeDelta)));
        remember(point, elapsedMillis, accuracyMeters);
        return accept(normalize(course), elapsedMillis);
    }

    private void remember(GeoPoint point, long elapsedMillis, double accuracy) {
        anchor = point;
        anchorMillis = elapsedMillis;
        anchorAccuracy = accuracy;
    }

    private boolean accept(double course, long elapsedMillis) {
        if (!known || elapsedMillis - lastHeadingMillis > RESET_SMOOTHING_MILLIS) {
            boolean changed = !known || Math.abs(shortestDelta(bearing, course)) > 0.05;
            known = true;
            bearing = course;
            lastHeadingMillis = elapsedMillis;
            return changed;
        }
        double alpha = 1 - Math.exp(-(elapsedMillis - lastHeadingMillis) / SMOOTHING_MILLIS);
        double change = shortestDelta(bearing, course) * alpha;
        lastHeadingMillis = elapsedMillis;
        if (Math.abs(change) <= 0.05) return false;
        bearing = normalize(bearing + change);
        return true;
    }

    private static double shortestDelta(double from, double to) {
        return (to - from + 540) % 360 - 180;
    }
    private static double normalize(double course) { return (course % 360 + 360) % 360; }
}
