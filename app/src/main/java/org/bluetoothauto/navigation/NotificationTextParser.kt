// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import java.text.DecimalFormatSymbols
import java.util.Locale

/** Original, pure text parsing. Notification layouts/private resources are deliberately unused. */
object NotificationTextParser {
    data class Parsed(val instruction: String, val distanceMeters: Double?, val distanceLabel: String, val status: Boolean)
    data class Distance(val meters: Double, val label: String, val start: Int, val end: Int)
    private val distance = Regex("([\\p{Nd}][\\p{Nd}\\s\\u00a0\\u202f.,'\\u066b\\u066c]{0,24})\\s*(kilometers?|kilometres?|km|км|公里|千米|miles?|mi|миль|мили|миля|feet|foot|ft|футов|фута|фут|yards?|yd|meters?|metres?|m|м|米)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val summary = Regex("(?:\\b(?:min|mins|minutes?|hours?|hrs?|hr|eta|arriv(?:e|al))\\b|\\b\\d{1,2}:\\d{2}\\b|\\bмин\\b|\\bч\\b)", RegexOption.IGNORE_CASE)
    private val localizedCommand = Regex("^(?:поверните (?:налево|направо)|держитесь (?:левее|правее)|tournez [àa] (?:droite|gauche)|(?:gira|gire) a la (?:derecha|izquierda)|rechts abbiegen|links abbiegen)\\b", RegexOption.IGNORE_CASE)
    private val state = Regex("^(?:finding gps(?: signal)?|searching for gps(?: signal)?|waiting for gps(?: signal)?|gps signal (?:lost|unavailable)|rerouting|recalculating(?: route)?|calculating(?: your)? route|finding (?:a )?route|поиск gps|прокладывается маршрут|перестроение маршрута)[. …]*$", RegexOption.IGNORE_CASE)

    fun clean(value: String, limit: Int = 240): String = value.take(2048).replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ").trim().take(limit)

    fun parse(fields: List<String>, navigationCategory: Boolean, ongoing: Boolean, locale: Locale = Locale.getDefault()): Parsed? {
        val lines = fields.take(8).flatMap { it.take(2048).lineSequence().take(8).toList() }
            .map { clean(it, 512) }.filter { it.isNotBlank() && !it.equals("Google Maps", true) }.distinct().take(32)
        if (!navigationCategory && !ongoing) return null
        lines.firstOrNull { state.matches(it) }?.let { return Parsed(clean(it), null, "", true) }
        val useful = lines.filterNot { summary.containsMatchIn(it) }
        val distances = useful.mapNotNull { text -> parseDistance(text, locale)?.let { text to it } }
        for ((text, range) in distances) {
            var instruction = (text.substring(0, range.start) + " " + text.substring(range.end)).trim(' ', '-', '–', '—', '·', ':')
            instruction = instruction.replace(Regex("^(?:in|через)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(?:in|через)$", RegexOption.IGNORE_CASE), "").trim()
            if (instruction.isNotBlank() && (navigationCategory || knownCommand(instruction)))
                return Parsed(clean(instruction), range.meters, clean(range.label, 48), false)
        }
        val instruction = useful.firstOrNull { text -> knownCommand(text) }
            ?: if (navigationCategory && distances.isNotEmpty()) useful.firstOrNull { parseDistance(it, locale) == null } else null
        if (instruction != null && (navigationCategory || knownCommand(instruction))) {
            val range = distances.firstOrNull()?.second
            return Parsed(clean(instruction), range?.meters, range?.label?.let { clean(it, 48) } ?: "", false)
        }
        if (navigationCategory && distances.isNotEmpty()) {
            val range = distances.first().second
            return Parsed("Navigation", range.meters, clean(range.label, 48), false)
        }
        // A navigation-category update can be a new/unknown route status. Replace stale
        // turn data with neutral text, with no direction icon or old distance attached.
        if (navigationCategory && lines.isNotEmpty()) return Parsed(clean(lines.first()), null, "", true)
        return null
    }

    /** Promoted live notifications can publish the newest distance separately from their title. */
    fun withCriticalDistance(parsed: Parsed, value: String?, locale: Locale = Locale.getDefault()): Parsed {
        if (parsed.status || value == null || value.length > 48) return parsed
        val text = value.trim()
        val distance = parseDistance(text, locale) ?: return parsed
        if (distance.start != 0 || distance.end != text.length) return parsed
        return parsed.copy(distanceMeters = distance.meters, distanceLabel = distance.label)
    }

    fun parseDistance(text: String, locale: Locale = Locale.getDefault()): Distance? {
        if (text.length > 2048) return null
        val match = distance.find(text) ?: return null
        if (match.range.first > 0 && (text[match.range.first - 1].isLetterOrDigit()
                    || text[match.range.first - 1] in charArrayOf('-', '−', '.', ','))) return null
        val number = numeric(match.groupValues[1], locale) ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val multiplier = when {
            unit in setOf("km", "км", "公里", "千米") || unit.startsWith("kilomet") -> 1000.0
            unit in setOf("mi", "миль", "мили", "миля") || unit.startsWith("mile") -> 1609.344
            unit in setOf("ft", "feet", "foot") || unit.startsWith("фут") -> 0.3048
            unit == "yd" || unit.startsWith("yard") -> 0.9144
            else -> 1.0
        }
        val meters = number * multiplier
        if (!meters.isFinite() || meters < 0 || meters > 10_000_000) return null
        return Distance(meters, match.value.trim(), match.range.first, match.range.last + 1)
    }

    private fun numeric(input: String, locale: Locale): Double? {
        val normalized = buildString {
            input.forEach { c ->
                val digit = Character.digit(c, 10)
                when {
                    digit >= 0 -> append(digit)
                    c == '.' || c == ',' -> append(c)
                    c == '\u066b' -> append('.')
                    c == '\u066c' || c.isWhitespace() || c == '\u00a0' || c == '\u202f' || c == '\'' -> Unit
                    else -> return null
                }
            }
        }
        if (normalized.isEmpty() || normalized.length > 24 || !Regex("\\d+(?:[.,]\\d+)*").matches(normalized)) return null
        val dots = normalized.count { it == '.' }; val commas = normalized.count { it == ',' }
        val decimal = when {
            dots > 0 && commas > 0 -> if (normalized.lastIndexOf('.') > normalized.lastIndexOf(',')) '.' else ','
            dots == 1 || commas == 1 -> {
                val separator = if (dots == 1) '.' else ','
                val trailing = normalized.length - normalized.lastIndexOf(separator) - 1
                if (trailing != 3 || DecimalFormatSymbols.getInstance(locale).decimalSeparator == separator || input.contains('\u066b')) separator else null
            }
            else -> null
        }
        val decimalIndex = decimal?.let { normalized.lastIndexOf(it) } ?: -1
        val integer = if (decimalIndex >= 0) normalized.substring(0, decimalIndex) else normalized
        if (integer.contains('.') || integer.contains(',')) {
            val groups = integer.split('.', ',')
            if (groups.first().length !in 1..3 || groups.drop(1).any { it.length != 3 }) return null
        }
        val ascii = buildString { normalized.forEachIndexed { index, c -> if (c.isDigit()) append(c) else if (index == decimalIndex) append('.') } }
        return ascii.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Text classification only: an unrecognized instruction never invents a straight arrow. */
    fun arrow(instruction: String): String = when (ManeuverText.parse(instruction)?.display) {
        ManeuverText.Display.LEFT -> "←"
        ManeuverText.Display.RIGHT -> "→"
        ManeuverText.Display.SLIGHT_LEFT -> "↖"
        ManeuverText.Display.SLIGHT_RIGHT -> "↗"
        ManeuverText.Display.STRAIGHT -> "↑"
        ManeuverText.Display.UTURN -> "↶"
        ManeuverText.Display.ROUNDABOUT -> "↻"
        else -> "NAV"
    }

    private fun knownCommand(instruction: String) = ManeuverText.parse(instruction) != null ||
        localizedCommand.containsMatchIn(instruction)
}
