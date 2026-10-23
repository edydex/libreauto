// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** One transition at a time. It never creates a playback/projection service merely to stop it. */
object CarDisplayController {
    private const val PREFS = "libreauto_connection"
    private const val MODE = "car_display_mode"
    private const val PREFERRED_PROJECTOR = "preferred_projection_mode"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var switching = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    interface Runtime {
        /** Return true only after workers, audio output, sockets and wake locks are released. */
        fun stopProjection(onStopped: (Boolean) -> Unit)
        /** Return true only after VLC output and audio focus are released; never resume automatically. */
        fun stopBluetoothPlayback(onStopped: (Boolean) -> Unit)
    }
    enum class Status { READY, MANUAL_ACTION_REQUIRED, FAILED, BUSY }
    data class Result(val mode: CarDisplayMode, val status: Status, val message: String)

    fun mode(context: Context): CarDisplayMode = CarDisplayMode.fromStored(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, null))
    /** Independent of a car override or a temporary Bluetooth-only selection. */
    fun preferredProjector(context: Context): CarDisplayMode = when (
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREFERRED_PROJECTOR, null)) {
        CarDisplayMode.LIBREAUTO.name -> CarDisplayMode.LIBREAUTO
        else -> CarDisplayMode.OFFICIAL_AA
    }
    /** Explicit manual projector choice only. selectMode()/car events never rewrite this key. */
    fun recordPreferredProjector(context: Context, mode: CarDisplayMode): Boolean {
        if (mode == CarDisplayMode.BLUETOOTH) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREFERRED_PROJECTOR, mode.name).commit()
    }
    fun isSwitching() = switching
    fun isProjectionAllowed(context: Context) = !switching && mode(context) == CarDisplayMode.LIBREAUTO
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.forEach { listener -> try { listener() } catch (_: Exception) { } } }

    /** Explicit phone action only. The saved choice by itself starts no projector and changes no AA state. */
    fun selectMode(context: Context, requested: CarDisplayMode, runtime: Runtime, onResult: (Result) -> Unit) {
        onMain {
            val app = context.applicationContext
            if (switching) {
                onResult(Result(mode(app), Status.BUSY, "A car display change is already in progress."))
                return@onMain
            }
            switching = true
            changed()
            val transition = CarDisplayTransition()
            val timeout = Runnable {
                if (transition.stopTimedOut()) {
                    switching = false
                    changed()
                    onResult(Result(mode(app), Status.FAILED, "The previous session has not stopped. Try again after disconnecting."))
                }
            }
            main.postDelayed(timeout, 20_000)
            fun finish(status: Status, message: String) {
                if (!transition.finish()) return
                main.removeCallbacks(timeout)
                switching = false
                changed()
                onResult(Result(mode(app), status, message))
            }
            fun afterStop() {
                if (transition.complete) return
                if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE, requested.name).commit()) {
                    finish(Status.FAILED, "The car display preference could not be saved.")
                    return
                }
                changed()
                // The two bounded teardown waits can consume nearly this entire stop budget.
                // The helper has its own 12-second callback deadline; retain the switching gate
                // for that separate phase instead of timing out a valid in-progress mutation.
                main.removeCallbacks(timeout)
                try {
                    OfficialAutoController.applyForMode(app, requested) { result ->
                        onMain {
                            finish(when (result.kind) {
                                OfficialAutoController.Kind.READY -> Status.READY
                                OfficialAutoController.Kind.MANUAL -> Status.MANUAL_ACTION_REQUIRED
                                OfficialAutoController.Kind.FAILED -> Status.FAILED
                            }, result.message)
                        }
                    }
                } catch (_: Exception) {
                    finish(Status.FAILED, "Android Auto switching could not start. Check its setting before reconnecting.")
                }
            }
            try {
                runtime.stopProjection { projectionStopped -> onMain projectionCallback@{
                    if (!transition.acceptProjectionResult()) return@projectionCallback
                    if (!projectionStopped) finish(Status.FAILED, "The car display session could not be stopped.")
                    else try {
                        runtime.stopBluetoothPlayback { playbackStopped -> onMain playbackCallback@{
                            if (!transition.acceptPlaybackResult()) return@playbackCallback
                            if (!playbackStopped) finish(Status.FAILED, "Music playback has not released its audio output.")
                            else afterStop()
                        } }
                    } catch (_: Exception) { finish(Status.FAILED, "Music playback could not be stopped.") }
                } }
            } catch (_: Exception) { finish(Status.FAILED, "The car display session could not be stopped.") }
        }
    }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
