// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import java.io.File

/** Host-only export of the exact production paths; no Android graphics, network or device required. */
object ManeuverVisualFixture {
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args.single()).also { it.mkdirs() }
        val labels = mapOf(ManeuverGlyph.LEFT to "Left", ManeuverGlyph.RIGHT to "Right", ManeuverGlyph.STRAIGHT to "Straight",
            ManeuverGlyph.SLIGHT_LEFT to "Slight left", ManeuverGlyph.SLIGHT_RIGHT to "Slight right", ManeuverGlyph.UTURN to "U-turn",
            ManeuverGlyph.ROUNDABOUT to "Roundabout", ManeuverGlyph.ARRIVE to "Arrival", ManeuverGlyph.UNKNOWN to "Unknown")
        fun artwork(glyph: ManeuverGlyph): String {
            val vector = ManeuverVectorGeometry.forGlyph(glyph)
            val paths = vector.paths.joinToString("") { "<path d=\"$it\" fill=\"white\" fill-rule=\"evenodd\"/>" }
            return "<rect width=\"100\" height=\"100\" rx=\"22\" fill=\"#007f82\"/><g" +
                (if (vector.mirrored) " transform=\"translate(100 0) scale(-1 1)\"" else "") + ">$paths</g>"
        }
        val cells = ManeuverGlyph.entries.mapIndexed { index, glyph ->
            val x = (index % 3) * 240; val y = (index / 3) * 260 + 48
            File(output, glyph.name.lowercase() + "-200.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"200\" viewBox=\"0 0 100 100\">${artwork(glyph)}</svg>")
            File(output, glyph.name.lowercase() + "-36.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"36\" height=\"36\" viewBox=\"0 0 100 100\">${artwork(glyph)}</svg>")
            "<g transform=\"translate(${x + 20} $y) scale(2)\">${artwork(glyph)}</g>" +
                "<text x=\"${x + 100}\" y=\"${y + 230}\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"20\" fill=\"#163f45\">${labels.getValue(glyph)}</text>" +
                "<g transform=\"translate(${x + 184} ${y + 208}) scale(.36)\">${artwork(glyph)}</g>"
        }.joinToString("")
        File(output, "maneuver-contact-sheet.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"720\" height=\"840\" viewBox=\"0 0 720 840\"><rect width=\"720\" height=\"840\" fill=\"#edf5f2\"/><text x=\"360\" y=\"30\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"20\" fill=\"#163f45\">Original maneuver vectors · 200px samples</text>$cells</svg>")
        println("Exported ${ManeuverGlyph.entries.size} exact production vector fixtures at200px and36px")
    }
}
