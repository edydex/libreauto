// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.TurnGeometry
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.round

/** One bounded route proposal constrained by the current Google notification, never its UI. */
class NotificationRouteResolver internal constructor(private val source: Source, private val clock: () -> Long) {
    constructor() : this(ProviderSource(), { System.nanoTime() / 1_000_000 })

    /** Explicitly approved approximate search bias; never carries the original fix. */
    internal class NearbyArea private constructor(val latitude: Double, val longitude: Double) {
        companion object {
            fun fromPoint(point: GeoPoint) = NearbyArea(
                (round(point.latitude * 100) / 100).coerceIn(-90.0, 90.0),
                (round(point.longitude * 100) / 100).coerceIn(-180.0, 180.0))
        }
    }
    internal interface Source {
        fun search(query: String, nearby: NearbyArea, maximumMillis: Long): List<OwnMapProvider.Place>
        fun routes(origin: GeoPoint, destination: OwnMapProvider.Place, bearing: Float, maximumMillis: Long): List<RoutePlan>
        fun cancel()
    }
    private class ProviderSource : Source {
        private val provider = OwnMapProvider()
        override fun search(query: String, nearby: NearbyArea, maximumMillis: Long) =
            provider.search(query, GeoPoint(nearby.latitude, nearby.longitude), maximumMillis)
        override fun routes(origin: GeoPoint, destination: OwnMapProvider.Place, bearing: Float, maximumMillis: Long) =
            provider.routeCandidates(origin, destination, bearing, maximumMillis)
        override fun cancel() = provider.cancel()
    }
    data class Diagnostics(val status: String, val networkRequests: Long, val cacheHits: Long,
                           val searchCacheHits: Long = 0)
    private data class Cached(val key: String, val createdAt: Long, val routes: List<RoutePlan>)
    private data class SearchKey(val query: String, val latitude: Double, val longitude: Double)
    private data class CachedSearch(val createdAt: Long, val places: List<OwnMapProvider.Place>)
    private data class SignedProposal(val road: String, val destination: String, val createdAt: Long,
                                      val origin: GeoPoint, val place: OwnMapProvider.Place)
    private val commitLock = Any()
    private val generation = AtomicLong()
    private val networkRequests = AtomicLong()
    private val cacheHits = AtomicLong()
    private val searchCacheHits = AtomicLong()
    private val searchCache = LinkedHashMap<SearchKey, CachedSearch>()
    @Volatile private var cached: Cached? = null
    @Volatile private var signedProposal: SignedProposal? = null
    @Volatile private var lastAttemptAt: Long? = null
    @Volatile private var state = "IDLE"
    fun diagnostics() = Diagnostics(state, networkRequests.get(), cacheHits.get(), searchCacheHits.get())
    fun cancel() { synchronized(commitLock) { generation.incrementAndGet() }; source.cancel() }
    fun clear(keepCandidates: Boolean = true) {
        cancel()
        if (!keepCandidates) synchronized(commitLock) { cached = null; signedProposal = null; searchCache.clear() }
    }

