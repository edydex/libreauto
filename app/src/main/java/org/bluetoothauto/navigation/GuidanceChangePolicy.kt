// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance

/** A new current junction discards its old picture; confirmed route continuity can retain future ones. */
object GuidanceChangePolicy {
    private val transientInstruction = Regex("recalculat|calculat|off[ -]route|waiting", RegexOption.IGNORE_CASE)

    fun startsNewTurn(previous: Guidance?, next: Guidance?): Boolean {
        if (previous == null || next == null) return previous != next
        if (previous.source != next.source || previous.instruction != next.instruction || previous.demo != next.demo) return true
        val before = previous.distanceMeters?.takeIf { it.isFinite() && it >= 0 } ?: return false
        val after = next.distanceMeters?.takeIf { it.isFinite() && it >= 0 } ?: return false
        return after >= before + 75 && after >= before * 1.8
    }

    /** Route continuity is supplied by the route owner, never inferred from repeated wording. */
    fun shouldKeepPrepared(previous: Guidance?, next: Guidance?, confirmedRouteContinues: Boolean): Boolean {
        if (previous == null || next == null || previous.source != next.source || previous.demo != next.demo) return false
        if (transientInstruction.containsMatchIn(previous.instruction) || transientInstruction.containsMatchIn(next.instruction)) return false
        return previous.instruction != next.instruction || confirmedRouteContinues
    }
}
