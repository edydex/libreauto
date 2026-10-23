package org.openandroidauto

/** Location mode is optional; denied location must not prevent a car connection. */
internal object ProjectionForegroundPolicy {
    enum class LocationMode { ENABLED, PERMISSION_MISSING, DISABLED, UNAVAILABLE, START_REJECTED }

    /** promote(false) always requests connected-device mode alone. No permission is requested here. */
    fun start(fineGranted: Boolean, coarseGranted: Boolean, locationEnabled: Boolean?,
              promote: (includeLocation: Boolean) -> Unit): LocationMode {
        val mode = when {
            !fineGranted && !coarseGranted -> LocationMode.PERMISSION_MISSING
            locationEnabled == null -> LocationMode.UNAVAILABLE
            !locationEnabled -> LocationMode.DISABLED
            else -> LocationMode.ENABLED
        }
        try {
            promote(mode == LocationMode.ENABLED)
        } catch (denied: SecurityException) {
            if (mode != LocationMode.ENABLED) throw denied
            // A grant/while-in-use eligibility can change between the check and
            // Android's promotion. Keep the ordinary car connection available.
            promote(false)
            return LocationMode.START_REJECTED
        }
        return mode
    }
}

/** A failed foreground promotion cannot start transport, including from a denial callback. */
internal class ProjectionForegroundStartup {
    var isReady = false; private set
    private var attempted = false

    fun start(promote: () -> Unit, isDenied: (Exception) -> Boolean,
              onDenied: () -> Unit): Boolean {
        check(!attempted)
        attempted = true
        try { promote() }
        catch (error: Exception) {
            if (!isDenied(error)) throw error
            onDenied()
            return false
        }
        isReady = true
        return true
    }

    fun runIfReady(action: () -> Unit): Boolean {
        if (!isReady) return false
        action()
        return true
    }
}
