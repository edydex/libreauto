// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class NotificationRouteTrackerTest {
    private fun point(east: Double = 0.0, north: Double = 0.0) = GeoPoint(47 + north / 111_195.08,
        -122 + east / (111_195.08 * cos(Math.toRadians(47.0))))
    private fun cue(text: String = "Turn left onto Third St", at: Long = 2_000) = Guidance(text, 100.0, receivedAt = at)
    private fun fix(east: Double = 0.0, north: Double = 30.0, at: Long = 2_000, accuracy: Float = 3f) =
        point(east, north).let { Position(it.latitude, it.longitude, accuracy = accuracy, receivedAt = at, speedMps = 0f) }
    private fun geometry(points: List<GeoPoint> = listOf(point(-100.0), point(), point(north = 100.0))) =
        TurnGeometry(points, point(), confidence = "matched", key = "proved-old-turn")
    private fun tracker() = NotificationRouteTracker().apply {
        remember(cue("Turn left onto Second St", 1_000), geometry(), 1_000)
    }

    @Test fun changedCueAndActualFixOnProvedDepartureCanSupplyMissingStationaryCourse() {
        val position = fix(); val course = tracker().fallbackCourse(position, cue(), 2_000)
        assertEquals(0.0, course!!, .1); assertNull(position.bearing)
        assertEquals(fix().latitude, position.latitude, 0.0)
    }
    @Test fun noPriorValidatedTurnDoesNotInventCourse() {
        assertNull(NotificationRouteTracker().fallbackCourse(fix(), cue(), 2_000))
    }
    @Test fun sameInstructionDoesNotAssumeUnseenStepAdvance() {
        assertNull(tracker().fallbackCourse(fix(), cue("Turn left onto Second Street"), 2_000))
    }
    @Test fun existingReportedCourseIsNeverOverriddenEvenWhenItConflicts() {
        for (bearing in listOf(0f, 90f, 180f, Float.NaN))
            assertNull(tracker().fallbackCourse(fix().copy(bearing = bearing), cue(), 2_000))
    }
    @Test fun atJunctionAndUncertainEarlyDepartureStayUnknown() {
        assertNull(tracker().fallbackCourse(fix(north = 0.0), cue(), 2_000))
        assertNull(tracker().fallbackCourse(fix(north = 10.0), cue(), 2_000))
        assertNull(tracker().fallbackCourse(fix(north = 30.0, accuracy = 20f), cue(), 2_000))
    }
    @Test fun approachSideOfOldJunctionCannotBecomeDepartureCourse() {
        assertNull(tracker().fallbackCourse(fix(east = -30.0, north = 0.0), cue(), 2_000))
    }
    @Test fun offPathAndBeyondEndpointNeverMoveActualFixOntoProvedRoad() {
        assertNull(tracker().fallbackCourse(fix(east = 25.0), cue(), 2_000))
        assertNull(tracker().fallbackCourse(fix(north = 110.0), cue(), 2_000))
    }
    @Test fun staleInaccurateAndFutureFixesDoNotSeedMatcher() {
        for (position in listOf(fix(at = 0), fix(at = 20_001), fix(accuracy = 26f),
            fix(accuracy = Float.NaN), fix().copy(speedMps = Float.NaN), fix().copy(speedMps = 81f)))
            assertNull(tracker().fallbackCourse(position, cue(at = 20_000), 20_000))
    }
    @Test fun staleOrFutureGuidanceDoesNotSeedMatcher() {
        assertNull(tracker().fallbackCourse(fix(at = 40_000), cue(at = 1_000), 40_000))
        assertNull(tracker().fallbackCourse(fix(), cue(at = 2_001), 2_000))
    }
    @Test fun memoryLeaseIsNotExtendedByRepeatedStationaryUse() {
        val tracker = tracker()
        assertNotNull(tracker.fallbackCourse(fix(at = 300_000), cue(at = 300_000), 300_000))
        assertNull(tracker.fallbackCourse(fix(at = 301_001), cue(at = 301_001), 301_001))
        assertNull(tracker.fallbackCourse(fix(), cue(), 2_000))
    }
    @Test fun clearingSessionPreventsOldRoadReuse() {
        val tracker = tracker(); tracker.reset(); assertNull(tracker.fallbackCourse(fix(), cue(), 2_000))
    }
    @Test fun sourceAndReroutingCuesInvalidateContinuity() {
        for (bad in listOf(cue().copy(source = "Own maps"), cue().copy(demo = true), cue("Rerouting…"))) {
            val tracker = tracker(); assertNull(tracker.fallbackCourse(fix(), bad, 2_000))
            assertNull(tracker.fallbackCourse(fix(), cue(), 2_000))
        }
    }
    @Test fun demoLocationAndUnsupportedCueCannotSupplyCourse() {
        assertNull(tracker().fallbackCourse(fix().copy(demo = true), cue(), 2_000))
        assertNull(tracker().fallbackCourse(fix(), cue("Continue straight"), 2_000))
    }
    @Test fun invalidOrNonMatchedGeometryCannotLeaveOldProofAlive() {
        for (bad in listOf(geometry().copy(confidence = "google-route-context"), geometry().copy(key = ""),
            geometry(listOf(point(-10.0), point(), point(north = 10.0))),
            geometry(listOf(point(-100.0), point(), point(), point(north = 100.0))))) {
            val tracker = tracker(); tracker.remember(cue("Turn left onto Second St"), bad, 2_000)
            assertNull(tracker.fallbackCourse(fix(at = 3_000), cue(at = 3_000), 3_000))
        }
    }
    @Test fun actualDirectedDepartureControlsSeedRatherThanOldApproachHeading() {
        val tracker = NotificationRouteTracker()
        tracker.remember(cue("Turn right onto Second St", 1_000),
            geometry(listOf(point(north = -100.0), point(), point(east = 100.0))), 1_000)
        assertEquals(90.0, tracker.fallbackCourse(fix(east = 30.0, north = 0.0), cue(), 2_000)!!, .1)
    }
    @Test fun nearbyOppositeDirectionsOnLoopingDepartureRemainAmbiguous() {
        val tracker = NotificationRouteTracker()
        tracker.remember(cue("Turn left onto Second St", 1_000),
            geometry(listOf(point(-40.0), point(), point(north = 40.0),
                point(east = 4.0, north = 40.0), point(east = 4.0))), 1_000)
        assertNull(tracker.fallbackCourse(fix(east = 2.0, north = 25.0), cue(), 2_000))
    }
    @Test fun strictlyConstrainedCandidateKeepsItsRealDirectedDepartureEligible() {
        val tracker = NotificationRouteTracker()
        tracker.remember(cue("Turn left onto Second St", 1_000),
            geometry().copy(confidence = "candidate-constrained"), 1_000)
        assertEquals(0.0, tracker.fallbackCourse(fix(), cue(), 2_000)!!, .1)
    }
    @Test fun longerArtworkPathKeepsOnlyFirst150MetersForCourseHandoff() {
        val tracker=NotificationRouteTracker()
        tracker.remember(cue("Turn left onto Second St",1_000),
            geometry(listOf(point(-100.0),point(),point(north=500.0))).copy(confidence="candidate-constrained"),1_000)
        assertEquals(0.0,tracker.fallbackCourse(fix(north=80.0),cue(),2_000)!!,.1)
        assertNull(tracker.fallbackCourse(fix(north=170.0),cue(),2_000))
    }
    @Test fun longerArtworkPathDoesNotExtendCourseMemoryLease() {
        val tracker=NotificationRouteTracker()
        tracker.remember(cue("Turn left onto Second St",1_000),
            geometry(listOf(point(-100.0),point(),point(north=500.0))),1_000)
        assertNull(tracker.fallbackCourse(fix(north=80.0,at=301_001),cue(at=301_001),301_001))
    }
}
