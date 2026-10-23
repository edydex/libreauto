// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

/** Camera course only: no route, position, compass or timer state. */
public final class MovingCourseHeading {
    // Matches NavigationPanel's existing movement threshold for its search controls.
    public static final double MOVING_METERS_PER_SECOND = 1.4;
    private double bearing;

    /** Starts north-up and retains the last valid moving course at rest. */
    public double update(boolean hasBearing, double observedBearing,
                         boolean hasSpeed, double metersPerSecond) {
        if (hasBearing && hasSpeed && Double.isFinite(observedBearing)
                && observedBearing >= 0 && observedBearing < 360
                && Double.isFinite(metersPerSecond)
                && metersPerSecond > MOVING_METERS_PER_SECOND) {
            bearing = observedBearing;
        }
        return bearing;
    }

    public double bearing() { return bearing; }
}
