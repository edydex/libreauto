// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

/** Main-thread request policy. Cached graph matching does not consume a network allowance. */
internal class TurnGeometryBackoff {
    private var minimumNextAtMillis = 0L
    @Volatile var nextAllowedAtMillis = 0L
        private set
    @Volatile var consecutiveFailures = 0
        private set
    @Volatile var lastFailureStatus = ""
        private set

    fun retryInMillis(nowMillis: Long): Long = (nextAllowedAtMillis - nowMillis.coerceAtLeast(0)).coerceAtLeast(0)

    fun started(nowMillis: Long) {
        minimumNextAtMillis = maxOf(minimumNextAtMillis, deadline(nowMillis, MINIMUM_INTERVAL_MILLIS))
        nextAllowedAtMillis = maxOf(nextAllowedAtMillis, minimumNextAtMillis)
    }

    /** Call only for an actual completed network attempt in the current, uncancelled generation. */
    fun failed(nowMillis: Long, status: String) {
        if (status == TurnGeometryTransport.State.CANCELLED.name) return
        lastFailureStatus = status.takeIf { it in FAILURE_STATES || it.matches(Regex("HTTP_[1-5][0-9]{2}")) } ?: "ERROR"
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(6)
        val wait = (MINIMUM_INTERVAL_MILLIS shl (consecutiveFailures - 1)).coerceAtMost(MAXIMUM_BACKOFF_MILLIS)
        nextAllowedAtMillis = maxOf(nextAllowedAtMillis, deadline(nowMillis, wait))
    }

    /** A valid fetch or confirmed cached match clears errors, while retaining minimum request spacing. */
    fun succeeded() { consecutiveFailures = 0; lastFailureStatus = ""; nextAllowedAtMillis = minimumNextAtMillis }

    private fun deadline(nowMillis: Long, delayMillis: Long): Long {
        val now = nowMillis.coerceAtLeast(0)
        return if (now > Long.MAX_VALUE - delayMillis) Long.MAX_VALUE else now + delayMillis
    }

    companion object {
        const val MINIMUM_INTERVAL_MILLIS = 30_000L
        const val MAXIMUM_BACKOFF_MILLIS = 300_000L
        private val FAILURE_STATES = setOf("TIMEOUT", "PARSE", "TOO_LARGE", "NETWORK", "TLS", "IO", "ERROR", "HTTP_ERROR")
    }
}
