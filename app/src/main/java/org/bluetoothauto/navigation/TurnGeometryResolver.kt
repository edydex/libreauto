// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Optional bounded OSM lookup. A failed/ambiguous lookup leaves the authoritative turn card intact. */
class TurnGeometryResolver(context: Context? = null) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bluetooth-turn-geometry").apply { isDaemon = true } }
    @Volatile private var serial = 0L
    @Volatile private var closed = false
    private var pending: Future<*>? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cache: Cached? = null
    private val backoff = TurnGeometryBackoff()
    private val candidateResolver = NotificationRouteResolver()
    @Volatile var status: String = TurnGeometryTransport.State.IDLE.name
        private set
    @Volatile private var resolving = false
    private val networkAttempts = AtomicLong()
    private val cacheHits = AtomicLong()
    private val matches = AtomicLong()
    private val failures = AtomicLong()
    private val persistentCacheHits = AtomicLong()
    private val persistentStoreFailures = AtomicLong()
    private val roadStore = context?.let { TurnRoadStore(it.applicationContext) }
    @Volatile private var graphSource = "NONE"
    @Volatile private var lastElapsedMillis = 0L
    private data class Cached(val center: GeoPoint, val radius: Double, val createdAt: Long, val graph: TurnMatcher.Graph, val source: String)
    private data class Fetched(val graph: TurnMatcher.Graph, val json: String)

    /** No coordinates, street names, queries, URLs, exception messages or device identifiers. */
    data class Diagnostics(val status: String, val pending: Boolean, val networkAttempts: Long,
                           val cacheHits: Long, val matches: Long, val failures: Long,
                           val lastElapsedMillis: Long, val cachedNodes: Int, val cachedWays: Int,
                           val lastFailureStatus: String, val consecutiveNetworkFailures: Int,
                           val networkRetryInMillis: Long, val persistentCacheHits: Long,
                           val graphSource: String, val preparedAreaCount: Int, val persistentStoreFailures: Long,
                           val candidateStatus: String, val candidateNetworkRequests: Long, val candidateCacheHits: Long)
    fun diagnostics(): Diagnostics {
        val graph = cache?.graph
        val candidates = candidateResolver.diagnostics()
        return Diagnostics(status, resolving, networkAttempts.get(), cacheHits.get(), matches.get(), failures.get(),
            lastElapsedMillis, graph?.nodes?.size ?: 0, graph?.ways?.size ?: 0,
            backoff.lastFailureStatus, backoff.consecutiveFailures, backoff.retryInMillis(SystemClock.elapsedRealtime()),
            persistentCacheHits.get(), graphSource, roadStore?.areaCount ?: 0, persistentStoreFailures.get(),
            candidates.status, candidates.networkRequests, candidates.cacheHits)
    }

    fun resolve(position: Position, guidance: Guidance, callback: (TurnGeometry?) -> Unit): Closeable {
        requireMain()
        val token = ++serial
        cancelPending()
        val point = try { GeoPoint(position.latitude, position.longitude) } catch (_: IllegalArgumentException) { null }
        val distance = guidance.distanceMeters
        val bearing = position.bearing?.toDouble()
        val now = SystemClock.elapsedRealtime()
        val blocked = when {
            closed -> TurnGeometryTransport.State.CLOSED
            position.demo || guidance.demo -> TurnGeometryTransport.State.DEMO
            point == null || !position.accuracy.isFinite() || position.accuracy !in 0f..25f || now - position.receivedAt !in 0..15_000 -> TurnGeometryTransport.State.WAITING_FIX
            bearing == null || !bearing.isFinite() || bearing !in 0.0..<360.0 -> TurnGeometryTransport.State.WAITING_COURSE
            distance == null || !distance.isFinite() || distance !in 5.0..50_000.0 -> TurnGeometryTransport.State.WAITING_DISTANCE
            !NotificationRouteResolver.supports(guidance.instruction, distance) -> TurnGeometryTransport.State.UNSUPPORTED_INSTRUCTION
            else -> null
        }
        if (blocked != null) { status = blocked.name; main.post { if (!closed && token == serial) callback(null) }; return handle(token) }
        val origin = point!!
        val meters = distance!!
        val course = bearing!!
        val radius = (meters * 1.3 + 150).coerceIn(250.0, 1500.0)
        val fetchRadius = (radius + 250).coerceAtMost(1500.0)
        val localSupported = meters <= 1200 && TurnMatcher.turn(guidance.instruction) != null
        val existing = cache?.takeIf { localSupported }?.takeIf { now - it.createdAt in 0..120_000 &&
            TurnGraphCoverage.reusableInterior(it.center, it.radius, origin) }
        val existingPartial = existing != null && !TurnGraphCoverage.full(existing.center, existing.radius, origin, radius)
        if (existing != null) cacheHits.incrementAndGet()
        status = if (existing == null) TurnGeometryTransport.State.PREPARING.name else TurnGeometryTransport.State.CACHED_MATCHING.name
        resolving = true
        val request = TurnMatcher.Request(origin, course, position.accuracy.toDouble(), guidance.instruction, meters)
        pending = worker.submit {
            var freshCache: Cached? = null
            var outcome = TurnGeometryTransport.State.NO_MATCH.name
            val attemptsBefore = networkAttempts.get()
            var networkFetched = false
            var usedPersistent = false
            var usedPartial = existingPartial
            var usedSource = existing?.let { "MEMORY_${if (existingPartial) "PARTIAL_" else ""}${it.source}" } ?: "NONE"
            val result = try {
                val stored = if (localSupported && existing == null) try {
                    roadStore?.load(origin, radius) ?: roadStore?.load(origin, 50.0)
                }
                    catch (interrupted: InterruptedException) { throw interrupted }
                    catch (_: Exception) { persistentStoreFailures.incrementAndGet(); null } else null
                val graph = existing?.graph ?: stored?.let {
                    usedPersistent = true
                    usedPartial = !TurnGraphCoverage.full(it.center, it.radius, origin, radius)
                    usedSource = "PERSISTED_${if (usedPartial) "PARTIAL_" else ""}${it.source}"
                    freshCache = Cached(it.center, it.radius, SystemClock.elapsedRealtime(), it.graph, it.source)
                    if (!closed && token == serial) status = TurnGeometryTransport.State.PERSISTED_MATCHING.name
                    it.graph
                }
                val coverage = existing ?: freshCache
                if (graph != null && usedPartial) outcome = "PARTIAL_NO_MATCH"
                fun graphMatch(value: TurnMatcher.Graph): TurnGeometry? = TurnMatcher.match(value, request)?.let {
                    if (usedPartial && (coverage == null || !TurnGraphCoverage.containsMatch(coverage.center, coverage.radius, it))) return@let null
                    outcome = when { existing != null -> if (usedPartial) "CACHED_PARTIAL_MATCH" else TurnGeometryTransport.State.CACHED_MATCH.name
                        usedPersistent -> if (usedPartial) "PERSISTED_PARTIAL_MATCH" else TurnGeometryTransport.State.PERSISTED_MATCH.name
                        else -> TurnGeometryTransport.State.MATCHED.name }
                    TurnGeometry(it.points, it.junction, it.approachSpeedLimitKph, "matched", it.key)
                }
                var answer = graph?.let(::graphMatch)
                // A complete local graph's ambiguous result is not permission to
                // select an independently optimized route instead. Missing areas and
                // unsupported local maneuver kinds may use bounded route proposals.
                if (answer == null && (graph == null || !localSupported)) {
                    if (!closed && token == serial) status = "CANDIDATE_ROUTING"
                    answer = candidateResolver.resolve(request)
                    if (answer != null) { outcome = "CANDIDATE_MATCH"; usedSource = "VALHALLA_CANDIDATE" }
                }
                val remainingBudget = 25_000 - (SystemClock.elapsedRealtime() - now)
                if (answer == null && graph == null && localSupported && remainingBudget > 0) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val throttled = synchronized(backoff) {
                        val at = SystemClock.elapsedRealtime()
                        if (backoff.retryInMillis(at) > 0) true else { backoff.started(at); false }
                    }
                    if (throttled) outcome = TurnGeometryTransport.State.RATE_LIMITED.name
                    else {
                        if (!closed && token == serial) status = TurnGeometryTransport.State.FETCHING.name
                        val fetched = fetch(origin, fetchRadius, remainingBudget.coerceAtMost(12_000))
                        networkFetched = true; usedSource = "NETWORK_OVERPASS"
                        freshCache = Cached(origin, fetchRadius, SystemClock.elapsedRealtime(), fetched.graph, TurnRoadStore.OVERPASS)
                        try { roadStore?.save(origin, fetchRadius, fetched.json) }
                        catch (interrupted: InterruptedException) { throw interrupted }
                        catch (_: Exception) { persistentStoreFailures.incrementAndGet() }
                        answer = graphMatch(fetched.graph)
                    }
                }
                answer
            } catch (error: Exception) { outcome = TurnGeometryTransport.failureStatus(error); null }
            if (!Thread.currentThread().isInterrupted) main.post { if (!closed && token == serial) {
                resolving = false; pending = null; status = outcome
                graphSource = usedSource
                if (usedPersistent) persistentCacheHits.incrementAndGet()
                lastElapsedMillis = (SystemClock.elapsedRealtime() - now).coerceAtLeast(0)
                if (result != null) matches.incrementAndGet()
                else if (outcome !in setOf(TurnGeometryTransport.State.NO_MATCH.name, "PARTIAL_NO_MATCH", TurnGeometryTransport.State.CANCELLED.name,
                        TurnGeometryTransport.State.RATE_LIMITED.name)) failures.incrementAndGet()
                // A throttled rematch must not replace the retained transport failure. Stale or
                // cancelled generations never reach this commit and cannot extend the backoff.
                synchronized(backoff) {
                    if (networkFetched || result != null) backoff.succeeded()
                    else if (existing == null && networkAttempts.get() > attemptsBefore && outcome != TurnGeometryTransport.State.NO_MATCH.name)
                        backoff.failed(SystemClock.elapsedRealtime(), outcome)
                }
                freshCache?.let { cache = it }; callback(result)
            } }
        }
        return handle(token)
    }

    private fun handle(token: Long) = Closeable {
            requireMain()
            if (token == serial) { serial++; cancelPending(); status = TurnGeometryTransport.State.CANCELLED.name }
    }

    // Changing the maneuver or presentation must not bypass a failing provider's backoff.
    fun clear(keepGraph: Boolean = false) { requireMain(); serial++; cancelPending(); if (!keepGraph) cache = null; candidateResolver.clear(keepCandidates = keepGraph); status = TurnGeometryTransport.State.IDLE.name }
    override fun close() { requireMain(); if (!closed) { clear(); closed = true; status = TurnGeometryTransport.State.CLOSED.name; worker.shutdownNow() } }
    private fun cancelPending() {
        pending?.cancel(true); pending = null; resolving = false
        candidateResolver.cancel()
        val active = connection
        if (active != null) Thread({ try { active.disconnect() } catch (_: RuntimeException) {} }, "turn-geometry-cancel").apply { isDaemon = true; start() }
    }

    private fun fetch(point: GeoPoint, radius: Double, maximumMillis: Long = 12_000): Fetched {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (abs(point.latitude) >= 80 || abs(point.longitude) > 179.9) throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.UNSUPPORTED_AREA)
        val query = TurnGeometryTransport.areaQuery(point, radius)
        TurnRoadSource.awaitDownloadSlot{closed}
        val active = TurnGeometryTransport.getUrl(query).toURL().openConnection() as HttpURLConnection
        networkAttempts.incrementAndGet()
        connection = active
        val timedOut = AtomicBoolean(false)
        val deadline = Timer("turn-geometry-deadline", true)
        deadline.schedule(object : TimerTask() { override fun run() { timedOut.set(true); try { active.disconnect() } catch (_: RuntimeException) {} } }, maximumMillis.coerceAtLeast(1))
        try {
            active.connectTimeout = maximumMillis.coerceIn(1, 4000).toInt()
            active.readTimeout = maximumMillis.coerceIn(1, 8000).toInt(); active.instanceFollowRedirects = false
            active.requestMethod = "GET"
            active.setRequestProperty("User-Agent", "BluetoothAuto/0.2 (bounded turn geometry prototype)")
            active.setRequestProperty("Accept", "application/json")
            val code = active.responseCode
            if (code != 200) throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.IO, code)
            val body = active.inputStream.use { input -> ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_BYTES) throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.TOO_LARGE)
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            } }
            return try { Fetched(parseGraph(body), body) }
            catch (interrupted: InterruptedException) { throw interrupted }
            catch (_: Exception) { throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.PARSE) }
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (timedOut.get()) throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.TIMEOUT)
            throw error
        } finally { deadline.cancel(); active.disconnect(); if (connection === active) connection = null }
    }

    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    companion object {
        const val ENDPOINT = TurnGeometryTransport.ENDPOINT
        private const val MAX_BYTES = 3_000_000

        /** Standard Overpass node/way JSON only. Partial output cannot establish a unique junction. */
        @JvmStatic fun parseGraph(json: String): TurnMatcher.Graph = TurnRoadCodec.parseGraph(json)
    }
}
