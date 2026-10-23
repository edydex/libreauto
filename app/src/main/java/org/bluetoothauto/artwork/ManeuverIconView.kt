// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Shared crisp icon for the phone's navigation strip; it draws no map or inferred junction. */
class ManeuverIconView(context: Context) : View(context) {
    private val painter = ManeuverPainter()
    private var glyph: ManeuverGlyph? = null

    init { setInstruction(null) }

    fun setInstruction(instruction: String?) {
        val next = instruction?.let(ManeuverGlyph::fromInstruction)
        if (next == glyph && contentDescription != null) return
        glyph = next
        contentDescription = when (next) {
            null -> "Music"
            ManeuverGlyph.LEFT -> "Turn left"
            ManeuverGlyph.RIGHT -> "Turn right"
            ManeuverGlyph.STRAIGHT -> "Continue straight"
            ManeuverGlyph.SLIGHT_LEFT -> "Bear left"
            ManeuverGlyph.SLIGHT_RIGHT -> "Bear right"
            ManeuverGlyph.UTURN -> "Make a U-turn"
            ManeuverGlyph.ROUNDABOUT -> "Roundabout"
            ManeuverGlyph.ARRIVE -> "Arrived"
            ManeuverGlyph.UNKNOWN -> "Navigation instruction"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        painter.draw(canvas, glyph, paddingLeft + (width - paddingLeft - paddingRight - size) / 2,
            paddingTop + (height - paddingTop - paddingBottom - size) / 2, size)
    }
}
