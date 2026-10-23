// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class MovingCourseHeadingTest {
    @Test public void initialViewIsNorthUpWithoutAnyFix() {
        assertEquals(0, new MovingCourseHeading().bearing(), 0);
    }

    @Test public void stationaryZeroThenOppositeThenMissingCannotRotateInitialView() {
        MovingCourseHeading view = new MovingCourseHeading();
        assertEquals(0, view.update(true, 0, true, 0), 0);
        assertEquals(0, view.update(true, 180, true, 0.2), 0);
        assertEquals(0, view.update(false, 0, false, 0), 0);
        assertEquals(0, view.update(true, 180, false, 4), 0);
    }

    @Test public void movingWestThenStationaryNorthEastAndMissingRetainsWest() {
        MovingCourseHeading view = new MovingCourseHeading();
        assertEquals(270, view.update(true, 270, true, 4), 0);
        assertEquals(270, view.update(true, 30, true, 0), 0);
        assertEquals(270, view.update(false, 0, true, 0), 0);
        assertEquals(270, view.update(false, 0, true, 5), 0);
    }

    @Test public void courseMayChangeAgainWhenMeasuredMovementResumes() {
        MovingCourseHeading view = new MovingCourseHeading();
        view.update(true, 270, true, 4);
        view.update(true, 30, true, 0);
        assertEquals(0, view.update(true, 0, true, 4), 0);
        assertEquals(359.9, view.update(true, 359.9, true, 4), 0);
    }

    @Test public void missingNonfiniteNegativeOrSlowSpeedCannotAuthorizeACourse() {
        MovingCourseHeading view = new MovingCourseHeading();
        view.update(true, 270, true, 4);
        for (double speed : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -1, 0, 1, 1.4}) {
            assertEquals(270, view.update(true, 30, true, speed), 0);
        }
        assertEquals(270, view.update(true, 30, false, 4), 0);
    }

    @Test public void invalidOrMissingBearingCannotReplaceValidCourse() {
        MovingCourseHeading view = new MovingCourseHeading();
        view.update(true, 270, true, 4);
        for (double course : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -0.01, 360, 540}) {
            assertEquals(270, view.update(true, course, true, 4), 0);
        }
        assertEquals(270, view.update(false, 30, true, 4), 0);
    }

    @Test public void thresholdIsStrictAndSmallestHigherSpeedCanUpdate() {
        MovingCourseHeading view = new MovingCourseHeading();
        assertEquals(0, view.update(true, 90, true, 1.4), 0);
        assertEquals(90, view.update(true, 90, true, Math.nextUp(1.4)), 0);
    }
}
