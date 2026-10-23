// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

/** Main-thread callback ticket: late/repeated service callbacks cannot start a second transition. */
internal class CarDisplayTransition {
    private enum class Phase { PROJECTION, PLAYBACK, APPLY, COMPLETE }
    private var phase = Phase.PROJECTION
    val complete get() = phase == Phase.COMPLETE
    fun acceptProjectionResult(): Boolean {
        if (phase != Phase.PROJECTION) return false
        phase = Phase.PLAYBACK
        return true
    }
    fun acceptPlaybackResult(): Boolean {
        if (phase != Phase.PLAYBACK) return false
        phase = Phase.APPLY
        return true
    }
    /** The stop deadline owns only teardown. Once helper application begins, its own
     * bounded result callback owns completion and the projection gate must stay closed.
     */
    fun stopTimedOut(): Boolean {
        if (phase == Phase.APPLY || complete) return false
        return finish()
    }
    fun finish(): Boolean {
        if (complete) return false
        phase = Phase.COMPLETE
        return true
    }
}
