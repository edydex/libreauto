// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TravelHeadingTest {
    private static final GeoPoint START = new GeoPoint(47.401, -122.3014);
    private static final double MISSING = Double.NaN;

    @Test public void initialDirectionIsUnknownAndStationaryZeroDoesNotInventNorth() {
        TravelHeading heading = new TravelHeading();
        assertFalse(heading.hasHeading());
        assertFalse(heading.update(START, 1000, 3, 0, 0, 0.1));
        assertFalse(heading.hasHeading());
        assertEquals(0, heading.bearingDegrees(), 0);
    }

    @Test public void reliableMovingCourseIsImmediateAndZeroIsAValidNorthBearing() {
        TravelHeading heading = new TravelHeading();
        assertTrue(heading.update(START, 1000, 3, 0, 10, 0.5));
        assertTrue(heading.hasHeading());
        assertEquals(0, heading.bearingDegrees(), 0);
    }

    @Test public void stationaryJitterAndSpuriousBearingRetainPreviousDirection() {
        TravelHeading heading = moving(270);
        for (int i = 1; i <= 8; i++)
            assertFalse(heading.update(offset(i * 0.5, i * 0.3), 1000 + i * 1000, 3, 0, 0.2, 0.5));
        assertEquals(270, heading.bearingDegrees(), 0);
    }

    @Test public void bearingIsPreferredOverOppositeDisplacement() {
        TravelHeading heading = new TravelHeading();
        heading.update(START, 1000, 2, MISSING, MISSING, MISSING);
        assertTrue(heading.update(offset(-20, 0), 2000, 2, 0, 15, 0.2));
        assertEquals(0, heading.bearingDegrees(), 0);
    }

    @Test public void missingBearingFallsBackToReliableOneSecondDisplacement() {
        TravelHeading heading = new TravelHeading();
        assertFalse(heading.update(START, 1000, 2, MISSING, MISSING, MISSING));
        assertTrue(heading.update(offset(0, 15), 2000, 2, MISSING, MISSING, MISSING));
        assertEquals(90, heading.bearingDegrees(), 0.01);
    }

    @Test public void uncertainOneSecondDisplacementAccumulatesToTwoSeconds() {
        TravelHeading heading = new TravelHeading();
        heading.update(START, 1000, 5, MISSING, MISSING, MISSING);
        assertFalse(heading.update(offset(0, 8), 2000, 5, MISSING, MISSING, MISSING));
        assertTrue(heading.update(offset(0, 16), 3000, 5, MISSING, MISSING, MISSING));
        assertEquals(90, heading.bearingDegrees(), 0.01);
    }

    @Test public void subsecondFixesDoNotOverreactToDisplacementNoise() {
        TravelHeading heading = new TravelHeading();
        heading.update(START, 1000, 1, MISSING, MISSING, MISSING);
        assertFalse(heading.update(offset(0, 7), 1200, 1, MISSING, MISSING, MISSING));
        assertTrue(heading.update(offset(0, 12), 2000, 1, MISSING, MISSING, MISSING));
        assertEquals(90, heading.bearingDegrees(), 0.01);
    }

    @Test public void unknownSpeedJitterDoesNotAcquireHeading() {
        TravelHeading heading = new TravelHeading();
        for (int i = 0; i < 10; i++)
            assertFalse(heading.update(offset(i % 2 == 0 ? 1 : -1, 0), i * 1000, 5, MISSING, MISSING, MISSING));
        assertFalse(heading.hasHeading());
    }

    @Test public void uncertainSpeedCannotAuthorizeGpsBearingButDisplacementCan() {
        TravelHeading heading = new TravelHeading();
        assertFalse(heading.update(START, 1000, 2, 270, 2, 1));
        assertTrue(heading.update(offset(0, 12), 2000, 2, 270, 2, 1));
        assertEquals(90, heading.bearingDegrees(), 0.01);
    }

    @Test public void invalidSpeedAccuracyCannotAuthorizeBearing() {
        for (double accuracy : new double[]{-1, Double.POSITIVE_INFINITY}) {
            TravelHeading heading = new TravelHeading();
            assertFalse(heading.update(START, 1000, 2, 90, 10, accuracy));
            assertFalse(heading.hasHeading());
        }
    }

    @Test public void absentSpeedAccuracyStillAllowsReliableMovingBearing() {
        TravelHeading heading = new TravelHeading();
        assertTrue(heading.update(START, 1000, 2, 180, 10, MISSING));
        assertEquals(180, heading.bearingDegrees(), 0);
    }

    @Test public void invalidBearingsCannotReplaceKnownDirectionWithoutMotionEvidence() {
        for (double bearing : new double[]{MISSING, -1, 360, Double.POSITIVE_INFINITY}) {
            TravelHeading heading = moving(270);
            assertFalse(heading.update(START, 2000, 2, bearing, 10, 0.1));
            assertEquals(270, heading.bearingDegrees(), 0);
        }
    }

    @Test public void poorFixClearsDisplacementAnchorAndCannotRotateArrow() {
        TravelHeading heading = moving(270);
        assertFalse(heading.update(offset(0, 20), 2000, 40, 90, 10, 0.1));
        assertFalse(heading.update(offset(0, 30), 3000, 2, MISSING, MISSING, MISSING));
        assertEquals(270, heading.bearingDegrees(), 0);
        assertTrue(heading.update(offset(0, 45), 4000, 2, MISSING, MISSING, MISSING));
    }

    @Test public void duplicateOrOlderTimestampsCannotAffectCourseOrAnchor() {
        TravelHeading heading = moving(270);
        assertFalse(heading.update(offset(0, 20), 1000, 2, 90, 10, 0.1));
        assertFalse(heading.update(offset(0, 20), 900, 2, 90, 10, 0.1));
        assertEquals(270, heading.bearingDegrees(), 0);
    }

    @Test public void headingSmoothingCrossesNorthByTheShortestAngleInBothDirections() {
        TravelHeading clockwise = moving(350);
        assertTrue(clockwise.update(START, 2000, 2, 10, 10, 0.1));
        assertTrue(clockwise.bearingDegrees() > 0 && clockwise.bearingDegrees() < 10);
        TravelHeading counterclockwise = moving(10);
        assertTrue(counterclockwise.update(START, 2000, 2, 350, 10, 0.1));
        assertTrue(counterclockwise.bearingDegrees() > 350 && counterclockwise.bearingDegrees() < 360);
    }

    @Test public void longGapRebasesDisplacementAndDoesNotBlendAnAncientDirection() {
        TravelHeading heading = moving(270);
        assertFalse(heading.update(offset(0, 50), 7000, 2, MISSING, MISSING, MISSING));
        assertEquals(270, heading.bearingDegrees(), 0);
        assertTrue(heading.update(offset(0, 65), 8000, 2, MISSING, MISSING, MISSING));
        assertEquals(90, heading.bearingDegrees(), 0.01);
    }

    @Test public void implausiblePositionJumpRebasesWithoutInventingDirection() {
        TravelHeading heading = new TravelHeading();
        heading.update(START, 1000, 2, MISSING, MISSING, MISSING);
        assertFalse(heading.update(offset(0, 300), 2000, 2, MISSING, MISSING, MISSING));
        assertTrue(heading.update(offset(0, 315), 3000, 2, MISSING, MISSING, MISSING));
        assertEquals(90, heading.bearingDegrees(), 0.01);
    }

    @Test public void datelineCrossingUsesTheShortGeographicDirection() {
        TravelHeading heading = new TravelHeading();
        heading.update(new GeoPoint(0, 179.9999), 1000, 2, MISSING, MISSING, MISSING);
        assertTrue(heading.update(new GeoPoint(0, -179.9999), 2000, 2, MISSING, MISSING, MISSING));
        assertEquals(90, heading.bearingDegrees(), 0.01);
    }

    @Test public void resetDiscardsPreviousSessionPositionCourseAndTimestamp() {
        TravelHeading heading = moving(270);
        heading.reset();
        assertFalse(heading.hasHeading());
        assertEquals(0, heading.bearingDegrees(), 0);
        assertTrue(heading.update(START, 0, 2, 180, 10, 0.1));
        assertEquals(180, heading.bearingDegrees(), 0);
    }

    private static TravelHeading moving(double course) {
        TravelHeading heading = new TravelHeading();
        assertTrue(heading.update(START, 1000, 2, course, 10, 0.1));
        return heading;
    }
    private static GeoPoint offset(double northMeters, double eastMeters) {
        return new GeoPoint(START.latitude + Math.toDegrees(northMeters / 6371008.8),
                START.longitude + Math.toDegrees(eastMeters / (6371008.8 * Math.cos(Math.toRadians(START.latitude)))));
    }
}
