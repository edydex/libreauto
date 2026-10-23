// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/** Filled outlines retain a clean silhouette at both phone and 200px Bluetooth thumbnail sizes. */
internal class ManeuverPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badge = RectF(0f, 0f, 100f, 100f)
    private val paths = ManeuverGlyph.entries.associateWith { build(ManeuverVectorGeometry.forGlyph(it)) }
    private val idle = build(ManeuverVectorGeometry.idle)

    fun draw(canvas: Canvas, glyph: ManeuverGlyph?, x: Float, y: Float, size: Float) {
        if (!size.isFinite() || size <= 0) return
        val saved = canvas.save()
        canvas.translate(x, y); canvas.scale(size / 100f, size / 100f)
        paint.color = 0xff007f82.toInt()
        canvas.drawRoundRect(badge, 22f, 22f, paint)
        paint.color = Color.WHITE
        for (path in if (glyph == null) idle else paths.getValue(glyph)) canvas.drawPath(path, paint)
        canvas.restoreToCount(saved)
    }

    private fun build(vector: ManeuverVectorGeometry.Vector): List<Path> = vector.paths.map { source ->
        requireNotNull(PathParser.createPathFromPathData(source)).apply {
            fillType = Path.FillType.EVEN_ODD
            if (vector.mirrored) transform(Matrix().apply { setScale(-1f, 1f, 50f, 50f) })
        }
    }
}
