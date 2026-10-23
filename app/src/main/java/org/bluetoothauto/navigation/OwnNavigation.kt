// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.bluetoothauto.GuidanceHub
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Process-local trip controller. The foreground media service supplies real position fixes. */
object OwnNavigation {
    const val KEY = "OWN_NAVIGATION"
    data class Snapshot(
        val route: RoutePlan? = null,
        val progress: RouteTracker.Progress? = null,
        val destination: OwnMapProvider.Place? = null,
        val position: Position? = null,
        val routing: Boolean = false,
        val error: String? = null,
        val guidance: Guidance? = null
    )
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "own-map-route").apply { isDaemon = true } }
    private val provider = OwnMapProvider()
    private var app: Context? = null
    private var pending: Future<*>? = null
    private var generation = 0L
    private var tracker: RouteTracker? = null
    private var lastRerouteAt = -60_000L
    var snapshot = Snapshot(); private set
    val observers = linkedSetOf<(Snapshot) -> Unit>()
    val active: Boolean get() = snapshot.routing || (snapshot.route != null && snapshot.progress?.arrived != true)
    val guidance: Guidance? get() = snapshot.guidance

    fun initialize(context: Context) { requireMain(); app = context.applicationContext }

    fun start(context: Context, destination: OwnMapProvider.Place) {
        initialize(context)
        val position = snapshot.position
        if (position == null || !usable(position)) {
            change(snapshot.copy(error = "Waiting for your location. Move near a window or check location access."))
            return
        }
        calculate(destination, position, false)
    }

    fun stop() {
        requireMain(); generation++; pending?.cancel(true); pending = null; provider.cancel(); tracker = null
        val point = snapshot.position
        snapshot = Snapshot(position = point)
        GuidanceHub.remove(KEY)
        notifyObservers()
    }

    fun updatePosition(context: Context, position: Position) {
        initialize(context)
        if (!usable(position)) return
        val before = snapshot.position
        if (before != null && position.receivedAt <= before.receivedAt) return
        var next = snapshot.copy(position = position)
        val route = next.route
        if (route != null) {
            val progress = tracker?.update(GeoPoint(position.latitude, position.longitude),
                position.accuracy.toDouble(), position.receivedAt, position.bearing)
            if (progress != null) next = next.copy(progress = progress)
            val progressNow = next.progress
            if (!next.routing && progressNow?.offRoute == true && next.destination != null &&
                SystemClock.elapsedRealtime() - lastRerouteAt >= 30_000) {
                snapshot = next
                calculate(next.destination, position, true)
                return
            }
            if (!next.routing) next = next.copy(guidance = toGuidance(route, progressNow))
        }
        change(next)
    }

    private fun calculate(destination: OwnMapProvider.Place, origin: Position, reroute: Boolean) {
        generation++; val token = generation
        pending?.cancel(true); provider.cancel()
        lastRerouteAt = SystemClock.elapsedRealtime()
        change(snapshot.copy(routing = true, error = null,
            guidance = Guidance(if (reroute) "Recalculating route" else "Calculating route", source = "Own maps")))
        pending = worker.submit {
            try {
                val route = provider.route(GeoPoint(origin.latitude, origin.longitude), destination, origin.bearing)
                if (Thread.currentThread().isInterrupted) return@submit
                main.post {
                    if (token != generation) return@post
                    tracker = RouteTracker(route)
                    val position = snapshot.position?.takeIf(::usable)
                    val progress = position?.let { tracker!!.update(GeoPoint(it.latitude, it.longitude),
                        it.accuracy.toDouble(), it.receivedAt, it.bearing) }
                    app?.let { OwnDestinations.recordRecent(it, destination) }
                    change(snapshot.copy(route = route, progress = progress, destination = destination,
                        routing = false, error = null, guidance = toGuidance(route, progress)))
                }
                // Route first; one optional bounded speed-limit pass never delays starting guidance.
                val enriched = provider.enrichRoute(route)
                if (Thread.currentThread().isInterrupted) return@submit
                main.post {
                    if (token != generation || snapshot.route !== route) return@post
                    pending = null
                    if (enriched !== route) {
                        val current = tracker ?: return@post
                        val progress = current.updatePlan(enriched) ?: snapshot.progress
                        change(snapshot.copy(route = enriched, progress = progress,
                            guidance = toGuidance(enriched, progress)))
                    }
                }
            } catch (_: InterruptedException) { /* The trip or selected destination changed. */ }
            catch (_: Exception) {
                if (Thread.currentThread().isInterrupted) return@submit
                main.post {
                    if (token != generation) return@post
                    pending = null
                    val old = snapshot.route
                    change(snapshot.copy(routing = false,
                        error = "Couldn’t get directions. Check your internet connection and try again.",
                        guidance = old?.let { toGuidance(it, snapshot.progress) }))
                }
            }
        }
    }

    private fun toGuidance(route: RoutePlan, progress: RouteTracker.Progress?): Guidance {
        if (progress?.arrived == true) return Guidance("You have arrived at ${route.destinationName}", source = "Own maps")
        if (progress?.offRoute == true) return Guidance("Off route · check directions on your phone", source = "Own maps")
        if (progress != null && !progress.usableFix) return Guidance("Waiting for an accurate location", source = "Own maps")
        val index = progress?.maneuverIndex ?: 0
        val maneuver = route.maneuvers[index.coerceIn(route.maneuvers.indices)]
        val distance = progress?.nextManeuverMeters
        return Guidance(maneuver.instruction, distanceMeters = distance, source = "Own maps")
    }

    private fun usable(point: Position): Boolean {
        val age = SystemClock.elapsedRealtime() - point.receivedAt
        return !point.demo && point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
            point.accuracy.isFinite() && point.accuracy in 0f..60f && age in 0..30_000
    }

    private fun change(next: Snapshot) {
        snapshot = next
        next.guidance?.let { GuidanceHub.publish(KEY, it) } ?: GuidanceHub.remove(KEY)
        notifyObservers()
    }
    private fun notifyObservers() { observers.toList().forEach { it(snapshot) } }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
}