    /** Worker only. Cache matching performs no network and still validates the current real fix. */
    fun resolve(request: TurnMatcher.Request): TurnGeometry? {
        val hint = NotificationTurnHint.parse(request.instruction) ?: run { state = "UNSUPPORTED"; return null }
        if (!supports(request.instruction, request.distanceMeters) || !request.bearing.isFinite() ||
            request.bearing !in 0.0..<360.0 || !request.accuracy.isFinite() || request.accuracy !in 0.0..25.0) {
            state = "WAITING_FIX"; return null
        }
        val ticket = generation.get()
        fun current() {
            if (Thread.currentThread().isInterrupted || generation.get() != ticket) throw InterruptedException()
        }
        val now = clock()
        val reference = referenceKey(hint.road)
        val remembered = synchronized(commitLock) {
            current()
            signedProposal?.takeIf {
                it.road == reference && now - it.createdAt in 0..1_200_000 &&
                    request.point.distanceTo(it.origin) <= 30_000 &&
                    request.point.distanceTo(it.place.point) in 15.0..150_000.0 &&
                    (hint.destinationHint == null || TurnMatcher.normalize(hint.destinationHint) == it.destination)
            }.also { if (it == null) signedProposal = null }
        }
        val key = hint.kind.name + ":" + (if (hint.toward) "toward:" else "onto:") + TurnMatcher.normalize(hint.road) +
            ":" + TurnMatcher.normalize(hint.destinationHint.orEmpty())
        val old = cached?.takeIf { it.key == key && now - it.createdAt in 0..120_000 }
        old?.let {
            current()
            NotificationCandidatePolicy.match(request, it.routes)?.let { match ->
                current(); cacheHits.incrementAndGet(); state = "CACHED_MATCH"; return match
            }
        }
        if (lastAttemptAt?.let { now - it in 0..29_999 } == true) { state = "RATE_LIMITED"; return null }
        current(); lastAttemptAt = now
        val deadline = now + 25_000
        fun remaining(): Long {
            current()
            val millis = deadline - clock()
            if (millis <= 0) throw SocketTimeoutException()
            return millis
        }
        try {
            // A geocoder's representative point may sit kilometres along a long
            // street. The route's actual first turn still has to match Google's cue.
            val maximumDistance = max(10_000.0, request.distanceMeters * 3 + 2000).coerceAtMost(150_000.0)
            val attempted = ArrayList<OwnMapProvider.Place>()
            val deferred = ArrayList<OwnMapProvider.Place>()
            val routes = ArrayList<RoutePlan>()
            var invalid = false
            var newSignedProposal: SignedProposal? = null
            fun matched(): TurnGeometry? {
                current()
                val result = NotificationCandidatePolicy.match(request, routes)
                synchronized(commitLock) {
                    current()
                    cached = Cached(key, clock(), routes.toList())
                    if (result != null && newSignedProposal != null) signedProposal = newSignedProposal
                }
                if (result != null) state = "MATCHED"
                return result
            }
            fun routeTargets(targets: List<OwnMapProvider.Place>, signedDestination: Boolean = false) {
                for (target in targets) {
                    if (attempted.size >= 4 || attempted.any { it.point.distanceTo(target.point) < 80 }) continue
                    attempted.add(target)
                    state = "ROUTING"
                    val budget = remaining()
                    networkRequests.incrementAndGet()
                    val choices = source.routes(request.point, target, request.bearing.toFloat(), budget)
                    remaining()
                    if (choices.size > 3 || choices.isEmpty()) { state = "INVALID_ROUTE_RESULT"; invalid = true; return }
                    routes.addAll(choices)
                    if (routes.sumOf { it.points.size } > 60_000) { state = "TOO_LARGE"; invalid = true; return }
                    if (signedDestination && reference != null && hint.destinationHint != null &&
                        NotificationCandidatePolicy.match(request, choices) != null) {
                        current()
                        newSignedProposal = SignedProposal(reference, TurnMatcher.normalize(hint.destinationHint),
                            now, request.point, target)
                    }
                }
            }
            val nearby = NearbyArea.fromPoint(request.point)
            // Search the named street alias; the full notification (including a
            // highway's explicit direction) remains authoritative for route proof.
            val searchRoad = TurnMatcher.roadAlternatives(hint.road).lastOrNull() ?: hint.road
            val expanded = expandedRoadName(searchRoad)
            // A highway reference may be absent from a geocoder's road-name
            // index. The signed destination can propose an endpoint, but cannot
            // replace any road/side/distance/first-maneuver proof below.
            // A fully spelled numbered street is less ambiguous than e.g.
            // "SE 256 St". Keep the original wording as the bounded fallback.
            val queries = if (hint.destinationHint == null && referenceKey(searchRoad) == null)
                listOfNotNull(expanded, searchRoad).distinct()
                else listOfNotNull(searchRoad, hint.destinationHint ?: expanded).distinct()
            if (remembered != null) {
                // Reuse only a destination point. The new cue gets a fresh route
                // from the actual fix and all first-turn constraints run again.
                routeTargets(listOf(remembered.place))
                if (invalid) return null
                matched()?.let { return it }
            }
            for ((queryIndex, query) in queries.withIndex()) {
                if (attempted.size >= 4) break
                state = "SEARCHING"
                val searchKey = SearchKey(query.trim().lowercase(Locale.ROOT), nearby.latitude, nearby.longitude)
                val saved = synchronized(commitLock) {
                    current()
                    searchCache.entries.removeAll { clock() - it.value.createdAt !in 0..300_000 }
                    searchCache[searchKey]
                }
                // This separate rounded-only value is the approved approximate
                // area, not the precise fix used locally and by routing.
                val places = if (saved != null) {
                    searchCacheHits.incrementAndGet(); saved.places
                } else {
                    val budget = remaining()
                    networkRequests.incrementAndGet()
                    source.search(query, nearby, budget).take(8).toList().also { results ->
                        remaining()
                        synchronized(commitLock) {
                            current()
                            if (results.isNotEmpty()) {
                                searchCache.remove(searchKey)
                                searchCache[searchKey] = CachedSearch(clock(), results)
                                while (searchCache.size > 4) searchCache.remove(searchCache.keys.first())
                            }
                        }
                    }
                }
                remaining()
                val eligible = ArrayList<OwnMapProvider.Place>()
                val signedDestinationQuery = hint.destinationHint != null && query == hint.destinationHint
                for (place in places.take(8).sortedBy { request.point.distanceTo(it.point) }) {
                    val labelAgrees = if (signedDestinationQuery)
                        TurnMatcher.normalize(place.name.substringBefore(',')) == TurnMatcher.normalize(hint.destinationHint!!)
                        else TurnMatcher.roadMatches(hint.road, place.roadNames)
                    if (!labelAgrees ||
                        request.point.distanceTo(place.point) !in 15.0..(if (signedDestinationQuery) 150_000.0 else maximumDistance) ||
                        attempted.any { it.point.distanceTo(place.point) < 80 } ||
                        eligible.any { it.point.distanceTo(place.point) < 80 }) continue
                    eligible.add(place)
                }
                // An exit sign names one destination proposal. Do not spend
                // extra route calls on several same-named POIs around that city.
                val offered = if (signedDestinationQuery) eligible.take(1) else eligible
                val proposed = selectTargets(request, hint.kind, offered, 4, attempted.map { it.point })
                // Reserve endpoints for the fallback spelling when the first
                // road representative cannot reproduce Google's actual next turn.
                // For a far street turn, a nearby centroid often ends before
                // the notified junction. Try a real farther road point first;
                // only the unchanged route proof can establish the actual turn.
                val firstStreetQuery = queryIndex == 0 && queries.size > 1 &&
                    hint.destinationHint == null && referenceKey(searchRoad) == null && request.distanceMeters > 500
                val firstOffered = if (firstStreetQuery) offered.sortedByDescending { request.point.distanceTo(it.point) } else offered
                val selected = selectTargets(request, hint.kind, firstOffered,
                    if (queryIndex < queries.lastIndex) 1 else 4 - attempted.size, attempted.map { it.point })
                deferred.addAll(proposed.filter { candidate -> selected.none { it === candidate } })
                routeTargets(selected, signedDestinationQuery)
                if (invalid) return null
                matched()?.let { return it }
            }
            routeTargets(deferred)
            if (invalid) return null
            matched()?.let { return it }
            state = if (attempted.isEmpty()) "NO_ROAD_RESULT" else "NO_MATCH"
            return null
        } catch (cancelled: InterruptedException) { throw cancelled }
        catch (_: SocketTimeoutException) { current(); state = "TIMEOUT"; return null }
        catch (_: Exception) { current(); state = "UNAVAILABLE"; return null }
    }

