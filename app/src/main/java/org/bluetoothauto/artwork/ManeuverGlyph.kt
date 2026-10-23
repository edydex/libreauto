// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.navigation.ManeuverText

/** Maneuver meaning from explicit instruction text; an unrecognized instruction stays neutral. */
internal enum class ManeuverGlyph {
    LEFT, RIGHT, STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN, ROUNDABOUT, ARRIVE, UNKNOWN;

    companion object {
        fun fromInstruction(instruction: String): ManeuverGlyph = when (ManeuverText.parse(instruction)?.display) {
            ManeuverText.Display.LEFT -> LEFT
            ManeuverText.Display.RIGHT -> RIGHT
            ManeuverText.Display.SLIGHT_LEFT -> SLIGHT_LEFT
            ManeuverText.Display.SLIGHT_RIGHT -> SLIGHT_RIGHT
            ManeuverText.Display.STRAIGHT -> STRAIGHT
            ManeuverText.Display.UTURN -> UTURN
            ManeuverText.Display.ROUNDABOUT -> ROUNDABOUT
            ManeuverText.Display.ARRIVE -> ARRIVE
            else -> UNKNOWN
        }
    }
}
