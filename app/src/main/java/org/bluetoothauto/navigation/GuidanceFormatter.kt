// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance
import java.util.Locale
import kotlin.math.roundToInt

object GuidanceFormatter {
    fun title(guidance: Guidance?, songTitle: String, units: String): String {
        val song = NotificationTextParser.clean(songTitle, 120).ifBlank { "BluetoothAuto" }
        if (guidance == null) return song
        val prefix = when (NotificationTextParser.arrow(guidance.instruction)) {
            "→" -> "->"
            "←" -> "<-"
            "↑" -> "^"
            "↗" -> "/->"
            "↖" -> "<-\\"
            "↶" -> "UTURN"
            "↻" -> "ROUND"
            else -> "NAV"
        }
        val distance = formatDistance(guidance.distanceMeters, guidance.distanceLabel, units)
        return (prefix + (if (distance.isBlank()) "" else " $distance") + " · " + song).take(180)
    }

    fun formatDistance(meters: Double?, originalLabel: String, units: String): String {
        if (units.equals("AUTO", true)) return NotificationTextParser.clean(originalLabel, 48)
            .ifBlank { converted(meters, false) }
        return converted(meters, units.equals("IMPERIAL", true))
    }

    private fun converted(meters: Double?, imperial: Boolean): String {
        if (meters == null || !meters.isFinite() || meters < 0 || meters > 10_000_000) return ""
        return if (imperial) {
            val feet = meters / 0.3048
            if (feet < 100) "${feet.roundToInt()} ft" else if (feet < 1000) "${(feet / 10).roundToInt() * 10} ft" else decimal(meters / 1609.344) + " mi"
        } else if (meters < 1000) "${meters.roundToInt()} m" else decimal(meters / 1000) + " km"
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, if (value >= 10) "%.0f" else "%.1f", value)
}