    companion object {
        private fun referenceKey(road: String): String? = TurnMatcher.normalize(road).takeIf {
            Regex("^(?:i|us|u s|wa|sr|(?:washington |wash )?state (?:route|hwy|rte|rt)) \\d+[a-z]?(?: [nsew])?$").matches(it)
        }
        /** Cover distinct portions of a long road; a winding approach makes current-course side unreliable. */
        internal fun selectTargets(request: TurnMatcher.Request, kind: NotificationTurnHint.Kind,
                                   places: List<OwnMapProvider.Place>, maximum: Int = 2,
                                   alreadyAttempted: List<GeoPoint> = emptyList()): List<OwnMapProvider.Place> {
            val limit=maximum.coerceIn(0,4)
            if(limit==0 || places.isEmpty())return emptyList()
            val right = when (kind) {
                NotificationTurnHint.Kind.RIGHT, NotificationTurnHint.Kind.SLIGHT_RIGHT,
                NotificationTurnHint.Kind.KEEP_RIGHT, NotificationTurnHint.Kind.EXIT_RIGHT,
                NotificationTurnHint.Kind.RAMP_RIGHT, NotificationTurnHint.Kind.MERGE_RIGHT -> true
                NotificationTurnHint.Kind.LEFT, NotificationTurnHint.Kind.SLIGHT_LEFT,
                NotificationTurnHint.Kind.KEEP_LEFT, NotificationTurnHint.Kind.EXIT_LEFT,
                NotificationTurnHint.Kind.RAMP_LEFT, NotificationTurnHint.Kind.MERGE_LEFT -> false
                else -> null
            }
            fun onNotifiedSide(place: OwnMapProvider.Place): Boolean {
                val lat1=Math.toRadians(request.point.latitude);val lat2=Math.toRadians(place.point.latitude)
                val lon=Math.toRadians(place.point.longitude-request.point.longitude)
                val bearing=Math.toDegrees(atan2(sin(lon)*cos(lat2),cos(lat1)*sin(lat2)-sin(lat1)*cos(lat2)*cos(lon)))
                val angle=(bearing-request.bearing+540)%360-180
                return if(right==true)angle in 10.0..170.0 else angle in -170.0..-10.0
            }
            val pending=places.toMutableList();val selected=ArrayList<OwnMapProvider.Place>()
            if(alreadyAttempted.isEmpty()) {
                val first=if(limit==1 && request.distanceMeters<=500 && right!=null)
                    pending.firstOrNull(::onNotifiedSide) ?: pending.first() else pending.first()
                selected.add(first);pending.remove(first)
            }
            while(selected.size<limit && pending.isNotEmpty()) {
                val anchors=alreadyAttempted+selected.map{it.point}
                val next=pending.maxByOrNull { candidate -> anchors.minOf { it.distanceTo(candidate.point) } } ?: break
                selected.add(next);pending.remove(next)
            }
            return selected
        }
        internal fun expandedRoadName(road: String): String? {
            val normal = TurnMatcher.normalize(road)
            val state = Regex("^(?:(?:wash|washington) state |state )?(?:rte|rt|route|hwy|highway|wa|sr) (\\d+[a-z]?)(?: [nsew])?$")
                .matchEntire(normal)
            val expanded = if (state != null) "Washington State Route ${state.groupValues[1]}" else {
                val words = mapOf("se" to "Southeast", "sw" to "Southwest", "ne" to "Northeast", "nw" to "Northwest",
                    "n" to "North", "s" to "South", "e" to "East", "w" to "West", "st" to "Street", "ave" to "Avenue",
                    "rd" to "Road", "dr" to "Drive", "ln" to "Lane", "ct" to "Court", "pl" to "Place", "blvd" to "Boulevard",
                    "pkwy" to "Parkway", "hwy" to "Highway", "i" to "Interstate")
                normal.split(' ').joinToString(" ") { words[it] ?: it }
            }
            return expanded.takeIf { !it.equals(road.trim(), ignoreCase = true) && it.length in 2..200 }
        }
        @JvmStatic fun supports(instruction: String, distanceMeters: Double?): Boolean =
            distanceMeters != null && distanceMeters.isFinite() && distanceMeters in 5.0..50_000.0 &&
                NotificationTurnHint.parse(instruction) != null
    }
}
