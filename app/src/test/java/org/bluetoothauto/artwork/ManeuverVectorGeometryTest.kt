// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.junit.Assert.*
import org.junit.Test

class ManeuverVectorGeometryTest {
    @Test fun everyManeuverHasClosedFilledGeometry() {
        for (glyph in ManeuverGlyph.entries) {
            val vector = ManeuverVectorGeometry.forGlyph(glyph)
            assertTrue(glyph.name, vector.paths.isNotEmpty())
            vector.paths.forEach { assertTrue(glyph.name, it.startsWith("M") && it.endsWith("Z")) }
        }
    }
    @Test fun allCoordinatesRemainInsideTheBadge() {
        val vectors = ManeuverGlyph.entries.map(ManeuverVectorGeometry::forGlyph) + ManeuverVectorGeometry.idle
        for (vector in vectors) for (path in vector.paths) for (number in Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(path))
            assertTrue("Out-of-bounds coordinate", number.value.toDouble() in 4.0..96.0)
    }
    @Test fun leftAndRightUseExactMirroredOutlines() {
        val left = ManeuverVectorGeometry.forGlyph(ManeuverGlyph.LEFT)
        val right = ManeuverVectorGeometry.forGlyph(ManeuverGlyph.RIGHT)
        assertEquals(left.paths, right.paths); assertTrue(left.mirrored); assertFalse(right.mirrored)
    }
    @Test fun slightTurnsUseExactMirroredOutlines() {
        val left = ManeuverVectorGeometry.forGlyph(ManeuverGlyph.SLIGHT_LEFT)
        val right = ManeuverVectorGeometry.forGlyph(ManeuverGlyph.SLIGHT_RIGHT)
        assertEquals(left.paths, right.paths); assertTrue(left.mirrored); assertFalse(right.mirrored)
        assertNotEquals(right.paths, ManeuverVectorGeometry.forGlyph(ManeuverGlyph.RIGHT).paths)
    }
    @Test fun pathsUseOnlySupportedCompleteCommands() {
        val arity = mapOf("M" to 2, "L" to 2, "Q" to 4, "C" to 6, "Z" to 0)
        for (vector in ManeuverGlyph.entries.map(ManeuverVectorGeometry::forGlyph) + ManeuverVectorGeometry.idle) {
            for (path in vector.paths) {
                val tokens = Regex("[A-Za-z]|[-+]?\\d+(?:\\.\\d+)?").findAll(path).map { it.value }.toList()
                var index = 0
                while (index < tokens.size) {
                    val count = requireNotNull(arity[tokens[index++]])
                    repeat(count) { assertTrue(tokens[index++].toDouble().isFinite()) }
                }
            }
        }
    }
}
