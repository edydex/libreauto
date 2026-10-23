// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test

class NotificationRouteResolverTest {
    private val origin = GeoPoint(0.0, 0.0)
    private fun request() = TurnMatcher.Request(origin, 90.0, 5.0, "Turn right onto James Street", 111.2)
    private fun roadPlace(name: String = "James Street", longitude: Double = .001) =
        OwnMapProvider.Place("Fixture destination", GeoPoint(-.001, longitude), listOf(name))
    private fun route(road: String = "James Street") = RoutePlan(listOf(origin, GeoPoint(0.0, .001), GeoPoint(-.001, .001)), listOf(
        RoutePlan.Maneuver(0, 1, "Head east", "", 1),
        RoutePlan.Maneuver(1, 2, "Turn right onto $road", "", 10, 10.0, listOf(road), emptyList(), emptyList()),
        RoutePlan.Maneuver(2, 2, "Your destination is on the right", "", 5)), 20.0, "Fixture")
    private class Fake : NotificationRouteResolver.Source {
        var searches = 0; var routes = 0; var cancels = 0
        var places = listOf(OwnMapProvider.Place("Fixture", GeoPoint(-.001, .001), listOf("James Street")))
        var choices: List<RoutePlan> = emptyList()
        var onSearch: () -> Unit = {}; var onRoute: () -> Unit = {}
        val budgets = arrayListOf<Long>()
        val queries = arrayListOf<String>()
        val routedDestinations = arrayListOf<GeoPoint>()
        val routeOrigins = arrayListOf<GeoPoint>()
        val nearbyAreas = arrayListOf<NotificationRouteResolver.NearbyArea>()
        override fun search(query: String, nearby: NotificationRouteResolver.NearbyArea, maximumMillis: Long): List<OwnMapProvider.Place> {
            nearbyAreas.add(nearby)
            searches++; queries.add(query); budgets.add(maximumMillis); onSearch(); return places
        }
        override fun routes(origin: GeoPoint, destination: OwnMapProvider.Place, bearing: Float, maximumMillis: Long): List<RoutePlan> {
            routes++; routeOrigins.add(origin); routedDestinations.add(destination.point); budgets.add(maximumMillis); onRoute(); return choices
        }
        override fun cancel() { cancels++ }
    }
    @Test fun unsupportedCueNeverMakesANetworkRequest() {
        val source = Fake(); val resolver = NotificationRouteResolver(source) { 100_000 }
        assertNull(resolver.resolve(request().copy(instruction = "Finding GPS")))
        assertEquals(0, source.searches)
    }
    @Test fun exactRoadResultProducesConstrainedGeometryAndReusesCandidateCache() {
        val source = Fake().apply { choices = listOf(route()) }; var clock = 100_000L
        val resolver = NotificationRouteResolver(source) { clock }
        assertEquals("candidate-constrained", resolver.resolve(request())!!.confidence)
        clock += 1000
        assertNotNull(resolver.resolve(request()))
        assertEquals(1, source.searches); assertEquals(1, source.routes)
        assertEquals(listOf("James Street"), source.queries)
        assertEquals(1L, resolver.diagnostics().cacheHits)
    }
    @Test fun unrelatedSearchResultNameCannotAuthorizeRouting() {
        val source = Fake().apply { places = listOf(roadPlace("Jameson Street")); choices = listOf(route()) }
        val resolver = NotificationRouteResolver(source) { 100_000 }
        assertNull(resolver.resolve(request()))
        assertEquals(0, source.routes)
    }
    @Test fun omittedStreetOrdinalCanProposeOnlyAnIndependentlyMatchingRoute() {
        val source=Fake().apply {
            places=listOf(roadPlace("Southeast 256th Street"))
            choices=listOf(route("Southeast 256th Street"))
        }
        val cue=request().copy(instruction="Use any lane to turn right onto SE 256 St")
        assertNotNull(NotificationRouteResolver(source){100_000}.resolve(cue))
        assertEquals(1,source.searches);assertEquals(1,source.routes)
        val wrong=Fake().apply {
            places=source.places;choices=listOf(route("Southeast 257th Street"))
        }
        val wrongResolver=NotificationRouteResolver(wrong){100_000}
        assertNull(wrongResolver.resolve(cue))
        assertEquals("NO_MATCH",wrongResolver.diagnostics().status)
    }
    @Test fun expandedStreetSpellingCanFinishWithoutAnotherFuzzySearch() {
        val source = Fake().apply { places = emptyList(); choices = listOf(route("Southeast 272nd Street")) }
        source.onSearch = { source.places = if (source.queries.last().startsWith("Southeast"))
            listOf(roadPlace("Southeast 272nd Street")) else emptyList() }
        val resolver = NotificationRouteResolver(source) { 100_000 }
        assertNotNull(resolver.resolve(request().copy(instruction = "Turn right onto SE 272nd St")))
        assertEquals(listOf("Southeast 272nd Street"), source.queries)
        assertEquals(1, source.searches); assertEquals(1, source.routes)
    }
    @Test fun allLastBatchDestinationsAreComparedEvenWhenTheirFirstResponseMatches() {
        val source = Fake().apply { places = listOf(roadPlace(longitude = .001), roadPlace(longitude = .003), roadPlace(longitude = .005), roadPlace(longitude = .007), roadPlace(longitude = .009)); choices = listOf(route()) }
        val resolver = NotificationRouteResolver(source) { 100_000 }
        assertNotNull(resolver.resolve(request()))
        assertEquals(4, source.routes)
    }
    @Test fun unmatchedEndpointsStillCannotExceedFourDistinctRouteRequests() {
        val source=Fake().apply {
            places=listOf(.001,.003,.005,.007,.009).map { roadPlace(longitude=it) }
            choices=listOf(route("Other Street"))
        }
        assertNull(NotificationRouteResolver(source){100_000}.resolve(request()))
        assertEquals(4,source.routes)
    }
    @Test fun conflictingAlternativesInOneCompletedEndpointNeverPublishTheFirstMatch() {
        val other=RoutePlan(listOf(origin,GeoPoint(0.0,.0012),GeoPoint(-.001,.0012)),listOf(
            RoutePlan.Maneuver(0,1,"Head east","",1),
            RoutePlan.Maneuver(1,2,"Turn right onto James Street","",10,10.0,listOf("James Street"),emptyList(),emptyList()),
            RoutePlan.Maneuver(2,2,"Arrive","",5)),20.0,"Fixture")
        val source=Fake().apply { choices=listOf(route(),other) }
        assertNull(NotificationRouteResolver(source){100_000}.resolve(request()))
        assertEquals(1,source.routes)
    }
    @Test fun originalWordingRemainsTheOnlyFallbackAfterPreferredStreetSpellingMisses() {
        val source=Fake().apply {choices=listOf(route("Southeast 272nd Street"))}
        source.onSearch={source.places=if(source.searches==1)emptyList()else listOf(roadPlace("Southeast 272nd Street"))}
        assertNotNull(NotificationRouteResolver(source){100_000}.resolve(
            request().copy(instruction="Turn right onto SE 272nd St")))
        assertEquals(listOf("Southeast 272nd Street","SE 272nd St"),source.queries)
        assertEquals(1,source.routes)
    }
    @Test fun expandedSearchPreservesTheOriginalNumberSpellingAndHighwayReferences() {
        assertEquals("Southeast 256 Street",NotificationRouteResolver.expandedRoadName("SE 256 St"))
        assertEquals("Southeast 256th Street",NotificationRouteResolver.expandedRoadName("SE 256th St"))
        assertEquals("Washington State Route 256",NotificationRouteResolver.expandedRoadName("WA 256"))
        assertEquals("Interstate 5 North",NotificationRouteResolver.expandedRoadName("I-5 N"))
    }
    @Test fun distantStreetCentroidCanProposeButCannotMoveTheActualNotifiedTurn() {
        val far = GeoPoint(-.04, .001)
        val source = Fake().apply {
            places = listOf(OwnMapProvider.Place("Fixture street", far, listOf("James Street")))
            choices = listOf(RoutePlan(listOf(origin, GeoPoint(0.0, .001), GeoPoint(-.001, .001), far), listOf(
                RoutePlan.Maneuver(0, 1, "Head east", "", 1),
                RoutePlan.Maneuver(1, 3, "Turn right onto James Street", "", 10, 10.0, listOf("James Street"), emptyList(), emptyList()),
                RoutePlan.Maneuver(3, 3, "Your destination is on the right", "", 5)), 200.0, "Fixture"))
        }
        val resolver = NotificationRouteResolver(source) { 100_000 }
        val geometry = resolver.resolve(request())!!
        assertEquals(0.0, geometry.junction.latitude, 0.0)
        assertEquals(.001, geometry.junction.longitude, 0.0)
    }
    @Test fun secondEndpointCanReachNotifiedSideWithoutAcceptingAnUnprovedTurn() {
        val nearestLeft = GeoPoint(.001, .001)
        val secondLeft = GeoPoint(.002, .001)
        val fartherRight = GeoPoint(-.004, .001)
        val source = Fake().apply {
            places = listOf(nearestLeft, secondLeft, fartherRight).map {
                OwnMapProvider.Place("Fixture street", it, listOf("James Street"))
            }
            // Endpoint ranking cannot make this conflicting road a valid turn.
            choices = listOf(route("Other Street"))
        }
        val resolver = NotificationRouteResolver(source) { 100_000 }
        assertNull(resolver.resolve(request()))
        assertTrue(source.routedDestinations.containsAll(listOf(nearestLeft,fartherRight)))
        assertEquals(3, source.routes)
        assertEquals("NO_MATCH", resolver.diagnostics().status)
    }
    @Test fun deadlineIsSharedAcrossSearchAndBothRouteCalls() {
        var clock = 100_000L
        val source = Fake().apply { choices = listOf(route()); onSearch = { clock += 24_500 }; onRoute = { clock += 600 } }
        val resolver = NotificationRouteResolver(source) { clock }
        assertNull(resolver.resolve(request()))
        assertEquals(listOf(25_000L, 500L), source.budgets)
        assertEquals("TIMEOUT", resolver.diagnostics().status)
    }
    @Test fun newCueCancellationCannotPublishOrCacheAnInflightCandidate() {
        val source = Fake().apply { choices = listOf(route()) }
        val resolver = NotificationRouteResolver(source) { 100_000 }
        source.onRoute = { resolver.cancel() }
        try { resolver.resolve(request()); fail("Cancelled route must not return") } catch (_: InterruptedException) { }
        assertEquals(1, source.cancels)
        assertEquals(0L, resolver.diagnostics().cacheHits)
    }
    @Test fun repeatedMissesRespectThirtySecondNetworkBackoff() {
        var clock = 100_000L
        val source = Fake().apply { places = emptyList() }
        val resolver = NotificationRouteResolver(source) { clock }
        assertNull(resolver.resolve(request())); clock += 29_999
        assertNull(resolver.resolve(request())); assertEquals(1, source.searches)
        clock++
        assertNull(resolver.resolve(request())); assertEquals(2, source.searches)
    }
    @Test fun farCueAdmissionRemainsBoundedAndRequiresExplicitNavigation() {
        assertTrue(NotificationRouteResolver.supports("Turn right onto James Street", 40_000.0))
        assertFalse(NotificationRouteResolver.supports("Turn right onto James Street", 50_001.0))
        assertFalse(NotificationRouteResolver.supports("Searching for GPS", 100.0))
        assertFalse(NotificationRouteResolver.supports("Turn right onto James Street", Double.NaN))
    }
    @Test fun searchReceivesOnlyRoundedAreaWhileRouteKeepsTheActualFix() {
        val source = Fake().apply {
            places = listOf(OwnMapProvider.Place("Fixture street", GeoPoint(40.124, -73.455), listOf("James Street")))
            choices = listOf(route())
        }
        val precise = GeoPoint(40.123456, -73.456789)
        NotificationRouteResolver(source) { 100_000 }.resolve(request().copy(point = precise))
        assertEquals(40.12, source.nearbyAreas.single().latitude, 0.0)
        assertEquals(-73.46, source.nearbyAreas.single().longitude, 0.0)
        assertEquals(1, source.routes)
        assertSame(precise, source.routeOrigins.single())
        assertNotEquals(precise.latitude, source.nearbyAreas.single().latitude, 0.0)
        assertNotEquals(precise.longitude, source.nearbyAreas.single().longitude, 0.0)
    }
    @Test fun roundedAreaRemainsValidAtTheGeographicEdges() {
        val area = NotificationRouteResolver.NearbyArea.fromPoint(GeoPoint(89.99999, -179.99999))
        assertEquals(90.0, area.latitude, 0.0)
        assertEquals(-180.0, area.longitude, 0.0)
    }
    @Test fun combinedRoadAliasSearchesTheStreetButPreservesTheActualTurnSide() {
        val source=Fake().apply { places=listOf(roadPlace("SE 272nd St")); choices=listOf(route("SE 272nd St")) }
        val resolver=NotificationRouteResolver(source) { 100_000 }
        assertNull(resolver.resolve(request().copy(instruction="Use the left 2 lanes to turn left onto WA-516 W/SE 272nd St")))
        assertEquals(listOf("Southeast 272nd Street","SE 272nd St"),source.queries)
        assertEquals("NO_MATCH",resolver.diagnostics().status) // Provider geometry is right, Google said left.
    }
    @Test fun rejectedFirstStreetRepresentativeCanUseOneExpandedSearchAndSecondEndpoint() {
        val first=OwnMapProvider.Place("Fixture street",GeoPoint(-.004,.001),listOf("SE 272nd St"))
        val second=OwnMapProvider.Place("Fixture street",GeoPoint(-.001,.001),listOf("Southeast 272nd Street"))
        val source=Fake()
        source.onSearch={source.places=if(source.searches==1)listOf(first)else listOf(second)}
        source.onRoute={source.choices=listOf(route(if(source.routes==1)"Other Street"else "SE 272nd St"))}
        val result=NotificationRouteResolver(source) { 100_000 }.resolve(request().copy(instruction="Turn right onto SE 272nd St"))
        assertNotNull(result)
        assertEquals(listOf("Southeast 272nd Street","SE 272nd St"),source.queries)
        assertEquals(listOf(first.point,second.point),source.routedDestinations)
        assertEquals(2,source.searches);assertEquals(2,source.routes)
        assertEquals(4,source.budgets.size)
        assertTrue(source.budgets.all{it in 1..25_000})
    }
    @Test fun aLaterLastBatchEndpointCanRevealAConflictingActualJunction() {
        val source=Fake()
        source.onSearch={source.places=if(source.searches==1)listOf(roadPlace("SE 272nd St",.004))else
            listOf(.001,.006,.009).map {roadPlace("Southeast 272nd Street",it)}}
        source.onRoute={
            source.choices=listOf(when(source.routes) {
                1,4 -> route("Other Street")
                2 -> route("SE 272nd St")
                else -> RoutePlan(listOf(origin,GeoPoint(0.0,.0012),GeoPoint(-.001,.0012)),listOf(
                    RoutePlan.Maneuver(0,1,"Head east","",1),
                    RoutePlan.Maneuver(1,2,"Turn right onto SE 272nd St","",10,10.0,listOf("SE 272nd St"),emptyList(),emptyList()),
                    RoutePlan.Maneuver(2,2,"Arrive","",5)),20.0,"Fixture")
            })
        }
        assertNull(NotificationRouteResolver(source){100_000}.resolve(
            request().copy(instruction="Turn right onto SE 272nd St")))
        assertEquals(2,source.searches);assertEquals(4,source.routes)
    }
    @Test fun aSearchMemoReusesOnlyProposalsAndStillRoutesFromTheFreshActualFix() {
        var clock=100_000L
        val source=Fake().apply {choices=listOf(route("Other Street"))}
        val resolver=NotificationRouteResolver(source){clock}
        assertNull(resolver.resolve(request()))
        clock+=30_001;source.choices=listOf(route())
        val moved=GeoPoint(0.0,.0001)
        assertNotNull(resolver.resolve(request().copy(point=moved,distanceMeters=100.1)))
        assertEquals(1,source.searches);assertEquals(2,source.routes)
        assertSame(moved,source.routeOrigins.last());assertEquals(1L,resolver.diagnostics().searchCacheHits)
    }
    @Test fun searchMemoHitsDoNotFreshenItsFiveMinuteExpiry() {
        var clock=100_000L
        val source=Fake().apply {choices=listOf(route("Other Street"))}
        val resolver=NotificationRouteResolver(source){clock}
        assertNull(resolver.resolve(request()))
        for(time in listOf(200_000L,399_999L,430_000L)) {clock=time;assertNull(resolver.resolve(request()))}
        assertEquals(2,source.searches);assertEquals(4,source.routes)
        assertEquals(2L,resolver.diagnostics().searchCacheHits)
    }
    @Test fun aDifferentRoundedAreaOrExplicitFullClearCannotReuseSearchResults() {
        var clock=100_000L
        val source=Fake().apply {choices=listOf(route("Other Street"))}
        val resolver=NotificationRouteResolver(source){clock}
        assertNull(resolver.resolve(request()))
        clock+=30_001
        val elsewhere=request().copy(point=GeoPoint(.02,0.0))
        assertNull(resolver.resolve(elsewhere));assertEquals(2,source.searches)
        resolver.clear(false);clock+=30_001
        assertNull(resolver.resolve(elsewhere));assertEquals(3,source.searches)
    }
    @Test fun canceledSearchCannotPopulateMemoButNormalManeuverClearCanRetainIt() {
        var clock=100_000L
        val source=Fake().apply {choices=listOf(route("Other Street"))}
        val resolver=NotificationRouteResolver(source){clock}
        source.onSearch={resolver.cancel()}
        try {resolver.resolve(request());fail("Cancelled search must not commit")}catch(_:InterruptedException){}
        clock+=30_001;source.onSearch={}
        assertNull(resolver.resolve(request()));assertEquals(2,source.searches)
        resolver.clear(true);clock+=30_001
        assertNull(resolver.resolve(request()));assertEquals(2,source.searches)
    }
    @Test fun searchMemoRetainsAtMostFourExactQueries() {
        var clock=100_000L
        val source=Fake().apply {choices=listOf(route("Other Street"))}
        val resolver=NotificationRouteResolver(source){clock}
        for(name in listOf("Alpha","Beta","Gamma","Delta","Epsilon","Alpha")) {
            source.places=listOf(roadPlace("$name Street"))
            assertNull(resolver.resolve(request().copy(instruction="Turn right onto $name Street")))
            clock+=30_001
        }
        assertEquals(6,source.searches)
    }
    @Test fun oneRemainingEndpointPrefersNotifiedSideWithoutIncreasingTheBudget() {
        val opposite=OwnMapProvider.Place("Fixture street",GeoPoint(.001,.001),listOf("James Street"))
        val correct=OwnMapProvider.Place("Fixture street",GeoPoint(-.004,.001),listOf("James Street"))
        assertEquals(listOf(correct),NotificationRouteResolver.selectTargets(request(),NotificationTurnHint.Kind.RIGHT,
            listOf(opposite,correct),1))
    }
    @Test fun windingApproachCoversDifferentRoadPortionsWithoutUsingCurrentHeadingAsFutureTurnSide() {
        val anchor=GeoPoint(.02,0.0)
        val near=OwnMapProvider.Place("Fixture road",GeoPoint(.03,0.0),listOf("James Street"))
        val first=OwnMapProvider.Place("Fixture road",GeoPoint(.02,-.03),listOf("James Street"))
        val middle=OwnMapProvider.Place("Fixture road",GeoPoint(.015,-.06),listOf("James Street"))
        val far=OwnMapProvider.Place("Fixture road",GeoPoint(.01,-.09),listOf("James Street"))
        val selected=NotificationRouteResolver.selectTargets(request().copy(bearing=270.0,distanceMeters=3862.0),
            NotificationTurnHint.Kind.LEFT,listOf(near,first,middle,far),3,listOf(anchor))
        assertEquals(3,selected.size)
        assertFalse(selected.contains(near))
        assertTrue(selected.containsAll(listOf(first,middle,far)))
    }
    @Test fun aFarStreetCueCanTryABeyondJunctionRepresentativeWithoutWeakeningRouteProof() {
        val near=roadPlace("Southeast 272nd Street",.001)
        val far=roadPlace("Southeast 272nd Street",.04)
        val source=Fake().apply {
            places=listOf(near,far);choices=listOf(route("Other Street"))
        }
        assertNull(NotificationRouteResolver(source){100_000}.resolve(
            request().copy(instruction="Turn right onto SE 272nd St",distanceMeters=1500.0)))
        assertSame(far.point,source.routedDestinations.first())
        assertEquals("Southeast 272nd Street",source.queries.first())
        assertTrue(source.searches<=2);assertTrue(source.routes<=4)
    }
    @Test fun anInvalidFartherRepresentativeStillFallsBackToAnotherActualRoadPoint() {
        val near=OwnMapProvider.Place("Fixture road",GeoPoint(-.001,.009),listOf("Southeast 272nd Street"))
        val far=OwnMapProvider.Place("Fixture road",GeoPoint(-.03,.009),listOf("Southeast 272nd Street"))
        val source=Fake().apply {places=listOf(near,far)}
        source.onRoute={
            val road=if(source.routes==1)"Other Street"else"SE 272nd St"
            source.choices=listOf(RoutePlan(listOf(origin,GeoPoint(0.0,.009),GeoPoint(-.001,.009)),listOf(
                RoutePlan.Maneuver(0,1,"Head east","",1),
                RoutePlan.Maneuver(1,2,"Turn right onto $road","",10,10.0,listOf(road),emptyList(),emptyList()),
                RoutePlan.Maneuver(2,2,"Arrive","",5)),100.0,"Fixture"))
        }
        assertNotNull(NotificationRouteResolver(source){100_000}.resolve(request().copy(
            instruction="Turn right onto SE 272nd St",distanceMeters=1000.8)))
        assertEquals(listOf(far.point,near.point),source.routedDestinations)
        assertEquals(2,source.searches);assertEquals(2,source.routes)
    }
    private fun signedSource(candidate:RoutePlan)=Fake().apply {
        onSearch={places=if(searches==1) emptyList() else
            listOf(OwnMapProvider.Place("Test City, Fixture County",GeoPoint(-.01,.001)))}
        choices=listOf(candidate)
    }
    private fun exitRoute(road:String="I 5 North")=RoutePlan(listOf(origin,GeoPoint(0.0,.001),GeoPoint(-.001,.001)),listOf(
        RoutePlan.Maneuver(0,1,"Head east","",1),
        RoutePlan.Maneuver(1,2,"Keep right","",23,10.0,listOf(road),emptyList(),emptyList()),
        RoutePlan.Maneuver(2,2,"Arrived","",4)),20.0,"Target")
    private fun exitRequest()=request().copy(instruction="Use the right lane to take the I-5 N exit toward Test City")
    @Test fun signedCityCanProposeButOnlyAnActuallyMatchingFirstExitCanProduceGeometry() {
        val valid=exitRoute()
        val source=signedSource(valid)
        assertNotNull(NotificationRouteResolver(source){100_000}.resolve(exitRequest()))
        assertEquals(listOf("I-5 N","Test City"),source.queries)
        assertEquals(2,source.searches);assertEquals(1,source.routes)
        assertEquals(3,source.budgets.size)
        assertTrue(source.budgets.all{it in 1..25_000})
    }
    @Test fun cityNameDoesNotProveTheRoadSideDirectionOrCurrentCourse() {
        for(candidate in listOf(exitRoute("Other Street"),exitRoute("I 5 South"))) {
            val source=signedSource(candidate)
            assertNull(NotificationRouteResolver(source){100_000}.resolve(exitRequest()))
            assertEquals(1,source.routes)
        }
        assertNull(NotificationRouteResolver(signedSource(exitRoute())){100_000}
            .resolve(exitRequest().copy(bearing=270.0)))
        val wrongSide=RoutePlan(listOf(origin,GeoPoint(0.0,.001),GeoPoint(.001,.001)),listOf(
            RoutePlan.Maneuver(0,1,"Head east","",1),
            RoutePlan.Maneuver(1,2,"Take the exit","",21,10.0,listOf("I 5 North"),emptyList(),emptyList()),
            RoutePlan.Maneuver(2,2,"Arrived","",4)),20.0,"Target")
        assertNull(NotificationRouteResolver(signedSource(wrongSide)){100_000}.resolve(exitRequest()))
    }
    @Test fun cityProposalCannotSkipAnEarlierUnannouncedTurn() {
        val earlier=RoutePlan(listOf(origin,GeoPoint(0.0,.0005),GeoPoint(-.0005,.0005),GeoPoint(-.0005,.001)),listOf(
            RoutePlan.Maneuver(0,1,"Head east","",1),
            RoutePlan.Maneuver(1,2,"Turn right","",10,10.0,listOf("Other Street"),emptyList(),emptyList()),
            RoutePlan.Maneuver(2,3,"Take the exit","",20,10.0,listOf("I 5 North"),emptyList(),emptyList()),
            RoutePlan.Maneuver(3,3,"Arrived","",4)),20.0,"Target")
        assertNull(NotificationRouteResolver(signedSource(earlier)){100_000}.resolve(exitRequest()))
    }
    @Test fun destinationHintChangeCannotReusePreviousSignedDestinationCache() {
        val source=signedSource(exitRoute());var clock=100_000L
        val resolver=NotificationRouteResolver(source){clock}
        assertNotNull(resolver.resolve(exitRequest()))
        clock+=1000
        assertNull(resolver.resolve(exitRequest().copy(instruction="Use the right lane to take the I-5 N exit toward Other City")))
        assertEquals(0L,resolver.diagnostics().cacheHits)
    }
    @Test fun nextHighwayCueReroutesTheRememberedDestinationFromTheFreshActualFix() {
        val source=signedSource(exitRoute());var clock=100_000L
        val resolver=NotificationRouteResolver(source){clock}
        assertNotNull(resolver.resolve(exitRequest()))
        clock+=30_001
        val fix=GeoPoint(0.0,.0002)
        val next=exitRequest().copy(point=fix,instruction="Keep right onto I-5 N",distanceMeters=88.96)
        assertNotNull(resolver.resolve(next))
        assertEquals(2,source.searches);assertEquals(2,source.routes)
        assertSame(fix,source.routeOrigins.last())
        assertEquals(source.routedDestinations.first(),source.routedDestinations.last())
        assertEquals(0L,resolver.diagnostics().cacheHits) // A fresh route call, not the prior cue's path.
    }
    @Test fun rememberedDestinationCannotOverrideTheNewActualTurnConstraints() {
        val source=signedSource(exitRoute());var clock=100_000L
        val resolver=NotificationRouteResolver(source){clock}
        assertNotNull(resolver.resolve(exitRequest()))
        clock+=30_001;source.places=emptyList();source.onSearch={source.places=emptyList()}
        assertNull(resolver.resolve(exitRequest().copy(instruction="Keep left onto I-5 N")))
        assertEquals(2,source.routes) // Reused target proposed a fresh route, then proof rejected it.
    }
    @Test fun signedProposalAgeAndMovementAreMeasuredFromItsOriginalCreation() {
        for(expired in listOf(true,false)) {
            val source=signedSource(exitRoute());var clock=100_000L
            val resolver=NotificationRouteResolver(source){clock}
            assertNotNull(resolver.resolve(exitRequest()))
            clock+=if(expired)1_200_001 else 30_001
            source.onSearch={source.places=emptyList()}
            val point=if(expired)origin else GeoPoint(.28,0.0)
            assertNull(resolver.resolve(exitRequest().copy(point=point,instruction="Keep right onto I-5 N")))
            assertEquals(1,source.routes)
        }
    }
    @Test fun differentOrOppositeHighwayAndClearFalseInvalidateTheRememberedProposal() {
        for(clear in listOf("clear","opposite","different")) {
            val source=signedSource(exitRoute());var clock=100_000L
            val resolver=NotificationRouteResolver(source){clock}
            assertNotNull(resolver.resolve(exitRequest()))
            clock+=30_001;source.onSearch={source.places=emptyList()}
            if(clear=="clear")resolver.clear(false)
            else {
                val road=if(clear=="opposite")"I-5 S"else"I-90 E"
                assertNull(resolver.resolve(exitRequest().copy(instruction="Keep right onto $road")))
                clock+=30_001
            }
            assertNull(resolver.resolve(exitRequest().copy(instruction="Keep right onto I-5 N")))
            assertEquals(1,source.routes)
        }
    }
    @Test fun canceledSignedCityResultCannotCommitAContinuityProposal() {
        val source=signedSource(exitRoute());var clock=100_000L
        val resolver=NotificationRouteResolver(source){clock}
        source.onRoute={resolver.cancel()}
        try { resolver.resolve(exitRequest());fail("Cancelled proposal must not commit") }catch(_:InterruptedException){}
        clock+=30_001;source.onRoute={};source.onSearch={source.places=emptyList()}
        assertNull(resolver.resolve(exitRequest().copy(instruction="Keep right onto I-5 N")))
        assertEquals(1,source.routes)
    }
    @Test fun signDestinationUsesOnlyTheNearestExactNamedProposalNotSameNamedPois() {
        val source=signedSource(exitRoute())
        source.onSearch={source.places=if(source.searches==1)emptyList()else
            listOf(.04,.03,.01,.02).map { OwnMapProvider.Place("Test City, Fixture County",GeoPoint(-it,.001)) }}
        assertNotNull(NotificationRouteResolver(source){100_000}.resolve(exitRequest()))
        assertEquals(2,source.searches);assertEquals(1,source.routes)
        assertTrue(source.routedDestinations.single().distanceTo(GeoPoint(-.01,.001))<.001)
    }
    @Test fun shortCueAllowsAFarSignedCityWithoutExpandingRoadTargetsOrTheCityCap() {
        val farCity=GeoPoint(-.30,.001) // About33km from a111m actual notified exit.
        val source=signedSource(exitRoute())
        source.onSearch={source.places=if(source.searches==1)emptyList()else
            listOf(OwnMapProvider.Place("Test City, Fixture County",farCity))}
        assertNotNull(NotificationRouteResolver(source){100_000}.resolve(exitRequest()))
        assertEquals(2,source.searches);assertEquals(1,source.routes)
        assertSame(farCity,source.routedDestinations.single())

        val road=Fake().apply {places=listOf(OwnMapProvider.Place("Highway",farCity,listOf("I 5 North")));choices=listOf(exitRoute())}
        assertNull(NotificationRouteResolver(road){100_000}.resolve(exitRequest().copy(instruction="Take the I-5 N exit")))
        assertEquals(0,road.routes)

        val outside=signedSource(exitRoute())
        outside.onSearch={outside.places=if(outside.searches==1)emptyList()else
            listOf(OwnMapProvider.Place("Test City, Fixture County",GeoPoint(-1.40,.001)))}
        assertNull(NotificationRouteResolver(outside){100_000}.resolve(exitRequest()))
        assertEquals(2,outside.searches);assertEquals(0,outside.routes)
    }
}
