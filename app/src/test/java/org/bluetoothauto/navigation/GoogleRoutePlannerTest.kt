// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.TurnGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GoogleRoutePlannerTest {
    private fun step(index: Int, instruction: String, following: Double?, label: String = "${following ?: 0} m",
                     kind: GoogleDirectionsStepKind = GoogleDirectionsStepKind.MANEUVER) =
        GoogleDirectionsStep(index, index * 2, instruction, following, label, kind, "fixture:$index")
    private fun capture(steps: List<GoogleDirectionsStep>) = GoogleDirectionsSnapshot("fixture", steps, 1000, true)
    private fun route() = RoutePlan(listOf(GeoPoint(0.0, 0.0), GeoPoint(.001, 0.0),
        GeoPoint(.001, .001), GeoPoint(.002, .001)), listOf(
        RoutePlan.Maneuver(0, 1, "Head north", "", 1),
        RoutePlan.Maneuver(1, 2, "Turn right onto First Street", "", 10),
        RoutePlan.Maneuver(2, 3, "Turn left onto Second Road", "", 15),
        RoutePlan.Maneuver(3, 3, "Your destination is on the right", "", 5)), 100.0, "Fixture destination")
    private fun capture(route: RoutePlan = route()) = capture(listOf(
        step(0, "Head north", route.cumulativeMeters[1], kind = GoogleDirectionsStepKind.DEPARTURE),
        step(1, "Turn right onto First St", route.cumulativeMeters[2] - route.cumulativeMeters[1]),
        step(2, "Turn left onto Second Rd", route.lengthMeters() - route.cumulativeMeters[2]),
        step(3, "Fixture destination", null, kind = GoogleDirectionsStepKind.ARRIVAL)))

    @Test fun independentlyValidatedCandidateAlignsNamesSidesLegsAndArrival() {
        val result = GoogleRoutePlanner.fromCandidate(capture(), 1, route(), route().cumulativeMeters[1])
        assertTrue(result.complete)
        assertEquals(listOf(1, 2, 3), result.turns.map { it.stepIndex })
        assertEquals(GoogleRoutePlanner.StopReason.COMPLETE, result.stopReason)
        assertEquals(0.0, result.turns[0].distanceAlongMeters, 0.0)
        assertEquals(route().cumulativeMeters[2] - route().cumulativeMeters[1], result.turns[1].distanceAlongMeters, .001)
        assertTrue(result.turns.all { it.geometry.points.size <= 256 && it.geometry.points.any { p -> p === it.geometry.junction } })
    }

    @Test fun originalOriginCanValidateWithoutInventingCurrentCountdown() {
        assertTrue(GoogleRoutePlanner.fromCandidate(capture(), 1, route()).complete)
        val wrongAnchor = GoogleRoutePlanner.fromCandidate(capture(), 1, route(), 1000.0)
        assertFalse(wrongAnchor.complete)
        assertEquals(GoogleRoutePlanner.StopReason.CANDIDATE_DISTANCE_MISMATCH, wrongAnchor.stopReason)
    }

    @Test fun departureIsOriginOnlyAndPreparesEveryActualUpcomingTurn() {
        val snapshot = capture().let { it.copy(steps = it.steps.map { s -> if (s.index == 0)
            s.copy(instruction = "Head east on Other Origin Label") else s }) }
        val result = GoogleRoutePlanner.fromCandidate(snapshot, 0, route())
        assertTrue(result.complete)
        assertEquals(listOf(1, 2, 3), result.turns.map { it.stepIndex })
        assertFalse(result.turns.any { it.stepIndex == 0 })
        assertEquals(GoogleRoutePlanner.StopReason.CANDIDATE_DISTANCE_MISMATCH,
            GoogleRoutePlanner.fromCandidate(snapshot, 0, route(), 1000.0).stopReason)
    }

    @Test fun skippedDepartureStillRequiresAnHonestFirstLeg() {
        val snapshot = capture().let { it.copy(steps = it.steps.map { s -> if (s.index == 0)
            s.copy(followingDistanceMeters = null) else s }) }
        assertEquals(GoogleRoutePlanner.StopReason.MISSING_LEG_DISTANCE,
            GoogleRoutePlanner.fromCandidate(snapshot, 0, route()).stopReason)
    }

    @Test fun differentRoadNamesRejectCandidateRatherThanSpoofGoogleGeometry() {
        val s = capture().let { it.copy(steps = it.steps.map { step -> if (step.index == 1) step.copy(instruction = "Turn right onto Wrong Road") else step }) }
        assertEquals(GoogleRoutePlanner.StopReason.CANDIDATE_NO_MATCH, GoogleRoutePlanner.fromCandidate(s, 1, route()).stopReason)
    }

    @Test fun sharedTowardReferenceDoesNotOverrideConflictingExplicitRoads() {
        val route = cueRoute("Turn right onto Second Street toward I-5", 10, 90.0)
        val result = GoogleRoutePlanner.fromCandidate(cueCapture("Turn right onto First Street toward I-5", route), 0, route)
        assertTrue(result.turns.isEmpty())
        assertEquals(GoogleRoutePlanner.StopReason.CANDIDATE_NO_MATCH, result.stopReason)
    }

    @Test fun sharedEarlierExitReferenceCannotOverrideDifferentOntoTargets() {
        val route = cueRoute("Take the I-5 exit onto Second Street", 20, 35.0)
        assertTrue(GoogleRoutePlanner.fromCandidate(cueCapture("Take the I-5 exit onto First Street", route), 0, route).turns.isEmpty())
    }

    @Test fun physicallyWrongSideRejectsEvenIncorrectProviderMetadata() {
        val old = route()
        val changed = RoutePlan(listOf(GeoPoint(0.0, 0.0), GeoPoint(.001, 0.0), GeoPoint(.001, -.001), GeoPoint(.002, -.001)), old.maneuvers, 100.0, "Fixture")
        assertTrue(GoogleRoutePlanner.fromCandidate(capture(), 1, changed).turns.isEmpty())
    }

    @Test fun inconsistentFollowingLegStopsAtLastValidatedStep() {
        val s = capture().let { it.copy(steps = it.steps.map { step -> if (step.index == 1) step.copy(followingDistanceMeters = 1000.0, followingDistanceLabel = "1 km") else step }) }
        val result = GoogleRoutePlanner.fromCandidate(s, 1, route())
        assertEquals(listOf(1), result.turns.map { it.stepIndex })
        assertEquals(GoogleRoutePlanner.StopReason.CANDIDATE_DISTANCE_MISMATCH, result.stopReason)
    }

    @Test fun missingLegIsNotInferredFromCandidate() {
        val s = capture().let { it.copy(steps = it.steps.map { step -> if (step.index == 1) step.copy(followingDistanceMeters = null) else step }) }
        assertEquals(GoogleRoutePlanner.StopReason.MISSING_LEG_DISTANCE, GoogleRoutePlanner.fromCandidate(s, 1, route()).stopReason)
    }

    @Test fun unannouncedMeaningfulCandidateTurnIsNotSilentlySkipped() {
        val old = route()
        val extra = RoutePlan(old.points, old.maneuvers.take(2) + RoutePlan.Maneuver(2, 2, "Turn right onto Other Street", "", 10) + old.maneuvers.drop(2), 100.0, "Fixture")
        val result = GoogleRoutePlanner.fromCandidate(capture(), 1, extra)
        assertEquals(1, result.turns.size)
        assertEquals(GoogleRoutePlanner.StopReason.CANDIDATE_EXTRA_TURN, result.stopReason)
    }

    @Test fun duplicateCandidateMatchesRemainAmbiguous() {
        val old = route()
        val duplicate = RoutePlan(old.points, old.maneuvers.take(2) + old.maneuvers[1] + old.maneuvers.drop(2), 100.0, "Fixture")
        assertEquals(GoogleRoutePlanner.StopReason.CANDIDATE_AMBIGUOUS, GoogleRoutePlanner.fromCandidate(capture(), 1, duplicate).stopReason)
    }

    @Test fun stableKeysBindActualGeometryAndSpeedLimitAsWellAsStep() {
        val before = GoogleRoutePlanner.fromCandidate(capture(), 1, route())
        val again = GoogleRoutePlanner.fromCandidate(capture().copy(capturedAtElapsed = 2000), 1, route())
        assertEquals(before.turns.map { it.geometry.key }, again.turns.map { it.geometry.key })
        val metadata = route().withRoadMetadata(null, doubleArrayOf(40.0, 50.0, 60.0))
        assertNotEquals(before.turns[0].geometry.key, GoogleRoutePlanner.fromCandidate(capture(), 1, metadata).turns[0].geometry.key)
    }

    private fun graph(): TurnMatcher.Graph = TurnMatcher.Graph(mapOf(1L to GeoPoint(0.0, 0.0),
        2L to GeoPoint(.001, 0.0), 3L to GeoPoint(.002, 0.0), 4L to GeoPoint(.002, -.001)), listOf(
        TurnMatcher.Way(10, listOf(1, 2, 3), mapOf("highway" to "residential", "name" to "First Street")),
        TurnMatcher.Way(20, listOf(3, 4), mapOf("highway" to "residential", "name" to "Second Road"))))
    private fun anchor() = TurnGeometry(listOf(GeoPoint(0.0, -.001), GeoPoint(0.0, 0.0), GeoPoint(.0009, 0.0)),
        GeoPoint(0.0, 0.0), key = "confirmed")
    private fun graphCapture() = capture(listOf(step(0, "Turn left onto First Street", 222.4),
        step(1, "Turn left onto Second Road", 100.0), step(2, "Fixture destination", null, kind = GoogleDirectionsStepKind.ARRIVAL)))

    @Test fun graphChainsFromKnownDepartureWithoutFutureGpsInjection() {
        val result = GoogleRoutePlanner.fromGraph(graphCapture(), 0, anchor(), graph())
        assertEquals(listOf(0, 1), result.turns.map { it.stepIndex })
        assertTrue(result.turns[1].geometry.junction.distanceTo(GeoPoint(.002, 0.0)) < .01)
        assertEquals(222.39, result.turns[1].distanceAlongMeters, .1)
        assertEquals(GoogleRoutePlanner.StopReason.UNSUPPORTED_GRAPH_STEP, result.stopReason)
    }

    @Test fun graphAmbiguityAndMissingCoverageDoNotProduceInventedSuffix() {
        val graph = graph().copy(ways = graph().ways.dropLast(1))
        val result = GoogleRoutePlanner.fromGraph(graphCapture(), 0, anchor(), graph)
        assertEquals(1, result.turns.size)
        assertEquals(GoogleRoutePlanner.StopReason.GRAPH_NO_UNIQUE_MATCH, result.stopReason)
    }

    @Test fun longGraphLegRequiresRealRouteGeometry() {
        val capture = graphCapture().let { it.copy(steps = it.steps.map { s -> if (s.index == 0) s.copy(followingDistanceMeters = 10_000.0) else s }) }
        assertEquals(GoogleRoutePlanner.StopReason.LEG_REQUIRES_ROUTE_GEOMETRY,
            GoogleRoutePlanner.fromGraph(capture, 0, anchor(), graph()).stopReason)
    }

    private fun cueRoute(instruction: String, type: Int, angle: Double = 25.0): RoutePlan {
        val after = GeoPoint(.001 + .001 * cos(Math.toRadians(angle)), .001 * sin(Math.toRadians(angle)))
        return RoutePlan(listOf(GeoPoint(0.0, 0.0), GeoPoint(.001, 0.0), after), listOf(
            RoutePlan.Maneuver(0, 1, "Head north", "", 1), RoutePlan.Maneuver(1, 2, instruction, "", type),
            RoutePlan.Maneuver(2, 2, "Your destination is on the right", "", 5)), 100.0, "Fixture")
    }
    private fun cueCapture(instruction: String, route: RoutePlan) = capture(listOf(
        step(0, instruction, route.lengthMeters() - route.cumulativeMeters[1]),
        step(1, "Fixture destination", null, kind = GoogleDirectionsStepKind.ARRIVAL)))

    @Test fun keepMergeAndDirectionalRampPhrasesUseOrderedProviderSemantics() {
        for ((text, type) in listOf("Keep right onto I-5 N" to 23, "Merge onto I-5 N" to 25,
            "Use the right lane to take the I-5 N ramp to Seattle" to 23, "Take exit 2 onto First Road" to 20)) {
            val route = cueRoute(if (type == 23) "Keep right onto I-5 North" else text, type)
            assertTrue(text, GoogleRoutePlanner.fromCandidate(cueCapture(text, route), 0, route).complete)
        }
    }

    @Test fun barePrivateFinalTurnsStillNeedPhysicalSideAndLegAgreement() {
        val route = cueRoute("Turn right.", 10, 90.0)
        assertTrue(GoogleRoutePlanner.fromCandidate(cueCapture("Turn right", route), 0, route).complete)
        assertTrue(GoogleRoutePlanner.fromCandidate(cueCapture("Turn left", route), 0, route).turns.isEmpty())
    }

    @Test fun explicitStateHighwayReferencesAgreeAcrossProviderNaming() {
        val route = cueRoute("Turn right to take the SR 18 West ramp", 18, 60.0)
        assertTrue(GoogleRoutePlanner.fromCandidate(cueCapture("Turn right onto Washington State Hwy 18 W ramp", route), 0, route).complete)
        assertTrue(GoogleRoutePlanner.fromCandidate(cueCapture("Turn right onto Wash State Rte 18 W ramp", route), 0, route).complete)
        assertFalse(GoogleRoutePlanner.fromCandidate(cueCapture("Turn right onto Washington State Hwy 19 W ramp", route), 0, route).complete)
    }

    @Test fun orderedRampsDisambiguateRoundedLongLegWithoutSkippingEarlierEvent() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(.001, 0.0), GeoPoint(.001, .153),
            GeoPoint(.001, .1611), GeoPoint(.001, .1621))
        val route = RoutePlan(points, listOf(RoutePlan.Maneuver(0, 1, "Head north", "", 1),
            RoutePlan.Maneuver(1, 2, "Turn right onto First Road", "", 10),
            RoutePlan.Maneuver(2, 3, "Keep right onto I-5 North", "", 23),
            RoutePlan.Maneuver(3, 4, "Keep right onto I-5 North", "", 23),
            RoutePlan.Maneuver(4, 4, "Your destination is on the right", "", 5)), 1000.0, "Fixture")
        val capture = capture(listOf(step(0, "Turn right onto First Road", 17702.784, "11 mi"),
            step(1, "Use the right lane to take the I-5 N exit toward Seattle", 804.672, "0.5 mi"),
            step(2, "Use the right lane to take the I-5 N ramp to Seattle", 111.2),
            step(3, "Fixture destination", null, kind = GoogleDirectionsStepKind.ARRIVAL)))
        assertTrue(GoogleRoutePlanner.fromCandidate(capture, 0, route).complete)
    }

    @Test fun unsupportedOrIncompleteCaptureNeverCreatesPreloadGeometry() {
        assertTrue(GoogleRoutePlanner.fromCandidate(capture().copy(complete = false), 1, route()).turns.isEmpty())
        assertTrue(GoogleRoutePlanner.fromCandidate(capture(), -1, route()).turns.isEmpty())
    }

    private fun combinedRoute(): RoutePlan {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(.001, 0.0), GeoPoint(.001, .001),
            GeoPoint(.001, .002), GeoPoint(0.0, .002))
        return RoutePlan(points, listOf(RoutePlan.Maneuver(0, 1, "Head north", "", 1),
            RoutePlan.Maneuver(1, 3, "Turn right onto First Street", "", 10),
            RoutePlan.Maneuver(3, 4, "Turn right onto Third Lane", "", 10),
            RoutePlan.Maneuver(4, 4, "Your destination is on the right", "", 5)), 100.0, "Fixture")
    }
    private fun combinedCapture() = capture(listOf(step(0, "Turn right onto First Street", 111.2),
        step(1, "Continue straight onto Second Road", 111.2), step(2, "Turn right onto Third Lane", 111.2),
        step(3, "Fixture destination", null, kind = GoogleDirectionsStepKind.ARRIVAL)))
    private fun detailGraph() = TurnMatcher.Graph(mapOf(1L to GeoPoint(.001, 0.0),
        2L to GeoPoint(.001, .001), 3L to GeoPoint(.001, .002)), listOf(
        TurnMatcher.Way(10, listOf(1, 2), mapOf("highway" to "residential", "name" to "First Street")),
        TurnMatcher.Way(20, listOf(2, 3), mapOf("highway" to "residential", "name" to "Second Road"))))

    @Test fun missingProviderContinueRequestsRoadDetailRatherThanInventingJunction() {
        val result = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, combinedRoute())
        assertEquals(1, result.turns.size)
        assertEquals(GoogleRoutePlanner.StopReason.NEEDS_ROAD_DETAIL, result.stopReason)
        assertEquals(1, result.detailRequests.single().stepIndex)
        assertEquals(500.0, result.detailRequests.single().radiusMeters, 0.0)
    }

    @Test fun sharedNamedOsmNodeOnCandidateLocatesActualContinueAndCompletesTrip() {
        val result = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, combinedRoute(), detailGraphs = listOf(detailGraph()))
        assertTrue(result.complete)
        assertEquals(listOf(0, 1, 2, 3), result.turns.map { it.stepIndex })
        assertTrue(result.turns[1].geometry.junction.distanceTo(GeoPoint(.001, .001)) < .001)
    }

    @Test fun exactCandidateEdgeNameBoundaryCanProveContinueWithoutOverpass() {
        val route = combinedRoute().withRoadSpans(listOf(
            RoutePlan.RoadSpan(0, 1, listOf("Approach"), "drive", "road"),
            RoutePlan.RoadSpan(1, 2, listOf("First Street"), "drive", "road"),
            RoutePlan.RoadSpan(2, 3, listOf("Second Road"), "drive", "road"),
            RoutePlan.RoadSpan(3, 4, listOf("Third Lane"), "drive", "road")))
        val result = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, route)
        assertTrue(result.complete)
        assertTrue(result.detailRequests.isEmpty())
        assertSame(route.points[2], result.turns[1].geometry.junction)
    }

    @Test fun omittedLeadingCardinalCanUseExactNamedEdgeTransition() {
        val route = combinedRoute().withRoadSpans(listOf(
            RoutePlan.RoadSpan(0, 1, listOf("Approach"), "drive", "road"),
            RoutePlan.RoadSpan(1, 2, listOf("First Street"), "drive", "road"),
            RoutePlan.RoadSpan(2, 3, listOf("South Second Road"), "drive", "road"),
            RoutePlan.RoadSpan(3, 4, listOf("Third Lane"), "drive", "road")))
        val result = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, route)
        assertTrue(result.complete)
        assertSame(route.points[2], result.turns[1].geometry.junction)
    }

    @Test fun explicitOppositeCardinalCannotUseNamedEdgeTransition() {
        val route = combinedRoute().withRoadSpans(listOf(
            RoutePlan.RoadSpan(0, 1, listOf("Approach"), "drive", "road"),
            RoutePlan.RoadSpan(1, 2, listOf("First Street"), "drive", "road"),
            RoutePlan.RoadSpan(2, 3, listOf("West Second Road"), "drive", "road"),
            RoutePlan.RoadSpan(3, 4, listOf("Third Lane"), "drive", "road")))
        val capture = combinedCapture().let { it.copy(steps = it.steps.map { step ->
            if (step.index == 1) step.copy(instruction = "Continue straight onto East Second Road") else step
        }) }
        assertFalse(GoogleRoutePlanner.fromCandidate(capture, 0, route).complete)
    }

    private fun redundantContinueCapture() = combinedCapture().let { it.copy(steps = it.steps.map { step ->
        if (step.index == 1) step.copy(instruction = "Continue straight onto First Street") else step
    }) }
    private fun sameRoadRoute(middleName: String = "First Street") = combinedRoute().withRoadSpans(listOf(
        RoutePlan.RoadSpan(0, 1, listOf("Approach"), "drive", "road"),
        RoutePlan.RoadSpan(1, 2, listOf("First Street"), "drive", "road"),
        RoutePlan.RoadSpan(2, 3, listOf(middleName), "drive", "road"),
        RoutePlan.RoadSpan(3, 4, listOf("Third Lane"), "drive", "road")))

    @Test fun redundantContinueProducesOnlyValidatedPathContextAfterFollowingTurnChecks() {
        val route = sameRoadRoute()
        val result = GoogleRoutePlanner.fromCandidate(redundantContinueCapture(), 0, route)
        assertTrue(result.complete)
        assertTrue(result.detailRequests.isEmpty())
        assertEquals("google-route-context", result.turns[1].geometry.confidence)
        assertSame(route.points[2], result.turns[1].geometry.junction)
        assertEquals("validated-google-route", result.turns[2].geometry.confidence)
    }

    @Test fun TwoExplicitAliasesMustPersistAcrossEntireLegToDescribeSameRoadContext() {
        val route = combinedRoute().withRoadSpans(listOf(
            RoutePlan.RoadSpan(0, 1, listOf("Approach"), "drive", "road"),
            RoutePlan.RoadSpan(1, 2, listOf("First Street", "Second Road"), "drive", "road"),
            RoutePlan.RoadSpan(2, 3, listOf("First Street", "Second Road"), "drive", "road"),
            RoutePlan.RoadSpan(3, 4, listOf("Third Lane"), "drive", "road")))
        val result = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, route)
        assertTrue(result.complete)
        assertEquals("google-route-context", result.turns[1].geometry.confidence)
    }

    @Test fun redundantContinueCannotConcealWrongFollowingTurnOrLegOrChangedRoad() {
        val original = redundantContinueCapture()
        val wrongTurn = original.copy(steps = original.steps.map { if (it.index == 2)
            it.copy(instruction = "Turn left onto Third Lane") else it })
        val wrongLeg = original.copy(steps = original.steps.map { if (it.index == 1)
            it.copy(followingDistanceMeters = 900.0) else it })
        for ((capture, route) in listOf(wrongTurn to sameRoadRoute(), wrongLeg to sameRoadRoute(),
                original to sameRoadRoute("Other Road"), original to combinedRoute())) {
            val result = GoogleRoutePlanner.fromCandidate(capture, 0, route)
            assertFalse(result.complete)
            assertEquals(1, result.turns.size)
        }
    }

    @Test fun contextAndConfirmedJunctionHaveDistinctArtworkKeysEvenForIdenticalFocus() {
        val context = GoogleRoutePlanner.fromCandidate(redundantContinueCapture(), 0, sameRoadRoute()).turns[1].geometry
        val junction = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, sameRoadRoute("Second Road")).turns[1].geometry
        assertEquals(context.points.map { it.latitude to it.longitude }, junction.points.map { it.latitude to it.longitude })
        assertNotEquals(context.key, junction.key)
    }

    @Test fun OverlappingAliasesOrNonDrivingEdgesAreNotExactTransitionProof() {
        for ((names, mode) in listOf(listOf("First Street", "Second Road") to "drive", listOf("First Street") to "pedestrian")) {
            val route = combinedRoute().withRoadSpans(listOf(
                RoutePlan.RoadSpan(0, 1, listOf("Approach"), "drive", "road"),
                RoutePlan.RoadSpan(1, 2, names, mode, "road"),
                RoutePlan.RoadSpan(2, 3, listOf("Second Road"), "drive", "road"),
                RoutePlan.RoadSpan(3, 4, listOf("Third Lane"), "drive", "road")))
            assertFalse(GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, route).complete)
        }
    }

    @Test fun sameCoordinatesWithoutSharedRoadNodeDoNotLocateContinue() {
        val graph = detailGraph().let { it.copy(nodes = it.nodes + (4L to it.nodes.getValue(2L)),
            ways = it.ways.map { w -> if (w.id == 20L) w.copy(nodes = listOf(4, 3)) else w }) }
        val result = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, combinedRoute(), detailGraphs = listOf(graph))
        assertFalse(result.complete)
        assertEquals(1, result.turns.size)
    }

    @Test fun contraryOneWayAndPrivateDetailCannotProveContinuation() {
        for (tags in listOf(mapOf("oneway" to "-1"), mapOf("access" to "private"))) {
            val graph = detailGraph().let { it.copy(ways = it.ways.map { w -> if (w.id == 20L) w.copy(tags = w.tags + tags) else w }) }
            assertFalse(GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, combinedRoute(), detailGraphs = listOf(graph)).complete)
        }
    }

    @Test fun geometricallyRemoteNamedNodeCannotBeUsedAsGoogleContinue() {
        val graph = detailGraph().let { it.copy(nodes = it.nodes.mapValues { (_, p) -> GeoPoint(p.latitude + .001, p.longitude) }) }
        val result = GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, combinedRoute(), detailGraphs = listOf(graph))
        assertFalse(result.complete)
        assertEquals(1, result.turns.size)
    }

    @Test fun pedestrianOrCyclingWaysCannotProveCarRoadNameTransition() {
        for (highway in listOf("footway", "cycleway", "steps", "path", "bridleway")) {
            val graph = detailGraph().let { it.copy(ways = it.ways.map { w -> if (w.id == 20L) w.copy(tags = w.tags + ("highway" to highway)) else w }) }
            assertFalse(GoogleRoutePlanner.fromCandidate(combinedCapture(), 0, combinedRoute(), detailGraphs = listOf(graph)).complete)
        }
    }
}
