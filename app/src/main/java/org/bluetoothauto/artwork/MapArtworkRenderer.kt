// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.GuidanceFormatter
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File

/** A proved route step; the renderer never derives a route from notification text. */
data class TripTurnArtwork(val guidance: Guidance, val geometry: TurnGeometry)

/**
 * Main-thread artwork producer. Each render emits a usable512px card synchronously and may emit
 * an improved map for the latest request later. A subsequent render/close invalidates old callbacks.
 * Outputs are independent immutable-in-practice bitmaps: callers must not recycle or mutate them.
 * No location service, permissions, route calculation, hidden connectivity override or polling.
 */
class MapArtworkRenderer(context: Context, private val allowCurrentContext: Boolean = false) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var serial = 0L
    private val turn = TurnArtworkSession()
    private var latest: Request? = null
    private var active: Job? = null
    private var cached: Cached? = null
    private data class PreparedMap(val frame: TurnArtworkPolicy.Frame, val background: Bitmap)
    private val prepared = PreparedTurnCache<PreparedMap> { if (!it.background.isRecycled) it.background.recycle() }
    private val preparations = PreparedTurnWorkQueue<Preparation> { it.key }
    private data class TripInput(val key: PreparedTurnKey, val position: Position,
                                 val geometry: TurnGeometry, val instruction: String)
    private val tripPlan = PreparedTripPlan<TripInput> { it.key }
    private var tripFingerprint: String? = null
    private val disk = PreparedTripImageIo(File(this.context.filesDir, "prepared-trip-artwork"), main)
    private val diskTried = hashSetOf<PreparedTurnKey>()
    private val diskLoading = hashSetOf<PreparedTurnKey>()
    private val diskReady = hashSetOf<PreparedTurnKey>()
    private val diskWriting = hashSetOf<PreparedTurnKey>()
    private var futureDiskLoad: Preparation? = null
    private var preparedDiskHits = 0L
    private var tripDiskEvictions = 0L
    private var nextAllowedAt = 0L
    private var prefetchNextAllowedAt = 0L
    private var mapFailed = false
    private var snapshotRequests = 0L
    private var lastSnapshotMs = 0L
    private var maxSnapshotMs = 0L
    private var totalSnapshotMs = 0L
    private var completedSnapshotCount = 0L
    private var nativeRouteSnapshots = 0L
    private var lastPreparedAdoptMs = 0L
    private var compositionCount = 0L
    private var cacheHits = 0L
    private var staticLayerBuilds = 0L
    private var mapRevision = 0L
    private var preparedCacheHits = 0L
    private var prefetchSnapshotRequests = 0L
    private var preparedGeneration = 0L
    private var lastEmissionKey: ImageKey? = null
    private var lastEmissionStatus: String? = null
    private var lastOutput: Bitmap? = null
    private val maneuvers = ManeuverPainter()
    private val deferred = Runnable { latest?.let(::ensureMap) }
    private val prefetchDeferred = Runnable { drainPreparations() }

    private data class Request(val token: Long, val position: Position?, val guidance: Guidance?, val units: String,
                               val frame: TurnArtworkPolicy.Frame?,
                               val callback: (Bitmap, String) -> Unit, val startedAtMs: Long)
    private class Preparation(val key: PreparedTurnKey, val frame: TurnArtworkPolicy.Frame,
                              val generation: Long, val callbacks: MutableList<(Boolean) -> Unit>, val tripOwned: Boolean = false)
    private class Job(val frame: TurnArtworkPolicy.Frame, val snapshotter: MapSnapshotter,
                      val preparation: Preparation? = null) {
        val startedAtMs = SystemClock.elapsedRealtime()
        var timeout: Runnable? = null
    }
    private class Cached(val frame: TurnArtworkPolicy.Frame, val snapshot: MapSnapshot?, var background: Bitmap? = null) {
        val readyAtMs = SystemClock.elapsedRealtime()
    }
    private data class ImageKey(val identity: TurnArtworkSession.Identity?, val distance: String, val mapRevision: Long,
                                val puck: CurrentContextPolicy.PuckKey? = null)

    /** Content-free main-thread counters; inspecting them does not trigger rendering or native work. */
    data class Diagnostics(val snapshotRequests: Long, val compositionCount: Long, val cacheHits: Long, val staticLayerBuilds: Long,
                           val preparedCacheHits: Long = 0, val preparedMapCount: Int = 0,
                           val prefetchSnapshotRequests: Long = 0, val isPreparing: Boolean = false,
                           val tripPlannedCount: Int = 0, val tripCompletedCount: Int = 0,
                           val tripPendingCount: Int = 0, val preparedDiskHits: Long = 0,
                           val tripDiskEvictions: Long = 0,
                           val lastSnapshotMs: Long = 0, val maxSnapshotMs: Long = 0,
                           val totalSnapshotMs: Long = 0, val completedSnapshotCount: Long = 0,
                           val lastPreparedAdoptMs: Long = 0, val nativeRouteSnapshots: Long = 0)

    fun diagnostics(): Diagnostics {
        requireMain()
        return Diagnostics(snapshotRequests, compositionCount, cacheHits, staticLayerBuilds,
            preparedCacheHits, prepared.size, prefetchSnapshotRequests,
            active?.preparation != null || preparations.isNotEmpty() || futureDiskLoad != null || tripPlan.hasWork || diskWriting.isNotEmpty(),
            tripPlan.plannedCount, tripPlan.completedCount, tripPlan.pendingCount, preparedDiskHits, tripDiskEvictions,
            lastSnapshotMs, maxSnapshotMs, totalSnapshotMs, completedSnapshotCount, lastPreparedAdoptMs, nativeRouteSnapshots)
    }

    /** Activate only the complete captured-route fingerprint; a reroute is a separate namespace. */
    fun setPreparedTrip(fingerprint: String?) {
        requireMain()
        require(fingerprint == null || fingerprint.isNotBlank() && fingerprint.length <= 256)
        if (closed || tripFingerprint == fingerprint) return
        clearPreparedState()
        tripFingerprint = fingerprint
        disk.activate(fingerprint)
    }

    /** Queue every supplied proved step, bounded to256 inputs; native jobs and decoded RAM stay small. */
    fun prepareTrip(fingerprint: String, position: Position, units: String, turns: List<TripTurnArtwork>) {
        requireMain()
        if (closed || tripFingerprint != fingerprint || position.demo ||
            !ArtworkPolicy.validPosition(position.latitude, position.longitude, position.accuracy.toDouble(),
                position.receivedAt, SystemClock.elapsedRealtime())) return
        val inputs = turns.take(PreparedTripPlan.MAX_TURNS).mapNotNull { item ->
            val key = preparedKey(item.geometry.key, units)
            val existing = key?.let(tripPlan::value)
            if (existing != null) existing
            else if (key == null || item.guidance.demo || item.guidance.instruction.isBlank() ||
                !TurnArtworkPolicy.validGeometry(item.geometry)) null
            else TripInput(key, position, item.geometry, item.guidance.instruction)
        }
        val removed = tripPlan.reconcile(inputs)
        if (removed.isNotEmpty()) {
            preparations.removeKeys(removed).forEach { finishPreparation(it,false) }
            if (active?.preparation?.key in removed) cancelJob()
            if (futureDiskLoad?.key in removed) futureDiskLoad = null
            removed.forEach { key -> prepared.take(key)?.background?.recycle() }
            diskReady.removeAll(removed); diskTried.removeAll(removed)
        }
        // A refined earlier step must precede retained later queued steps. Only queued trip work
        // is released: the visible map, native/disk-active work and saved progress keep priority.
        val queued = preparations.removeMatching { it.tripOwned }.associateBy { it.key }.toMutableMap()
        queued.keys.forEach { tripPlan.finish(it, false) }
        fillTripQueue(queued)
        queued.values.forEach { finishPreparation(it, false) }
        drainPreparations()
    }

    /** Prepare a real known junction without changing the visible request, turn lock or artwork. */
    fun prepare(position: Position, guidance: Guidance, units: String, geometry: TurnGeometry,
                onPrepared: (Boolean) -> Unit = {}) {
        requireMain()
        if (closed || guidance.instruction.isBlank() || !TurnArtworkPolicy.validGeometry(geometry) ||
            !ArtworkPolicy.validPosition(position.latitude, position.longitude, position.accuracy.toDouble(),
                position.receivedAt, SystemClock.elapsedRealtime())) { onPrepared(false); return }
        val key = preparedKey(geometry.key, units)
        if (key == null) { onPrepared(false); return }
        if (prepared.peek(key)?.background?.isRecycled == false ||
            latest?.let { preparedKey(it.frame?.geometryKey, it.units) == key && usableCache(it) != null } == true) {
            onPrepared(true); return
        }
        val existing = active?.preparation?.takeIf { it.key == key } ?: preparations.find(key)
        if (existing != null) {
            if (existing.callbacks.size < 4) existing.callbacks.add(onPrepared) else onPrepared(false)
            return
        }
        if (preparations.size >= 3) { onPrepared(false); return }
        // The confirmed geometry fixes zoom/orientation; position and phone speed cannot reframe it.
        val frame = TurnArtworkPolicy.frame(position, geometry, guidance.instruction)
        preparations.offer(Preparation(key, frame, preparedGeneration, mutableListOf(onPrepared)))
        drainPreparations()
    }

    /** Reroute/source reset: release future images and cancel future work, leaving the visible map intact. */
    fun clearPrepared() {
        requireMain()
        clearPreparedState()
    }

    fun render(position: Position?, guidance: Guidance?, units: String, geometry: TurnGeometry? = null, callback: (Bitmap, String) -> Unit) {
        requireMain()
        if (closed) return
        val startedAtMs = SystemClock.elapsedRealtime()
        val fresh = position?.takeIf {
            ArtworkPolicy.validPosition(it.latitude, it.longitude, it.accuracy.toDouble(), it.receivedAt, SystemClock.elapsedRealtime())
        }
        val currentIdentity = identity(guidance, units)
        val incomingKey = geometry?.key?.takeIf { it.isNotBlank() && it.length <= 256 }
        val locked = turn.accepts(currentIdentity, incomingKey)
        val validGeometry = if (locked) null else geometry?.takeIf { guidance != null && TurnArtworkPolicy.validGeometry(it) }
        val futureKey = validGeometry?.let { preparedKey(it.key, units) }
        val hasPreparedImage = futureKey?.let { prepared.peek(it)?.background?.isRecycled == false } == true
        val preparedFrame = futureKey?.let { key -> prepared.peek(key)?.frame ?:
            active?.preparation?.takeIf { it.key == key }?.frame ?:
            preparations.find(key)?.frame }
        val candidate = preparedFrame ?: fresh?.let { point -> validGeometry?.let { TurnArtworkPolicy.frame(point, it, guidance?.instruction) } }
        val selection = turn.select(currentIdentity, candidate, incomingKey)
        if (selection.invalidated) {
            val futureJob = active?.preparation
            val keepContextJob = allowCurrentContext && selection.frame == null && fresh != null && active?.frame?.let {
                CurrentContextPolicy.canDisplay(it,fresh,startedAtMs) } == true
            if (!keepContextJob && (futureJob == null || !hasPreparedImage && futureJob.key != futureKey)) cancelJob(requeuePreparation = true)
            if (!allowCurrentContext || fresh == null || cached?.let { CurrentContextPolicy.canDisplay(it.frame,fresh,startedAtMs) } != true) clearCached()
            nextAllowedAt = 0; mapFailed = false
            lastEmissionKey = null; lastOutput = null
        }
        val frame = selection.frame ?: fresh?.takeIf { allowCurrentContext && guidance != null }?.let { point ->
            active?.frame?.takeIf { CurrentContextPolicy.canDisplay(it,point,startedAtMs) }
                ?: cached?.takeIf { contextReusable(it,point,startedAtMs) }?.frame
                ?: CurrentContextPolicy.frame(point,startedAtMs)
        }
        val request = Request(++serial, fresh, guidance, units, frame, callback, startedAtMs)
        latest = request
        main.removeCallbacks(deferred)
        if (frame == null) {
            if (active?.preparation == null) cancelJob()
            clearCached()
            emit(request, null, "Turn location unconfirmed · direction card")
            drainPreparations()
            return
        }
        adoptPrepared(request)
        val map = displayCache(request)
        if (map != null) cacheHits++
        emit(request, map, when {
            map != null -> mapStatus(map)
            mapFailed -> "Map unavailable · direction card"
            frame.currentContext -> "Loading current area · direction card"
            else -> "Loading map · direction card"
        })
        if (latest === request && !closed) ensureMap(request)
    }

    override fun close() {
        requireMain()
        if (closed) return
        closed = true; resetState(false); disk.close()
    }

    /** Explicit settings/theme/session reset. Ordinary movement and distance do not reset a turn. */
    fun resetTurn(keepPrepared: Boolean = false) {
        requireMain()
        if (closed) return
        resetState(keepPrepared)
    }

    private fun resetState(keepPrepared: Boolean) {
        val keepContext = allowCurrentContext && keepPrepared && cached?.frame?.currentContext == true
        serial++; latest = null; turn.reset(); main.removeCallbacks(deferred)
        if (!keepPrepared) clearPreparedState()
        if (active?.preparation == null) cancelJob()
        if (!keepContext) clearCached()
        nextAllowedAt = 0; mapFailed = false
        lastEmissionKey = null; lastEmissionStatus = null; lastOutput = null
    }

    /** Stop an obsolete artwork request without discarding the bounded map cache. */
    fun cancelPending() {
        requireMain()
        if (closed) return
        serial++; preparedGeneration++; latest = null; main.removeCallbacks(deferred); clearQueuedPreparations(); cancelJob()
        tripPlan.clear(); futureDiskLoad = null; diskLoading.clear(); diskWriting.clear()
        lastEmissionKey = null; lastEmissionStatus = null; lastOutput = null
    }

    /** Cheap admission check for the first confirmed map or an unresolved failed first attempt. */
    fun needsTurnMap(position: Position?, guidance: Guidance?, geometry: TurnGeometry?): Boolean {
        requireMain()
        if (closed || guidance == null) return false
        val current = identity(guidance, latest?.units ?: "AUTO")
        if (turn.accepts(current, geometry?.key))
            return (active==null || active?.preparation!=null) &&
                (cached==null || cached?.frame?.currentContext==true) && SystemClock.elapsedRealtime() >= nextAllowedAt
        if (position == null || !ArtworkPolicy.validPosition(position.latitude, position.longitude,
                position.accuracy.toDouble(), position.receivedAt, SystemClock.elapsedRealtime())) return false
        if (geometry != null && TurnArtworkPolicy.validGeometry(geometry)) return true
        if (!allowCurrentContext) return false
        val now=SystemClock.elapsedRealtime()
        val puckChanged=CurrentContextPolicy.puckKey(position)!=CurrentContextPolicy.puckKey(latest?.position)
        active?.takeIf { it.preparation==null }?.let {
            return it.frame.currentContext && (!CurrentContextPolicy.canDisplay(it.frame,position,now) || puckChanged)
        }
        val background=cached ?: return now>=nextAllowedAt
        return puckChanged || now>=nextAllowedAt && !contextReusable(background,position,now)
    }

    private fun identity(guidance: Guidance?, units: String): TurnArtworkSession.Identity? = guidance?.let {
        TurnArtworkSession.Identity(it.instruction, it.source, it.demo, units)
    }

    private fun usableCache(request: Request): Cached? {
        val frame = request.frame ?: return null
        return cached?.takeIf {
            it.frame === frame && (it.background?.isRecycled == false || it.snapshot?.bitmap?.isRecycled == false)
        }
    }

    private fun contextReusable(map: Cached, point: Position, now: Long): Boolean =
        CurrentContextPolicy.canReuse(map.frame,map.readyAtMs,point,now) && contextVisible(map,point)

    private fun contextVisible(map: Cached, point: Position): Boolean = map.snapshot?.let {
        visible(it,org.bluetoothauto.navigation.GeoPoint(point.latitude,point.longitude)) } == true

    /** Keep a real area visible while its replacement/confirmed-turn image is being prepared. */
    private fun displayCache(request: Request): Cached? = usableCache(request) ?: cached?.takeIf { map ->
        allowCurrentContext && request.guidance!=null && request.position?.let { point ->
            CurrentContextPolicy.canDisplay(map.frame,point,SystemClock.elapsedRealtime()) && contextVisible(map,point)
        } == true
    }

    private fun mapStatus(map: Cached) = if(map.frame.currentContext) "Current area" else "Upcoming turn"

    private fun ensureMap(request: Request) {
        if (closed || latest !== request) return
        val frame = request.frame ?: return
        if (adoptPrepared(request)) emit(request, usableCache(request), "Upcoming turn")
        if (usableCache(request) != null) { drainPreparations(); return }
        val diskKey = preparedKey(frame.geometryKey, request.units)
        if (tripFingerprint != null && diskKey != null) {
            if (diskKey in diskLoading) return
            if (diskKey !in diskTried || diskKey in diskReady) { loadCurrentFromDisk(request, diskKey); return }
        }
        active?.let {
            val key=preparedKey(frame.geometryKey,request.units)
            if (it.frame === frame || key!=null && it.preparation?.key == key) return
            cancelJob(requeuePreparation = true)
        }
        val wait = ArtworkPolicy.delay(SystemClock.elapsedRealtime(), nextAllowedAt)
        if (wait > 0) { main.removeCallbacks(deferred); main.postDelayed(deferred, wait); return }
        val queued = preparedKey(frame.geometryKey, request.units)?.let(preparations::take)
        startSnapshot(frame, queued)
    }

    private fun startSnapshot(frame: TurnArtworkPolicy.Frame, preparation: Preparation? = null) {
        if (closed || active != null) return
        try {
            MapLibre.getInstance(context)
            HttpRequestUtil.setLogEnabled(false)
            val options = MapSnapshotter.Options(SIZE, SIZE).withPixelRatio(1f)
                .withStyleBuilder(TurnRouteStyle.builder(STYLE, frame))
                .withLogo(false).withAttribution(false)
                .withCameraPosition(CameraPosition.Builder().target(LatLng(frame.center.latitude, frame.center.longitude))
                    .zoom(frame.zoom).bearing(frame.bearingDegrees).tilt(0.0).build())
            val job = Job(frame, MapSnapshotter(context, options), preparation)
            active = job
            job.timeout = Runnable { fail(job) }.also { main.postDelayed(it, ArtworkPolicy.SNAPSHOT_TIMEOUT_MILLIS) }
            // Queue delivery after the SDK resets its own callback state; consumers always run on main.
            snapshotRequests++
            if (preparation != null) prefetchSnapshotRequests++
            job.snapshotter.start({ snapshot -> main.post { complete(job, snapshot) } }, { _ -> main.post { fail(job) } })
        } catch (_: Exception) {
            cancelJob(notifyPreparation = false)
            if (preparation != null) preparationFailed(preparation) else failed()
        } catch (_: LinkageError) {
            cancelJob(notifyPreparation = false)
            if (preparation != null) preparationFailed(preparation) else failed()
        }
    }

    private fun complete(job: Job, snapshot: MapSnapshot) {
        if (closed || active !== job) return
        job.timeout?.let(main::removeCallbacks); active = null
        if (snapshot.bitmap.isRecycled || snapshot.bitmap.width <= 0 || snapshot.bitmap.height <= 0) {
            if (job.preparation != null) preparationFailed(job.preparation) else failed(); return
        }
        val junction = job.frame.junction
        // The map itself, rather than a post-snapshot Canvas overlay, owns route rendering.
        val hasNativeRoute = junction != null &&
            job.snapshotter.getSource(TurnRouteStyle.SOURCE_ID) is GeoJsonSource &&
            job.snapshotter.getLayer(TurnRouteStyle.LINE_ID) is LineLayer &&
            job.snapshotter.getLayer(TurnRouteStyle.CASING_ID) is LineLayer
        val validImage = if(job.frame.currentContext) visible(snapshot,job.frame.center)
            else hasNativeRoute && visible(snapshot,junction) && job.frame.path.all { visible(snapshot,it) }
        if (!validImage) {
            if (job.preparation != null) preparationFailed(job.preparation) else failed(); return
        }
        // Successful native duration includes tile loading/rendering and main callback delivery;
        // cancelled/failed jobs, PNG persistence, footer composition and AVRCP are not included.
        lastSnapshotMs = (SystemClock.elapsedRealtime() - job.startedAtMs).coerceAtLeast(0)
        maxSnapshotMs = maxOf(maxSnapshotMs, lastSnapshotMs)
        totalSnapshotMs += lastSnapshotMs
        completedSnapshotCount++
        if (hasNativeRoute) nativeRouteSnapshots++
        val preparation = job.preparation
        if (preparation != null) {
            val temporary = Cached(job.frame, snapshot)
            try {
                val background = staticBackground(temporary)
                temporary.background = null // Transfer exclusive image ownership to the prepared cache.
                prepared.put(preparation.key, PreparedMap(job.frame, background))
                persist(preparation.key, background)
            } catch (_: Exception) { preparationFailed(preparation); return }
            catch (_: OutOfMemoryError) { prepared.clear(); preparationFailed(preparation); return }
            prefetchNextAllowedAt = 0
            latest?.let { current -> if (adoptPrepared(current)) emit(current, usableCache(current), "Upcoming turn") }
            finishPreparation(preparation, true)
            drainPreparations()
            return
        }
        clearCached()
        cached = Cached(job.frame, snapshot); mapFailed = false; mapRevision++
        val current = latest ?: return
        val usable = usableCache(current)
        if (usable != null) {
            emit(current, usable, mapStatus(usable))
            preparedKey(job.frame.geometryKey, current.units)?.let { persist(it, staticBackground(usable)) }
            drainPreparations()
        }
        else { clearCached(); failed() }
    }

    private fun fail(job: Job) {
        if (closed || active !== job) return
        cancelJob(notifyPreparation = false)
        if (job.preparation != null) preparationFailed(job.preparation) else failed()
    }

    private fun failed() {
        if (closed) return
        mapFailed = true
        nextAllowedAt = SystemClock.elapsedRealtime() + ArtworkPolicy.FAILURE_BACKOFF_MILLIS
        latest?.let { request ->
            val map = displayCache(request)
            emit(request, map, if (map == null) "Map unavailable · direction card" else mapStatus(map))
        }
    }

    private fun cancelJob(requeuePreparation: Boolean = false, notifyPreparation: Boolean = true) {
        val job = active ?: return
        active = null; job.timeout?.let(main::removeCallbacks)
        try { job.snapshotter.cancel() } catch (_: RuntimeException) { }
        job.preparation?.let { preparation ->
            if (requeuePreparation && !closed && preparations.offer(preparation, first = true)) Unit
            else if (notifyPreparation) finishPreparation(preparation, false)
        }
    }

    private fun preparedKey(geometryKey: String?, units: String): PreparedTurnKey? =
        geometryKey?.takeIf { it.isNotBlank() && it.length <= 256 && units.length <= 32 }
            ?.let { PreparedTurnKey(it, units.uppercase(java.util.Locale.ROOT), STYLE_REVISION) }

    private fun adoptPrepared(request: Request): Boolean {
        if (usableCache(request) != null) return false
        val frame = request.frame ?: return false
        val key = preparedKey(frame.geometryKey, request.units) ?: return false
        val ready = prepared.take(key) ?: return false
        if (ready.background.isRecycled) return false
        clearCached()
        cached = Cached(frame, null, ready.background)
        mapFailed = false; nextAllowedAt = 0; mapRevision++; preparedCacheHits++
        // Render request to prepared background adoption; a disk miss/load or in-flight future
        // snapshot can contribute waiting time. This ends before footer composition/publication.
        lastPreparedAdoptMs = (SystemClock.elapsedRealtime() - request.startedAtMs).coerceAtLeast(0)
        return true
    }

    private fun drainPreparations() {
        if (closed || active != null || futureDiskLoad != null) return
        latest?.let { if (it.frame != null && usableCache(it) == null) return }
        val wait = ArtworkPolicy.delay(SystemClock.elapsedRealtime(), prefetchNextAllowedAt)
        if (wait > 0) { main.removeCallbacks(prefetchDeferred); main.postDelayed(prefetchDeferred, wait); return }
        fillTripQueue()
        val next = preparations.poll() ?: return
        val existing = prepared.peek(next.key)?.background?.takeUnless { it.isRecycled } ?: latest?.let {
            if (preparedKey(it.frame?.geometryKey, it.units) == next.key) usableCache(it)?.let(::staticBackground) else null
        }
        if (existing != null) {
            persist(next.key, existing)
            finishPreparation(next, true); drainPreparations(); return
        }
        if (tripFingerprint != null && (next.key !in diskTried || next.key in diskReady)) { loadFutureFromDisk(next); return }
        startSnapshot(next.frame, next)
    }

    private fun fillTripQueue(reuse: MutableMap<PreparedTurnKey, Preparation>? = null) {
        while (preparations.size < 3) {
            val input = tripPlan.poll() ?: return
            if (active?.preparation?.key == input.key || futureDiskLoad?.key == input.key ||
                preparations.find(input.key) != null) continue
            val preparation = reuse?.remove(input.key) ?: Preparation(input.key,
                TurnArtworkPolicy.frame(input.position, input.geometry, input.instruction), preparedGeneration, mutableListOf(), tripOwned = true)
            preparations.offer(preparation)
        }
    }

    private fun imageKey(key: PreparedTurnKey): PreparedTripImageKey? =
        tripFingerprint?.let { PreparedTripImageKey(it, key) }

    private fun loadCurrentFromDisk(request: Request, key: PreparedTurnKey) {
        val imageKey = imageKey(key) ?: return
        val frame = request.frame ?: return
        val generation = preparedGeneration
        diskTried.add(key); diskLoading.add(key)
        disk.load(imageKey, priority = true) { bitmap ->
            if (closed || generation != preparedGeneration || tripFingerprint != imageKey.trip) {
                bitmap?.recycle(); return@load
            }
            diskLoading.remove(key)
            if (bitmap != null) {
                prepared.put(key, PreparedMap(frame, bitmap)); preparedDiskHits++
                diskReady.add(key); tripPlan.finish(key, true)
            } else diskReady.remove(key)
            latest?.let(::ensureMap)
            drainPreparations()
        }
    }

    private fun loadFutureFromDisk(preparation: Preparation) {
        val imageKey = imageKey(preparation.key) ?: return
        val generation = preparedGeneration
        futureDiskLoad = preparation
        diskTried.add(preparation.key); diskLoading.add(preparation.key)
        disk.load(imageKey, priority = false) { bitmap ->
            if (closed || generation != preparedGeneration || tripFingerprint != imageKey.trip) {
                bitmap?.recycle(); return@load
            }
            if (futureDiskLoad === preparation) futureDiskLoad = null
            diskLoading.remove(preparation.key)
            if (preparation.tripOwned && !tripPlan.contains(preparation.key)) {
                bitmap?.recycle(); finishPreparation(preparation,false); drainPreparations(); return@load
            }
            if (bitmap != null) {
                prepared.put(preparation.key, PreparedMap(preparation.frame, bitmap)); preparedDiskHits++
                diskReady.add(preparation.key); tripPlan.finish(preparation.key, true)
                latest?.let { if (adoptPrepared(it)) emit(it, usableCache(it), "Upcoming turn") }
                finishPreparation(preparation, true)
            } else {
                diskReady.remove(preparation.key)
                if (!preparations.offer(preparation, first = true)) {
                    tripPlan.finish(preparation.key, false); finishPreparation(preparation, false)
                }
            }
            latest?.let(::ensureMap)
            drainPreparations()
        }
    }

    /** Clone before handing ownership to the worker; RAM eviction can never recycle an encoding input. */
    private fun persist(key: PreparedTurnKey, background: Bitmap) {
        val imageKey = imageKey(key) ?: return
        if (key in diskReady) { tripPlan.finish(key, true); return }
        if (!diskWriting.add(key)) return
        val generation = preparedGeneration
        val wasPlanned = tripPlan.contains(key)
        val owned = try { background.copy(Bitmap.Config.ARGB_8888, false) }
            catch (_: OutOfMemoryError) { null }
            catch (_: RuntimeException) { null }
        if (owned == null) {
            diskWriting.remove(key); tripPlan.finish(key, false)
            prefetchNextAllowedAt = SystemClock.elapsedRealtime() + ArtworkPolicy.FAILURE_BACKOFF_MILLIS
            main.removeCallbacks(prefetchDeferred); main.postDelayed(prefetchDeferred, ArtworkPolicy.FAILURE_BACKOFF_MILLIS)
            return
        }
        disk.save(imageKey, owned) { result ->
            if (closed || generation != preparedGeneration || tripFingerprint != imageKey.trip) return@save
            diskWriting.remove(key)
            if (wasPlanned && !tripPlan.contains(key)) { drainPreparations(); return@save }
            tripPlan.finish(key, result.saved)
            if (result.saved) diskReady.add(key)
            else prefetchNextAllowedAt = SystemClock.elapsedRealtime() + ArtworkPolicy.FAILURE_BACKOFF_MILLIS
            if (result.evictedImageHashes.isNotEmpty()) {
                val evicted = diskReady.filter { this.imageKey(it)?.imageHash in result.evictedImageHashes }
                evicted.forEach { diskReady.remove(it); tripPlan.forgetSaved(it) }
                tripDiskEvictions += result.evictedImageHashes.size
            }
            drainPreparations()
        }
    }

    private fun preparationFailed(preparation: Preparation) {
        if (closed) return
        tripPlan.finish(preparation.key, false)
        prefetchNextAllowedAt = SystemClock.elapsedRealtime() + ArtworkPolicy.FAILURE_BACKOFF_MILLIS
        finishPreparation(preparation, false)
        val current = latest
        if (current != null && preparedKey(current.frame?.geometryKey, current.units) == preparation.key && usableCache(current) == null) failed()
        if (preparations.isNotEmpty() || tripPlan.hasWork) {
            main.removeCallbacks(prefetchDeferred)
            main.postDelayed(prefetchDeferred, ArtworkPolicy.FAILURE_BACKOFF_MILLIS)
        }
    }

    private fun finishPreparation(preparation: Preparation, success: Boolean) {
        if (!success) tripPlan.finish(preparation.key, false)
        val callbacks = preparation.callbacks.toList(); preparation.callbacks.clear()
        callbacks.forEach { callback -> main.post { callback(success && !closed && preparation.generation == preparedGeneration) } }
    }

    private fun clearQueuedPreparations() {
        val removed = preparations.clearAndTake()
        removed.forEach { finishPreparation(it, false) }
    }

    private fun clearPreparedState() {
        preparedGeneration++
        main.removeCallbacks(prefetchDeferred); prefetchNextAllowedAt = 0
        if (active?.preparation != null) cancelJob()
        clearQueuedPreparations(); prepared.clear()
        tripPlan.clear(); futureDiskLoad = null
        diskTried.clear(); diskLoading.clear(); diskReady.clear(); diskWriting.clear()
    }

    private fun clearCached() {
        cached?.background?.let { if (!it.isRecycled) it.recycle() }
        cached = null
    }

    private fun pixel(snapshot: MapSnapshot, point: org.bluetoothauto.navigation.GeoPoint): PointF? = try {
        snapshot.pixelForLatLng(LatLng(point.latitude, point.longitude)).takeIf { it.x.isFinite() && it.y.isFinite() }
    } catch (_: RuntimeException) { null }

    private fun visible(snapshot: MapSnapshot, point: org.bluetoothauto.navigation.GeoPoint): Boolean =
        pixel(snapshot, point)?.let { it.x in 22f..490f && it.y in 16f..352f } == true

    private fun emit(request: Request, map: Cached?, status: String) {
        if (closed || latest !== request || serial != request.token) return
        val key = ImageKey(identity(request.guidance, request.units), request.guidance?.let {
            GuidanceFormatter.formatDistance(it.distanceMeters, it.distanceLabel, request.units)
        }.orEmpty(), if (map == null) 0 else mapRevision,
            if(map?.frame?.currentContext==true)CurrentContextPolicy.puckKey(request.position) else null)
        val displayStatus = if (request.guidance?.demo == true) "Demo · $status" else status
        if (lastEmissionKey == key) {
            if (lastEmissionStatus != displayStatus) lastOutput?.let { request.callback(it, displayStatus) }
            lastEmissionStatus = displayStatus
            return
        }
        val bitmap = compose(request, map)
        lastEmissionKey = key; lastEmissionStatus = displayStatus; lastOutput = bitmap
        if (!closed && latest === request) request.callback(bitmap, displayStatus)
    }

    private fun compose(request: Request, map: Cached?): Bitmap {
        compositionCount++
        val image = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        image.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val background = map?.let { staticBackground(it) }
        if (background != null && !background.isRecycled) canvas.drawBitmap(background, null, Rect(0, 0, SIZE, SIZE), paint)
        else {
            paint.shader = LinearGradient(0f, 0f, 512f, 512f, intArrayOf(0xff122b35.toInt(), 0xff07141f.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 512f, 512f, paint); paint.shader = null
        }
        val guidance = request.guidance
        if(map?.frame?.currentContext==true){
            drawCurrentPosition(canvas,map,request.position)
            drawCurrentAreaLabel(canvas)
        }
        if (map == null) drawLargeGlyph(canvas, guidance, 166f, 94f, 180f)

        paint.color = 0xf5102430.toInt()
        canvas.drawRoundRect(RectF(12f, ArtworkLayout.DISTANCE_TOP, 500f, ArtworkLayout.INSTRUCTION_BOTTOM), 16f, 16f, paint)
        val distance = if (guidance == null) "Ready to drive" else
            GuidanceFormatter.formatDistance(guidance.distanceMeters, guidance.distanceLabel, request.units).ifBlank { "Next turn" }
        val distanceLabel = if (guidance?.demo == true) "DEMO · $distance" else distance
        fittedText(canvas, distanceLabel, if (guidance == null) 38f else ArtworkLayout.DISTANCE_TEXT_SIZE, 390f,
            30f, ArtworkLayout.DISTANCE_BASELINE, Color.WHITE)
        drawLargeGlyph(canvas, guidance, 438f, 402f, 48f)
        val body = if (guidance == null) "Music & navigation\nChoose a destination on the phone" else clean(guidance.instruction, 300).ifBlank { "Follow navigation on the phone" }
        drawLines(canvas, body, 30f, ArtworkLayout.INSTRUCTION_BASELINE, 452f, ArtworkLayout.INSTRUCTION_TEXT_SIZE, 2)
        return image
    }

    private fun staticBackground(map: Cached): Bitmap {
        val previous = map.background
        if (previous != null && !previous.isRecycled) return previous
        val snapshot = requireNotNull(map.snapshot) { "Prepared map background ownership was lost" }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(snapshot.bitmap, null, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
        drawCredits(canvas)
        staticLayerBuilds++
        map.background = bitmap
        previous?.let { if (!it.isRecycled) it.recycle() }
        return bitmap
    }

    private fun drawCredits(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ArtworkLayout.CREDIT_BACKGROUND
        canvas.drawRoundRect(RectF(12f, ArtworkLayout.CREDIT_TOP, 500f, ArtworkLayout.CREDIT_BOTTOM), 7f, 7f, paint)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ArtworkLayout.CREDIT_INK; textSize = ArtworkLayout.CREDIT_TEXT_SIZE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val width = label.measureText(ArtworkLayout.CREDITS)
        if (width > 466f) label.textSize *= 466f / width
        // The fixed required credit is never ellipsized or partially removed.
        canvas.drawText(ArtworkLayout.CREDITS, 23f, ArtworkLayout.CREDIT_BASELINE, label)
    }

    private fun drawCurrentAreaLabel(canvas: Canvas) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xe8102430.toInt() }
        canvas.drawRoundRect(RectF(12f,12f,162f,46f),10f,10f,paint)
        text(canvas,"Current area",20f,Color.WHITE,23f,36f)
    }

    private fun drawCurrentPosition(canvas: Canvas, map: Cached, position: Position?) {
        val point=position ?: return
        val snapshot=map.snapshot ?: return
        val projected=pixel(snapshot,org.bluetoothauto.navigation.GeoPoint(point.latitude,point.longitude)) ?: return
        if(!visible(snapshot,org.bluetoothauto.navigation.GeoPoint(point.latitude,point.longitude)))return
        val saved=canvas.save();canvas.clipRect(0f,ArtworkLayout.MAP_TOP,SIZE.toFloat(),ArtworkLayout.MAP_BOTTOM)
        canvas.translate(projected.x,projected.y)
        val course=TurnArtworkPolicy.movingCourse(point)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        if(course==null){
            paint.color=Color.WHITE;canvas.drawCircle(0f,0f,13f,paint)
            paint.color=0xff00bba2.toInt();canvas.drawCircle(0f,0f,8f,paint)
        }else{
            canvas.rotate((course-map.frame.bearingDegrees).toFloat())
            val arrow=Path().apply{moveTo(0f,-17f);lineTo(12f,12f);lineTo(0f,7f);lineTo(-12f,12f);close()}
            paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=6f;paint.strokeJoin=Paint.Join.ROUND
            canvas.drawPath(arrow,paint)
            paint.style=Paint.Style.FILL;paint.color=0xff00bba2.toInt();canvas.drawPath(arrow,paint)
        }
        canvas.restoreToCount(saved)
    }

    private fun drawLargeGlyph(canvas: Canvas, guidance: Guidance?, x: Float, y: Float, size: Float) {
        // Notification images can contain padding, backgrounds or an app logo rather than a turn.
        // Draw one original semantic vector instead of recoloring/stretching those bitmaps.
        maneuvers.draw(canvas, guidance?.let { ManeuverGlyph.fromInstruction(it.instruction) }, x, y, size)
    }

    private fun drawLines(canvas: Canvas, value: String, x: Float, baseline: Float, width: Float, size: Float, count: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        var remaining = value.trim()
        for (line in 0 until count) {
            if (remaining.isEmpty()) return
            val newline = remaining.indexOf('\n').takeIf { it >= 0 } ?: remaining.length
            val fits = paint.breakText(remaining, 0, newline, true, width, null)
            var end = fits
            if (fits < newline) remaining.lastIndexOf(' ', fits).takeIf { it > 0 }?.let { end = it }
            val text = if (line == count - 1) TextUtils.ellipsize(remaining.replace('\n', ' '), paint, width, TextUtils.TruncateAt.END).toString() else remaining.take(end)
            canvas.drawText(text, x, baseline + line * (size + 7), paint)
            remaining = remaining.drop(end).trimStart()
        }
    }

    private fun fittedText(canvas: Canvas, value: String, maxSize: Float, width: Float, x: Float, baseline: Float, color: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = maxSize; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        if (paint.measureText(value) > width) paint.textSize = maxOf(22f, maxSize * width / paint.measureText(value))
        canvas.drawText(TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString(), x, baseline, paint)
    }

    private fun text(canvas: Canvas, value: String, size: Float, color: Int, x: Float, baseline: Float, align: Paint.Align = Paint.Align.LEFT) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; textAlign = align; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        canvas.drawText(value, x, baseline, paint)
    }

    private fun clean(text: String, limit: Int) = text.take(limit).replace(Regex("[\\r\\n\\t]+"), " ").trim()
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Artwork renderer requires the main thread" }

    companion object {
        const val SIZE = 512
        const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
        private const val STYLE_REVISION = "openfreemap-liberty:turn-artwork-v7-whole-route-focus"
    }
}
