// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

/** Original filled vector outlines, shared verbatim by Android and the host visual fixtures. */
internal object ManeuverVectorGeometry {
    data class Vector(val paths: List<String>, val mirrored: Boolean = false)

    private val right = listOf("M22 88 L22 55 C22 42 30 34 43 34 L61 34 L61 17 L88 41 L61 65 L61 49 L44 49 C39 49 37 53 37 58 L37 88 Z")
    private val slight = listOf("M26 88 L26 65 Q26 56 33 49 L59 23 L45 9 L88 9 L88 52 L74 38 L48 64 Q42 70 42 76 L42 88 Z")
    private val straight = listOf("M43 88 L43 40 L24 40 L50 12 L76 40 L57 40 L57 88 Z")
    private val uturn = listOf("M20 88 L20 39 C20 18 32 9 48 9 C65 9 77 21 77 40 L77 57 L91 57 L69 83 L47 57 L61 57 L61 40 C61 30 57 25 48 25 C40 25 36 30 36 39 L36 88 Z")
    private val roundabout = listOf(
        "M50 14 C30 14 14 30 14 50 C14 70 30 86 50 86 C70 86 86 70 86 50 C86 30 70 14 50 14 Z M50 29 C62 29 71 38 71 50 C71 62 62 71 50 71 C38 71 29 62 29 50 C29 38 38 29 50 29 Z",
        "M43 81 L57 81 L57 95 L43 95 Z"
    )
    private val arrive = listOf("M20 12 L32 12 L32 90 L20 90 Z", "M32 15 L83 15 L73 33 L83 51 L32 51 Z")
    private val unknown = listOf("M50 10 C30 10 17 25 17 44 C17 65 50 91 50 91 C50 91 83 65 83 44 C83 25 70 10 50 10 Z M50 30 C58 30 64 36 64 44 C64 52 58 58 50 58 C42 58 36 52 36 44 C36 36 42 30 50 30 Z")
    val idle = Vector(listOf(
        "M20 70 L20 44 C20 25 32 13 50 13 C68 13 80 25 80 44 L80 70 L68 70 L68 44 C68 32 62 25 50 25 C38 25 32 32 32 44 L32 70 Z",
        "M17 48 L32 48 L32 79 L24 79 Q17 79 17 72 Z", "M68 48 L83 48 L83 72 Q83 79 76 79 L68 79 Z"
    ))

    fun forGlyph(glyph: ManeuverGlyph): Vector = when (glyph) {
        ManeuverGlyph.LEFT -> Vector(right, true)
        ManeuverGlyph.RIGHT -> Vector(right)
        ManeuverGlyph.STRAIGHT -> Vector(straight)
        ManeuverGlyph.SLIGHT_LEFT -> Vector(slight, true)
        ManeuverGlyph.SLIGHT_RIGHT -> Vector(slight)
        ManeuverGlyph.UTURN -> Vector(uturn)
        ManeuverGlyph.ROUNDABOUT -> Vector(roundabout)
        ManeuverGlyph.ARRIVE -> Vector(arrive)
        ManeuverGlyph.UNKNOWN -> Vector(unknown)
    }
}
