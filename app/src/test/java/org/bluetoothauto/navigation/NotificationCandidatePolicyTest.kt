// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class NotificationCandidatePolicyTest {
    private fun point(east:Double=0.0,north:Double=0.0)=GeoPoint(47+north/111_195.08,
        -122+east/(111_195.08*cos(Math.toRadians(47.0))))
    private fun maneuver(begin:Int,end:Int,type:Int,road:String="Second St")=RoutePlan.Maneuver(begin,end,
        "Provider instruction","Provider speech",type,Double.NaN,listOf(road),emptyList(),emptyList())
    private fun route(junctionEast:Double=0.0, approach:Double=100.0, north:Double=120.0, type:Int=15,
        road:String="Second Street")=RoutePlan(listOf(point(-approach),point(junctionEast),point(junctionEast,north)),
        listOf(maneuver(0,1,1,"First St"),maneuver(1,2,type,road),maneuver(2,2,4)),100.0,"Target")
    private fun request(text:String="Turn left onto Second St",distance:Double=100.0,east:Double=-100.0,
        north:Double=0.0,bearing:Double=90.0)=TurnMatcher.Request(point(east,north),bearing,3.0,text,distance)
    private fun match(request:TurnMatcher.Request=request(),vararg routes:RoutePlan)=
        NotificationCandidatePolicy.match(request,routes.toList().ifEmpty{listOf(route())})
    private fun connector(length:Double=60.0,target:String="Second Street",nextType:Int=8,
        junctionEast:Double=0.0):RoutePlan {
        val points=listOf(point(-100.0),point(junctionEast),point(junctionEast,-length),
            if(nextType==8) point(junctionEast,-length-120.0) else point(junctionEast+120.0,-length))
        return RoutePlan(points,listOf(maneuver(0,1,1,"First St"),maneuver(1,2,10,""),
            maneuver(2,3,nextType,target),maneuver(3,3,4)),100.0,"Target")
    }
    private fun withSpans(route:RoutePlan,target:String="Second Street",connectorMode:String="drive",
        connectorUse:String="ramp",targetMode:String="drive",targetUse:String="road")=route.withRoadSpans(listOf(
        RoutePlan.RoadSpan(0,1,listOf("First St"),"drive","road"),
        RoutePlan.RoadSpan(1,2,emptyList(),connectorMode,connectorUse),
        RoutePlan.RoadSpan(2,3,listOf(target),targetMode,targetUse)))
    private fun longCurve(radius:Double=600.0,ramp:Boolean=true,subdivisions:Int=90):Pair<RoutePlan,GeoPoint> {
        val start=-Math.PI/4
        val curve=(0..subdivisions).map { step ->
            val angle=start+Math.toRadians(step*90.0/subdivisions)
            point(radius*(kotlin.math.sin(angle)-kotlin.math.sin(start)),
                radius*(kotlin.math.cos(angle)-kotlin.math.cos(start)))
        }
        val final=curve.last()
        val tail=GeoPoint(final.latitude-1500/kotlin.math.sqrt(2.0)/111_195.08,
            final.longitude+1500/kotlin.math.sqrt(2.0)/(111_195.08*cos(Math.toRadians(47.0))))
        val points=listOf(point(north=-100.0))+curve+tail
        return RoutePlan(points,listOf(maneuver(0,1,1,"First St"),
            maneuver(1,points.lastIndex,if(ramp)18 else 10,if(ramp)"I 5 North"else"Second Street"),
            maneuver(points.lastIndex,points.lastIndex,4)),100.0,"Target") to final
    }

    @Test fun namedCurrentTurnUsesActualCandidateJunctionAndPath() {
        val result=match()!!
        assertEquals("candidate-constrained",result.confidence)
        assertTrue(result.junction.distanceTo(point())<.01)
        assertTrue(result.points.last().distanceTo(point(north=120.0))<.1)
        assertTrue(result.key.startsWith("current-candidate:"))
    }
    @Test fun freshFixCanMoveAlongCachedInitialLegWithoutRefetchingOrigin() {
        assertNotNull(match(request(distance=40.0,east=-40.0)))
        assertNull(match(request(distance=100.0,east=-40.0)))
    }
    @Test fun currentPointAndHeadingMustAgreeWithActualInitialCorridor() {
        assertNull(match(request(north=30.0)))
        assertNull(match(request(bearing=270.0)))
        assertNull(match(request().copy(accuracy=26.0)))
        assertNull(match(request().copy(bearing=Double.NaN)))
    }
    @Test fun sideNeedsProviderTypeAndActualGeometryAgreement() {
        assertNull(match(request("Turn right onto Second St")))
        assertNull(match(request(),route(north=-120.0,type=15)))
        assertNotNull(match(request("Turn right onto Second St"),route(north=-120.0,type=10)))
    }
    @Test fun explicitNamesAndDirectionalReferencesCannotBeFuzzyMatched() {
        assertNull(match(request("Turn left onto Second Avenue")))
        assertNull(match(request("Turn left onto WA-18 E"),route(road="WA-18 W")))
        assertNotNull(match(request("Turn left onto WA-18"),route(road="Washington State Hwy 18")))
    }
    @Test fun distanceMismatchAndUnannouncedEarlierTurnRejectCandidate() {
        assertNull(match(request(distance=300.0)))
        val route=RoutePlan(listOf(point(-100.0),point(),point(north=100.0),point(100.0,100.0)),listOf(
            maneuver(0,1,1),maneuver(1,2,15,"Old St"),maneuver(2,3,10),maneuver(3,3,4)),100.0,"Target")
        assertNull(match(request("Turn right onto Second St",200.0),route))
    }
    @Test fun harmlessStraightRenameBeforeCurrentTurnMayRemainOnInitialCorridor() {
        val route=RoutePlan(listOf(point(-100.0),point(-50.0),point(),point(north=100.0)),listOf(
            maneuver(0,1,1),maneuver(1,2,8,"Renamed St"),maneuver(2,3,15),maneuver(3,3,4)),100.0,"Target")
        assertNotNull(match(request(),route))
    }
    @Test fun twoDifferentPlausibleJunctionsRemainUnknown() {
        assertNull(match(request(distance=110.0),route(),route(junctionEast=30.0)))
    }
    @Test fun equivalentAlternativePathsDeduplicateButCandidateCountIsBounded() {
        assertNotNull(NotificationCandidatePolicy.match(request(),List(12){route()}))
        assertNull(NotificationCandidatePolicy.match(request(),List(13){route()}))
        assertNull(NotificationCandidatePolicy.match(request(),emptyList()))
    }
    @Test fun equivalentDifferentVertexDensityHasStableChoiceRegardlessCandidateOrder() {
        val base=route()
        val dense=RoutePlan(listOf(point(-100.0),point(-50.0),point(),point(north=60.0),point(north=120.0)),listOf(
            maneuver(0,2,1),maneuver(2,4,15),maneuver(4,4,4)),100.0,"Target")
        assertEquals(match(request(),base,dense)!!.key,match(request(),dense,base)!!.key)
    }
    @Test fun FarCurrentCueCropsOnlyActualLast1000MetersAndFirst500Meters() {
        val route=route(approach=2_000.0,north=800.0).withRoadMetadata(doubleArrayOf(90.0,10.0),doubleArrayOf(40.0,30.0))
        val result=match(request(distance=2_000.0,east=-2_000.0),route)!!
        assertTrue(result.points.first().distanceTo(point(-1000.0))<.1)
        assertTrue(result.points.last().distanceTo(point(north=500.0))<.1)
        assertEquals(40.0,result.approachSpeedLimitKph!!,0.0)
    }
    @Test fun LongDistanceToleranceHasAnAbsoluteBound() {
        assertNull(match(request(distance=50_000.0,east=-30_000.0),route(approach=30_000.0)))
        assertNull(match(request(distance=50_001.0)))
        assertNull(match(request(distance=Double.NaN)))
    }
    @Test fun LongerRetainedContextStillRejectsMoreThan512ExactPathPoints() {
        val points=(-2000..0).map { point(it.toDouble()) } + (1..800).map { point(north=it.toDouble()) }
        val dense=RoutePlan(points,listOf(maneuver(0,2000,1,"First St"),
            maneuver(2000,points.lastIndex,15),maneuver(points.lastIndex,points.lastIndex,4)),100.0,"Target")
        assertNull(match(request(distance=2000.0,east=-2000.0),dense))
    }
    @Test fun longHighwayRampRetainsTheCompleteProviderCurvePast500Meters() {
        val (route,lastCurve)=longCurve()
        val result=match(request("Use the right lane to take the I-5 N ramp toward Test City",
            distance=100.0,east=0.0,north=-100.0,bearing=0.0),route)!!
        assertTrue(result.points.any { it.distanceTo(lastCurve)<.01 })
        assertTrue(result.points.last().distanceTo(lastCurve)>150)
        assertTrue(result.points.size<=512)
    }
    @Test fun denseOptionalRampExtensionKeepsTheFormerExactWindowInsteadOfLosingTheMap() {
        val (route,lastCurve)=longCurve(subdivisions=900)
        val junctionAlong=route.cumulativeMeters[1]
        val exactBaseline=route.points.indices.filter {
            route.cumulativeMeters[it] < junctionAlong+500
        }.map { route.points[it] }
        assertTrue(exactBaseline.size in 400..510)
        assertTrue(route.points.size>512)
        val result=match(request("Take the I-5 N ramp on the right",distance=100.0,
            east=0.0,north=-100.0,bearing=0.0),route)!!
        assertTrue(result.points.size<=512)
        assertEquals(exactBaseline,result.points.dropLast(1))
        val junction=result.points.indexOfFirst { it.distanceTo(result.junction)<.01 }
        assertEquals(500.0,result.points.drop(junction).zipWithNext { a,b->a.distanceTo(b) }.sum(),.1)
        assertTrue(result.points.none { it.distanceTo(lastCurve)<.01 })
    }
    @Test fun denseRampStillRejectsWhenTheFormerWindowItselfExceedsThePointBudget() {
        val (route,_)=longCurve(subdivisions=1200)
        assertNull(match(request("Take the I-5 N ramp on the right",distance=100.0,
            east=0.0,north=-100.0,bearing=0.0),route))
    }
    @Test fun aLongRampCannotExpandBeyondTwoKilometresOfKnownDeparture() {
        val (route,_)=longCurve(radius=2000.0)
        val result=match(request("Use the right lane to take the I-5 N ramp toward Test City",
            distance=100.0,east=0.0,north=-100.0,bearing=0.0),route)!!
        val junction=result.points.indexOfFirst { it.distanceTo(result.junction)<.01 }
        val distance=result.points.drop(junction).zipWithNext { a,b->a.distanceTo(b) }.sum()
        assertEquals(2000.0,distance,.1)
    }
    @Test fun aStraightHighwayDepartureKeepsTheCompact500MeterContext() {
        val points=listOf(point(north=-100.0),point(),point(3000.0,3000.0))
        val route=RoutePlan(points,listOf(maneuver(0,1,1,"First St"),maneuver(1,2,18,"I 5 North"),
            maneuver(2,2,4)),100.0,"Target")
        val result=match(request("Take the I-5 N ramp on the right",distance=100.0,
            east=0.0,north=-100.0,bearing=0.0),route)!!
        assertEquals(500.0,result.points.last().distanceTo(result.junction),.1)
    }
    @Test fun longRampGeometryStillStopsBeforeTheNextUnannouncedDecision() {
        val (base,_)=longCurve()
        val decision=50
        val route=RoutePlan(base.points,listOf(maneuver(0,1,1,"First St"),maneuver(1,decision,18,"I 5 North"),
            maneuver(decision,base.points.lastIndex,10,"Other Street"),maneuver(base.points.lastIndex,base.points.lastIndex,4)),100.0,"Target")
        val result=match(request("Use the right lane to take the I-5 N ramp toward Test City",
            distance=100.0,east=0.0,north=-100.0,bearing=0.0),route)!!
        assertTrue(result.points.last().distanceTo(base.points[decision])<.01)
    }
    @Test fun ordinaryStreetTurnGeometryKeepsIts500MeterDepartureBound() {
        val (route,lastCurve)=longCurve(ramp=false)
        val result=match(request("Turn right onto Second Street",distance=100.0,east=0.0,
            north=-100.0,bearing=0.0),route)!!
        assertTrue(result.points.none { it.distanceTo(lastCurve)<.01 })
        val junction=result.points.indexOfFirst { it.distanceTo(result.junction)<.01 }
        assertEquals(500.0,result.points.drop(junction).zipWithNext { a,b->a.distanceTo(b) }.sum(),.1)
    }
    @Test fun departureEndsAtNextMeaningfulIntersectionWithoutDrawingItsUnannouncedTurn() {
        val route=RoutePlan(listOf(point(-100.0),point(),point(north=200.0),point(200.0,200.0)),listOf(
            maneuver(0,1,1,"First St"),maneuver(1,2,15),maneuver(2,3,10,"Third St"),maneuver(3,3,4)),100.0,"Target")
        val result=match(request(),route)!!
        assertTrue(result.points.last().distanceTo(point(north=200.0))<.1)
        assertTrue(result.points.none { it.longitude>point().longitude+1e-7 })
    }
    @Test fun alternativeFutureBranchesDoNotMakeTheSameCurrentTurnAmbiguous() {
        fun branch(east:Double,type:Int)=RoutePlan(listOf(point(-100.0),point(),point(north=200.0),point(east,200.0)),listOf(
            maneuver(0,1,1,"First St"),maneuver(1,2,15),maneuver(2,3,type,"Third St"),maneuver(3,3,4)),100.0,"Target")
        val left=branch(-200.0,15);val right=branch(200.0,10)
        val result=match(request(),left,right)!!
        assertEquals(result.key,match(request(),right,left)!!.key)
        assertTrue(result.points.last().distanceTo(point(north=200.0))<.1)
    }
    @Test fun differentKnownHorizonsPublishOnlyTheSharedOrderedDeparture() {
        val short=RoutePlan(listOf(point(-100.0),point(),point(north=200.0),point(200.0,200.0)),listOf(
            maneuver(0,1,1,"First St"),maneuver(1,2,15),maneuver(2,3,10,"Third St"),maneuver(3,3,4)),100.0,"Target")
        val long=route(north=800.0)
        val result=match(request(),long,short)!!
        assertTrue(result.points.last().distanceTo(point(north=200.0))<.1)
        assertEquals(result.key,match(request(),short,long)!!.key)
    }
    @Test fun departureBranchBeforeSharedHorizonCannotBeDeduplicated() {
        val curved=RoutePlan(listOf(point(-100.0),point(),point(30.0,100.0),point(north=200.0)),listOf(
            maneuver(0,1,1,"First St"),maneuver(1,3,15),maneuver(3,3,4)),100.0,"Target")
        assertNull(match(request(),route(north=500.0),curved))
    }
    @Test fun sameSpatialLoopSegmentsInDifferentOrderRemainAmbiguous() {
        fun loopy(leftFirst:Boolean):RoutePlan {
            val center=point(north=80.0)
            fun loop(east:Double)=listOf(point(east,80.0),point(east,120.0),point(north=120.0),center)
            val points=listOf(point(-100.0),point(),point(north=40.0),center)+
                (if(leftFirst)loop(-40.0)+loop(40.0)else loop(40.0)+loop(-40.0))+point(north=160.0)
            return RoutePlan(points,listOf(maneuver(0,1,1,"First St"),maneuver(1,points.lastIndex,15),
                maneuver(points.lastIndex,points.lastIndex,4)),100.0,"Target")
        }
        assertNull(match(request(),loopy(true),loopy(false)))
    }
    @Test fun noProviderRoadLabelsCannotBeReplacedWithInstructionGuessing() {
        val route=RoutePlan(listOf(point(-100.0),point(),point(north=100.0)),listOf(maneuver(0,1,1),
            RoutePlan.Maneuver(1,2,"Turn left onto Second St","",15),maneuver(2,2,4)),100.0,"Target")
        assertNull(match(request(),route))
    }
    @Test fun exitsRampsAndMergeHaveDistinctProviderConstraints() {
        assertNotNull(match(request("Exit left onto Second St"),route(type=21)))
        assertNull(match(request("Exit right onto Second St"),route(type=21)))
        assertNotNull(match(request("Take the ramp on the left to Second St"),route(type=19)))
        assertNotNull(match(request("Merge left onto Second St"),route(type=25)))
    }
    @Test fun aProviderKeepForkCanBeTheSameNotifiedDirectionalExit() {
        assertNotNull(match(request("Use the right lane to take the I-5 N exit toward Test City"),
            route(north=-120.0,type=23,road="I 5 North")))
        assertNotNull(match(request("Use the left lane to take the I-5 N ramp toward Test City"),
            route(type=24,road="I 5 North")))
        assertNotNull(match(request("Take the I-5 N exit toward Test City"),
            route(north=-120.0,type=23,road="I 5 North")))
        assertNotNull(match(request("Take the I-5 N ramp toward Test City"),
            route(type=24,road="I 5 North")))
    }
    @Test fun forkAliasStillRequiresActualSideRoadDirectionAndDistance() {
        val cue="Use the right lane to take the I-5 N exit toward Test City"
        assertNull(match(request(cue),route(north=-120.0,type=24,road="I 5 North")))
        assertNull(match(request(cue),route(north=120.0,type=23,road="I 5 North")))
        assertNull(match(request(cue),route(north=-120.0,type=23,road="I 5 South")))
        assertNull(match(request(cue,distance=300.0),route(north=-120.0,type=23,road="I 5 North")))
    }
    @Test fun straightNamedTransitionNeedsStraightActualGeometry() {
        val route=RoutePlan(listOf(point(-100.0),point(),point(100.0)),listOf(
            maneuver(0,1,1),maneuver(1,2,8),maneuver(2,2,4)),100.0,"Target")
        assertNotNull(match(request("Continue onto Second St"),route))
        assertNull(match(request("Continue onto Second St"),route(type=8)))
    }
    @Test fun noExtrapolatedTurnAtArrivalOrAlreadyPassedJunction() {
        assertNull(match(request(east=0.0,north=20.0,bearing=0.0,distance=10.0)))
        val route=RoutePlan(listOf(point(-100.0),point(-50.0),point()),listOf(
            maneuver(0,2,1),maneuver(2,2,15)),100.0,"Target")
        assertNull(match(request(),route))
    }
    @Test fun googleTurnOntoRampCanUseSameSidedValhallaRampClassification() {
        assertNotNull(match(request("Turn right onto State Highway 18 ramp"),route(north=-120.0,type=18,road="SR 18")))
        assertNotNull(match(request("Turn right onto State Highway 18"),route(north=-120.0,type=18,road="SR 18")))
        assertNull(match(request("Turn left onto State Highway 18 ramp"),route(north=-120.0,type=18,road="SR 18")))
    }
    @Test fun laneTurnStreetAliasCannotHideAnExplicitlyOppositeRouteReference() {
        val cue=request("Use the left 2 lanes to turn left onto WA-516 W/SE 272nd St")
        assertNotNull(match(cue,route(road="Southeast 272nd Street")))
        assertNotNull(match(cue,route(road="Washington State Hwy 516")))
        val opposite=RoutePlan(listOf(point(-100.0),point(),point(north=120.0)),listOf(
            maneuver(0,1,1),RoutePlan.Maneuver(1,2,"Provider instruction","",15,Double.NaN,
                listOf("Southeast 272nd Street","WA 516 E"),emptyList(),emptyList()),maneuver(2,2,4)),100.0,"Target")
        assertNull(match(cue,opposite))
        assertNull(match(cue,route(north=-120.0,type=10,road="Southeast 272nd Street")))
    }
    @Test fun towardMayEnterUnnamedConnectorWhenActualTargetFollowsWithoutAnotherTurn() {
        val result=match(request("Turn right toward Second St"),connector())!!
        assertEquals("candidate-constrained",result.confidence)
        assertTrue(result.junction.distanceTo(point())<.01)
        assertTrue(result.junction.distanceTo(point(north=-60.0))>59.0)
        assertTrue(result.points.last().distanceTo(point(north=-180.0))<.1)
        assertNull(match(request("Turn right onto Second St"),connector()))
    }
    @Test fun towardCanUseExactDriveRoadSpanPartitionForUnnamedConnector() {
        assertNotNull(match(request("Turn right toward Second St"),withSpans(connector(target=""))))
    }
    @Test fun towardTargetMustBeReachedWithin300MetersAfterActualFirstJunction() {
        assertNotNull(match(request("Turn right toward Second St"),connector(length=299.0)))
        assertNull(match(request("Turn right toward Second St"),connector(length=301.0)))
        assertNotNull(match(request("Turn right toward Second St"),withSpans(connector(length=299.0,target=""))))
        assertNull(match(request("Turn right toward Second St"),withSpans(connector(length=301.0,target=""))))
    }
    @Test fun towardCannotSkipAnotherMeaningfulTurnEvenAtTheNamedTargetBoundary() {
        assertNull(match(request("Turn right toward Second St"),connector(nextType=15)))
        assertNull(match(request("Turn right toward Second St"),withSpans(connector(nextType=15,target=""))))
    }
    @Test fun towardCannotUseOnlySignTextAsEvidenceThatTheRouteReachesTarget() {
        val base=connector(target="")
        val signOnly=RoutePlan(base.points,base.maneuvers.mapIndexed { index,step ->
            if(index==1) RoutePlan.Maneuver(step.beginIndex,step.endIndex,step.instruction,"",step.type,
                Double.NaN,emptyList(),emptyList(),listOf("Second Street")) else step
        },100.0,"Target")
        assertNull(match(request("Turn right toward Second St"),signOnly))
    }
    @Test fun towardExactSpansCannotBeOverriddenByContradictoryManeuverNames() {
        assertNull(match(request("Turn right toward Second St"),withSpans(connector(),target="Other Road")))
    }
    @Test fun towardRequiresDrivableTargetAndConnectorWhenSpansExist() {
        val cue=request("Turn right toward Second St")
        assertNull(match(cue,withSpans(connector(),connectorMode="pedestrian")))
        assertNull(match(cue,withSpans(connector(),connectorUse="footway")))
        assertNull(match(cue,withSpans(connector(),targetMode="bicycle")))
        assertNull(match(cue,withSpans(connector(),targetUse="path")))
    }
    @Test fun towardStillRequiresTargetReferenceDirectionAndCurrentTurnSide() {
        val east=connector(target="WA 18 E")
        assertNotNull(match(request("Turn right toward WA-18 E"),east))
        assertNull(match(request("Turn right toward WA-18 W"),east))
        assertNull(match(request("Turn left toward WA-18 E"),east))
    }
    @Test fun towardStillRequiresCurrentOriginCourseAndDistance() {
        assertNull(match(request("Turn right toward Second St",north=30.0),connector()))
        assertNull(match(request("Turn right toward Second St",bearing=270.0),connector()))
        assertNull(match(request("Turn right toward Second St",distance=300.0),connector()))
    }
    @Test fun towardDifferentPlausibleConnectorJunctionsRemainAmbiguous() {
        assertNull(match(request("Turn right toward Second St",distance=110.0),connector(),connector(junctionEast=30.0)))
    }
    @Test fun towardAndOntoWithSameNamedDepartureHaveDistinctCacheKeys() {
        val same=route(north=-120.0,type=10)
        val onto=match(request("Turn right onto Second St"),same)!!
        val toward=match(request("Turn right toward Second St"),same)!!
        assertNotEquals(onto.key,toward.key)
        assertTrue(onto.junction.distanceTo(toward.junction)<.01)
    }
    @Test fun targetNamedOnlyOnIncomingSpanCannotProveTowardDeparture() {
        val incoming=connector(target="").withRoadSpans(listOf(
            RoutePlan.RoadSpan(0,1,listOf("Second Street"),"drive","road"),
            RoutePlan.RoadSpan(1,3,emptyList(),"drive","road")))
        assertNull(match(request("Turn right toward Second St"),incoming))
    }
    @Test fun towardCannotTreatAnUnrelatedNamedDetourAsAnUnnamedConnector() {
        val cue=request("Turn right toward Second St")
        val base=connector()
        val named=RoutePlan(base.points,base.maneuvers.mapIndexed { index,step ->
            if(index==1) maneuver(step.beginIndex,step.endIndex,step.type,"Other Road") else step
        },100.0,"Target")
        assertNull(match(cue,named))
        val namedSpan=base.withRoadSpans(listOf(
            RoutePlan.RoadSpan(0,1,listOf("First St"),"drive","road"),
            RoutePlan.RoadSpan(1,2,listOf("Other Road"),"drive","service_road"),
            RoutePlan.RoadSpan(2,3,listOf("Second Street"),"drive","road")))
        assertNull(match(cue,namedSpan))
        val renamed=RoutePlan(listOf(point(-100.0),point(),point(north=-30.0),point(north=-60.0),
            point(north=-180.0)),listOf(maneuver(0,1,1,"First St"),maneuver(1,2,10,""),
            maneuver(2,3,8,"Other Road"),maneuver(3,4,8),maneuver(4,4,4)),100.0,"Target")
        assertNull(match(cue,renamed))
    }
}
