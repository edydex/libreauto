// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

/** Pure cadence policy: all times are monotonic elapsed milliseconds, all distances are supplied data. */
internal object DistanceRefreshPolicy {
    fun intervalMillis(distanceMeters: Double?): Long = when (bucket(distanceMeters)) {
        0 -> 1000L
        1 -> 2000L
        else -> 5000L
    }

    fun locationIntervalMillis(distanceMeters: Double?): Long = when (bucket(distanceMeters)) {
        0 -> 1000L
        1 -> 2000L
        else -> 3000L
    }

    /**
     * Schedule from the last accepted render, never from the latest incoming sample. The caller
     * keeps one timer and consumes its latest pending content when due. A nearer turn may advance
     * that timer; a farther turn may let one already-scheduled update finish early.
     *
     * Keep existingDueAt while a fired callback rechecks this policy; clear it only when rendering
     * is accepted or no pending content remains. Otherwise the callback can slide its own deadline.
     * No changed content cancels an obsolete timer. Instruction/source/geometry/clear and explicit
     * user refresh use immediate=true. The caller determines freshness, actual movement, formatted
     * distance changes and map-cache age; this policy never predicts a countdown or creates a fix.
     */
    fun dueAtMillis(
        now: Long,
        lastRenderedAt: Long,
        distanceMeters: Double?,
        hasChanges: Boolean,
        immediate: Boolean = false,
        existingDueAt: Long? = null
    ): Long? {
        if (immediate) return now
        if (!hasChanges) return null
        if (lastRenderedAt < 0 || lastRenderedAt > now) return now
        val interval = intervalMillis(distanceMeters)
        val calculated = if (lastRenderedAt > Long.MAX_VALUE - interval) Long.MAX_VALUE else lastRenderedAt + interval
        val existing = existingDueAt?.takeIf { it >= lastRenderedAt }
        return maxOf(now, if (existing == null) calculated else minOf(existing, calculated))
    }

    private fun bucket(distance: Double?): Int = when {
        distance == null || !distance.isFinite() || distance < 0 -> 2
        distance <= 150.0 -> 0
        distance <= 500.0 -> 1
        else -> 2
    }
}
