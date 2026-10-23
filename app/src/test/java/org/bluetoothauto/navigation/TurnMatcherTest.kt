// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test

class TurnMatcherTest {
    private fun base(extra: List<TurnMatcher.Way> = emptyList(), nodes: Map<Long, GeoPoint> = emptyMap()): TurnMatcher.Graph {
        val points = mapOf(1L to GeoPoint(0.0, 0.0), 2L to GeoPoint(0.0, .0005), 3L to GeoPoint(0.0, .001),
            4L to GeoPoint(0.0, .002), 5L to GeoPoint(-.001, .001)) + nodes
        return TurnMatcher.Graph(points, listOf(
            way(10, listOf(1, 2), "Pine Street", mapOf("maxspeed" to "25 mph")),
            way(11, listOf(2, 3, 4), "Pine St"), way(20, listOf(3, 5), "James Street")) + extra)
    }
    private fun way(id: Long, nodes: List<Long>, name: String, tags: Map<String, String> = emptyMap()) =
        TurnMatcher.Way(id, nodes, mapOf("highway" to "residential", "name" to name) + tags)
    private fun request(instruction: String = "Turn right onto James St", meters: Double = 100.0,
                        bearing: Double = 90.0) = TurnMatcher.Request(GeoPoint(0.0, .0001), bearing, 5.0, instruction, meters)

