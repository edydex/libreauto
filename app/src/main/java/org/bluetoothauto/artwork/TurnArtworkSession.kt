// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

/** A turn preview is a fixed junction diagram, never a moving-position map. */
internal class TurnArtworkSession {
    data class Identity(val instruction: String, val source: String, val demo: Boolean, val units: String)
    data class Selection(val frame: TurnArtworkPolicy.Frame?, val invalidated: Boolean)
    private var identity: Identity? = null
    private var locked: TurnArtworkPolicy.Frame? = null

    fun accepts(current: Identity?, geometryKey: String?): Boolean = identity == current && locked != null &&
        (geometryKey == null || geometryKey == locked?.geometryKey)

    /** A missing/temporarily unavailable fix cannot move or erase a previously confirmed junction. */
    fun select(current: Identity?, candidate: TurnArtworkPolicy.Frame?, incomingGeometryKey: String? = candidate?.geometryKey): Selection {
        val changed = identity != current || (incomingGeometryKey != null && locked != null && incomingGeometryKey != locked?.geometryKey)
        if (changed || current == null) { identity = current; locked = null }
        if (current != null && locked == null && candidate?.geometryKey != null && candidate.junction != null) locked = candidate
        return Selection(locked, changed)
    }

    fun reset() { identity = null; locked = null }
}