    @Test fun matchesConnectedWayChainAndBuildsActualTurnPath() {
        val result = TurnMatcher.match(base(), request())!!
        assertEquals(0.0, result.junction.latitude, 0.0); assertEquals(.001, result.junction.longitude, 0.0)
        assertTrue(result.points.size >= 4)
        assertEquals(.0001, result.points.first().longitude, 1e-10)
        assertTrue(result.points.last().latitude < -.0008)
        assertEquals(40.2336, result.approachSpeedLimitKph!!, 1e-6)
    }
    @Test fun anyLanePrefaceStillRequiresTheActualTurnSideAndConnectedTargetRoad() {
        for(right in listOf(true,false)) {
            val side=if(right)"right"else"left"
            fun orient(graph:TurnMatcher.Graph)=if(right)graph else graph.copy(
                nodes=graph.nodes.mapValues { (_,p)->GeoPoint(-p.latitude,p.longitude) })
            val onto="Use any lane to turn $side onto James Street"
            val toward="Use any lane to turn $side toward James Street"
            assertNotNull(TurnMatcher.match(orient(base()),request(onto)))
            assertNotNull(TurnMatcher.match(orient(towardGraph()),request(toward)))
            assertNull(TurnMatcher.match(orient(towardGraph()),request(onto)))
            val wrongSide=if(right)"left"else"right"
            assertNull(TurnMatcher.match(orient(base()),request("Use any lane to turn $wrongSide onto James Street")))
        }
    }
    @Test fun numericOrdinalStreetAliasesPreserveNumberRoadTypeAndDirection() {
        assertTrue(TurnMatcher.roadMatches("SE 256 St",listOf("Southeast 256th Street")))
        assertTrue(TurnMatcher.roadMatches("NE 21st Ave",listOf("Northeast 21 Avenue")))
        assertTrue(TurnMatcher.roadMatches("22 Rd SW",listOf("22nd Road Southwest")))
        for(other in listOf("Southeast 257th Street","Southeast 256th Avenue",
            "Southwest 256th Street","Southeast 256th Street Church"))
            assertFalse(other,TurnMatcher.roadMatches("SE 256 St",listOf(other)))
        assertFalse(TurnMatcher.roadMatches("WA 256",listOf("WA 256th")))
    }
    @Test fun anOrdinalAliasStillNeedsTheActualConnectedTurnAndCorrectSide() {
        val graph=base().let { it.copy(ways=it.ways.map { w->
            if(w.id==20L)w.copy(tags=w.tags+("name" to "Southeast 256th Street")) else w }) }
        assertNotNull(TurnMatcher.match(graph,request("Use any lane to turn right onto SE 256 St")))
        assertNull(TurnMatcher.match(graph,request("Use any lane to turn left onto SE 256 St")))
        assertNull(TurnMatcher.match(graph,request("Use any lane to turn right onto SE 257 St")))
    }
    @Test fun sameCoordinatesWithoutSharedNodeNeverInventIntersection() {
        val graph = base().let { it.copy(nodes = it.nodes + (30L to GeoPoint(0.0, .001)),
            ways = it.ways.filterNot { w -> w.id == 20L } + way(20, listOf(30, 5), "James Street")) }
        assertNull(TurnMatcher.match(graph, request()))
    }
    private fun towardGraph(extra:List<TurnMatcher.Way> = emptyList(), nodes:Map<Long,GeoPoint> = emptyMap()) =
        TurnMatcher.Graph(mapOf(1L to GeoPoint(0.0,0.0),2L to GeoPoint(0.0,.001),3L to GeoPoint(-.0004,.001),
            4L to GeoPoint(-.001,.001),5L to GeoPoint(-.002,.001))+nodes,listOf(
            way(10,listOf(1,2),"Approach Street"),way(20,listOf(2,3,4),"",mapOf("highway" to "service")),
            way(30,listOf(4,5),"James Street"))+extra)
    @Test fun towardStreetProvesActualConnectorTurnInsteadOfRelabellingItOntoTarget() {
        val graph=towardGraph()
        val toward=TurnMatcher.match(graph,request("Turn right toward James Street"))!!
        assertEquals(0.0,toward.junction.latitude,0.0)
        assertEquals(.001,toward.junction.longitude,0.0)
        assertTrue(toward.points.any{it.latitude<0})
        assertNull(TurnMatcher.match(graph,request("Turn right onto James Street")))
        assertNull(TurnMatcher.match(graph,request("Turn left toward James Street")))
    }
    @Test fun towardCanBeginOnAnUnnamedDrivableApproach() {
        val graph=towardGraph().let{it.copy(ways=it.ways.map{w->if(w.id==10L)w.copy(tags=mapOf("highway" to "service"))else w})}
        assertNotNull(TurnMatcher.match(graph,request("Turn right toward James Street")))
    }
    @Test fun towardTargetMustBeConnectedAndWithinThreeHundredMeters() {
        val disconnected=towardGraph().let{it.copy(nodes=it.nodes+(40L to it.nodes.getValue(4L)),
            ways=it.ways.map{w->if(w.id==30L)w.copy(nodes=listOf(40,5))else w})}
        assertNull(TurnMatcher.match(disconnected,request("Turn right toward James Street")))
        val far=towardGraph().let{it.copy(nodes=it.nodes+(4L to GeoPoint(-.003,.001))+(5L to GeoPoint(-.004,.001)))}
        assertNull(TurnMatcher.match(far,request("Turn right toward James Street")))
    }
    @Test fun towardDoesNotTraverseAnotherNamedRoadOrAnUnannouncedSharpTurn() {
        val named=towardGraph().let{it.copy(ways=it.ways.map{w->if(w.id==20L)w.copy(tags=w.tags+("name" to "Other Street"))else w})}
        assertNull(TurnMatcher.match(named,request("Turn right toward James Street")))
        val sharp=towardGraph().let{it.copy(nodes=it.nodes+(5L to GeoPoint(-.001,.002)))}
        assertNull(TurnMatcher.match(sharp,request("Turn right toward James Street")))
    }
    @Test fun twoConnectorPathsToTheNamedStreetRemainAmbiguous() {
        val graph=towardGraph(listOf(way(21,listOf(2,6,7),"",mapOf("highway" to "service")),
            way(31,listOf(7,8),"James Street")),mapOf(6L to GeoPoint(-.0004,.0012),7L to GeoPoint(-.001,.0012),8L to GeoPoint(-.002,.0012)))
        assertNull(TurnMatcher.match(graph,request("Turn right toward James Street")))
    }
    @Test fun towardRespectsConnectorAccessAndOneWayRestrictions() {
        for(tags in listOf(mapOf("access" to "private"),mapOf("oneway" to "-1"))) {
            val graph=towardGraph().let{it.copy(ways=it.ways.map{w->if(w.id==20L)w.copy(tags=w.tags+tags)else w})}
            assertNull(TurnMatcher.match(graph,request("Turn right toward James Street")))
        }
    }
    @Test fun notificationTopologyCanEliminateAnUnconnectedParallelApproach() {
        val graph = base(listOf(way(30, listOf(31, 32), "Pine Street")),
            mapOf(31L to GeoPoint(.00002, 0.0), 32L to GeoPoint(.00002, .002)))
        assertNotNull(TurnMatcher.match(graph, request()))
    }
    @Test fun competingParallelRoadsWithPlausibleNamedTurnsRemainAmbiguous() {
        val graph = base(listOf(way(30, listOf(31, 32, 33), "Pine Street"),
            way(21, listOf(32, 34), "James Street")), mapOf(
            31L to GeoPoint(.0001, 0.0), 32L to GeoPoint(.0001, .001),
            33L to GeoPoint(.0001, .002), 34L to GeoPoint(-.001, .001)))
        assertNull(TurnMatcher.match(graph, request().copy(point = GeoPoint(.00004, .0001))))
    }
    @Test fun modestCenterlineOffsetIsResolvedByFullTurnConstraints() {
        val result = TurnMatcher.match(base(), request().copy(point = GeoPoint(.0001, .0001)))!!
        assertEquals(0.0, result.points.first().latitude, 0.0)
        assertEquals(.001, result.junction.longitude, 0.0)
        assertNull(TurnMatcher.match(base(), request("Turn left onto James Street").copy(point = GeoPoint(.0001, .0001))))
    }
    @Test fun gpsRoadAdmissionNeverExceedsTwentyFiveMeters() {
        assertNotNull(TurnMatcher.match(base(), request().copy(point = GeoPoint(.00022, .0001), accuracy = 25.0)))
        assertNull(TurnMatcher.match(base(), request().copy(point = GeoPoint(.00024, .0001), accuracy = 25.0)))
    }
    @Test fun adjacentFragmentsOfSameCorridorDoNotMakeDuplicateTurns() {
        assertNotNull(TurnMatcher.match(base(), request(meters = 57.0).copy(point = GeoPoint(0.0, .00049))))
    }
    @Test fun wrongTurnSideAndDistanceDoNotMatch() {
        assertNull(TurnMatcher.match(base(), request("Turn left onto James Street")))
        assertNull(TurnMatcher.match(base(), request(meters = 500.0)))
    }
    @Test fun requiresExplicitTargetRoadAndReliableCourse() {
        assertNull(TurnMatcher.match(base(), request("Turn right")))
        assertNull(TurnMatcher.match(base(), request("Continue onto James Street")))
        assertNull(TurnMatcher.match(base(), request(bearing = Double.NaN)))
        assertNull(TurnMatcher.match(base(), request().copy(accuracy = 40.0)))
    }
    @Test fun duplicatePlausibleJunctionsAreRejected() {
        val graph = base(listOf(way(21, listOf(6, 7), "James Street")),
            mapOf(6L to GeoPoint(0.0, .0014), 7L to GeoPoint(-.001, .0014))).let {
            it.copy(ways = it.ways.map { w -> if (w.id == 11L) w.copy(nodes = listOf(2, 3, 6, 4)) else w })
        }
        assertNull(TurnMatcher.match(graph, request(meters = 120.0)))
    }
    @Test fun targetAndDistanceCanSelectTheOnlyCompletePathAcrossAForwardFork() {
        val graph = base(listOf(way(12, listOf(2, 6), "Pine Street")), mapOf(6L to GeoPoint(.0003, .001)))
        assertNotNull(TurnMatcher.match(graph, request()))
    }
    @Test fun twoForwardBranchesProvingDifferentTargetJunctionsRemainAmbiguous() {
        val graph = base(listOf(way(12, listOf(2, 6, 7), "Pine Street"),
            way(21, listOf(7, 8), "James Street")), mapOf(6L to GeoPoint(.00015, .001),
            7L to GeoPoint(.00015, .0014), 8L to GeoPoint(-.001, .0014)))
        assertNull(TurnMatcher.match(graph, request(meters = 120.0)))
    }
    @Test fun distinctForwardPathsConvergingAtOneTargetAreNotOneUniquePath() {
        val graph = base(listOf(way(12, listOf(2, 6, 7, 3), "Pine Street")),
            mapOf(6L to GeoPoint(.00008, .0007), 7L to GeoPoint(.00008, .00085)))
        assertNull(TurnMatcher.match(graph, request()))
    }
    @Test fun roadReferenceAliasesAreExactAndPreserveExplicitDirectionConflicts() {
        assertTrue(TurnMatcher.roadMatches("Wash State Rte 18 W ramp", listOf("SR 18")))
        assertTrue(TurnMatcher.roadMatches("Washington State Hwy 18 W ramp", listOf("State Route 18 West")))
        assertFalse(TurnMatcher.roadMatches("State Route 18 West", listOf("State Route 18 East")))
        assertFalse(TurnMatcher.roadMatches("State Route 18", listOf("State Route 19")))
    }
    @Test fun oneWayAndPrivateDepartureAreRespected() {
        for (tags in listOf(mapOf("oneway" to "-1"), mapOf("access" to "private"), mapOf("junction" to "roundabout"))) {
            val graph = base().let { it.copy(ways = it.ways.map { w -> if (w.id == 20L) w.copy(tags = w.tags + tags) else w }) }
            assertNull(TurnMatcher.match(graph, request()))
        }
    }
    @Test fun reverseOneWayIsTraversedOnlyInItsPermittedDirection() {
        val reverse = base().let { it.copy(ways = it.ways.map { way ->
            if (way.id in setOf(10L, 11L)) way.copy(nodes = way.nodes.reversed(), tags = way.tags + ("oneway" to "-1")) else way
        }) }
        assertNotNull(TurnMatcher.match(reverse, request()))
        val contrary = reverse.copy(ways = reverse.ways.map { way ->
            if (way.id in setOf(10L, 11L)) way.copy(tags = way.tags + ("oneway" to "yes")) else way
        })
        assertNull(TurnMatcher.match(contrary, request()))
    }
    @Test fun directionalLimitsRespectOriginalWayOrder() {
        val tags = mapOf("maxspeed" to "50", "maxspeed:forward" to "25 mph", "maxspeed:backward" to "30")
        assertEquals(40.2336, TurnMatcher.speedLimit(tags, true)!!, 1e-6)
        assertEquals(30.0, TurnMatcher.speedLimit(tags, false)!!, 0.0)
    }
    @Test fun unknownVariableAndImplicitLimitsStayUnknown() {
        for (raw in listOf("", "0", "signals", "walk", "US:urban", "30;50", "500"))
            assertNull(TurnMatcher.speedLimit(mapOf("maxspeed" to raw), true))
        assertNull(TurnMatcher.speedLimit(mapOf("maxspeed" to "30", "maxspeed:conditional" to "20 @ (school_hours)"), true))
        assertEquals(Double.POSITIVE_INFINITY, TurnMatcher.speedLimit(mapOf("maxspeed" to "none"), true)!!, 0.0)
    }
    @Test fun abbreviationsAreExactAndNoFuzzyWrongStreetIsAccepted() {
        assertEquals(TurnMatcher.normalize("South James Avenue"), TurnMatcher.normalize("S. James Ave."))
        assertEquals(TurnMatcher.normalize("I-5"), TurnMatcher.normalize("Interstate 5"))
        assertNull(TurnMatcher.match(base(), request("Turn right onto Jameson Street")))
    }
    @Test fun unknownPostedLimitDoesNotHideOtherwiseExactGeometry() {
        val graph = base().let { it.copy(ways = it.ways.map { w -> w.copy(tags = w.tags - "maxspeed") }) }
        assertNotNull(TurnMatcher.match(graph, request()))
        assertNull(TurnMatcher.match(graph, request())!!.approachSpeedLimitKph)
    }
    @Test fun straightApproachMayChangeStreetNameBeforeTheTurn() {
        val graph=base().let{it.copy(ways=it.ways.map{w->if(w.id==11L)w.copy(tags=w.tags+mapOf("name" to "Cedar Drive"))else w})}
        val result=TurnMatcher.match(graph,request())!!
        assertEquals(.001,result.junction.longitude,0.0)
        assertTrue(result.points.any{it.longitude==.0005})
        assertEquals(40.2336,result.approachSpeedLimitKph!!,1e-6)
    }
    @Test fun uniqueStraightCorridorCanCrossUnnamedConnectorAndMoreThanOneName() {
        val graph=TurnMatcher.Graph(mapOf(1L to GeoPoint(0.0,0.0),2L to GeoPoint(0.0,.0004),
            3L to GeoPoint(0.0,.0007),4L to GeoPoint(0.0,.001),5L to GeoPoint(-.001,.001)),listOf(
            way(10,listOf(1,2),"Pine Street"),way(11,listOf(2,3),""),
            way(12,listOf(3,4),"Cedar Drive"),way(20,listOf(4,5),"James Street")))
        val result=TurnMatcher.match(graph,request())!!
        assertEquals(.001,result.junction.longitude,0.0)
        assertEquals(listOf(.0001,.0004,.0007,.001),result.points.take(4).map{it.longitude})
    }
    @Test fun degreeTwoRoadCanBendAndChangeNameWithoutInventingATurnChoice() {
        val graph=TurnMatcher.Graph(mapOf(1L to GeoPoint(0.0,0.0),2L to GeoPoint(0.0,.0005),
            3L to GeoPoint(.00025,.00075),4L to GeoPoint(.0005,.001),5L to GeoPoint(-.0005,.002)),listOf(
            way(10,listOf(1,2),"Pine Street"),way(11,listOf(2,3,4),"Cedar Drive"),
            way(20,listOf(4,5),"James Street")))
        val result=TurnMatcher.match(graph,request(meters=123.0))!!
        assertEquals(.0005,result.junction.latitude,0.0)
        assertEquals(.001,result.junction.longitude,0.0)
    }
    @Test fun unrelatedPerpendicularSideStreetDoesNotBlockStraightNameChange() {
        val graph=base(listOf(way(12,listOf(2,6),"Side Street")),mapOf(6L to GeoPoint(.001,.0005)))
            .let{it.copy(ways=it.ways.map{w->if(w.id==11L)w.copy(tags=w.tags+mapOf("name" to "Cedar Drive"))else w})}
        assertNotNull(TurnMatcher.match(graph,request()))
    }
    @Test fun namedTurnConstraintCanSelectRenamedRoadAcrossForwardFork() {
        val graph=base(listOf(way(12,listOf(2,6),"Alder Road")),mapOf(6L to GeoPoint(.00015,.001)))
            .let{it.copy(ways=it.ways.map{w->if(w.id==11L)w.copy(tags=w.tags+mapOf("name" to "Cedar Drive"))else w})}
        assertNotNull(TurnMatcher.match(graph,request()))
    }
    @Test fun streetNameAloneDoesNotChooseButUniqueCompleteTurnPathCan() {
        val graph=base(listOf(way(12,listOf(2,6),"Alder Road")),mapOf(6L to GeoPoint(.00015,.001)))
        assertNotNull(TurnMatcher.match(graph,request()))
    }
    @Test fun duplicatePlausibleTargetJunctionsAfterNameChangeStillReject() {
        val graph=base(listOf(way(21,listOf(6,7),"James Street")),mapOf(6L to GeoPoint(0.0,.0014),7L to GeoPoint(-.001,.0014)))
            .let{it.copy(ways=it.ways.map{w->if(w.id==11L)w.copy(nodes=listOf(2,3,6,4),tags=w.tags+mapOf("name" to "Cedar Drive"))else w})}
        assertNull(TurnMatcher.match(graph,request(meters=120.0)))
    }
    @Test fun renamedApproachStillRespectsOneWayAndAccessRestrictions() {
        for(tags in listOf(mapOf("oneway" to "-1"),mapOf("access" to "private"),mapOf("motor_vehicle" to "no"))){
            val graph=base().let{it.copy(ways=it.ways.map{w->if(w.id==11L)w.copy(tags=w.tags+mapOf("name" to "Cedar Drive")+tags)else w})}
            assertNull(TurnMatcher.match(graph,request()))
        }
    }
    @Test fun targetStreetIsNotFollowedStraightToManufactureALaterTurn() {
        val graph=base().let{it.copy(ways=it.ways.map{w->if(w.id==11L)w.copy(tags=w.tags+mapOf("name" to "James Street"))else w})}
        assertNull(TurnMatcher.match(graph,request()))
    }
}
