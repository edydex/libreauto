// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

/** Current-cue wording only; toward a named street is distinct from turning onto it. */
object NotificationTurnHint {
    enum class Kind { LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, KEEP_LEFT, KEEP_RIGHT,
        EXIT, EXIT_LEFT, EXIT_RIGHT, RAMP, RAMP_LEFT, RAMP_RIGHT, MERGE, MERGE_LEFT, MERGE_RIGHT, STRAIGHT }
    data class Hint(val kind: Kind, val road: String, val toward: Boolean = false,
                    val destinationHint: String? = null)

    fun parse(instruction: String): Hint? {
        val maneuver = ManeuverText.parse(instruction) ?: return null
        val kind = Kind.entries.firstOrNull { it.name == maneuver.kind.name } ?: return null
        val towardParts = maneuver.action.split(Regex("\\s+towards?\\s+", RegexOption.IGNORE_CASE), limit = 2)
        val action = towardParts[0].trim()
        val onRoad = Regex("\\b(?:onto|on)\\s+(?!the\\s+(?:left|right)\\b)(.+)$", RegexOption.IGNORE_CASE)
            .find(action)?.groupValues?.get(1)
        val toRoad = Regex("\\bto\\s+(?!turn\\b|take\\b|merge\\b|keep\\b|continue\\b)(.+)$", RegexOption.IGNORE_CASE)
            .find(action)?.groupValues?.get(1)
        val exitRoad = Regex("\\bfor\\s+(.+)$", RegexOption.IGNORE_CASE).find(action)?.groupValues?.get(1)
            ?.takeIf { roadLike(it) }
        val referencePattern = Regex("\\b(?:Interstate|I|U\\.?S\\.?|WA|SR|State (?:Route|Hwy|Highway|Rte|Rt)|(?:Washington|Wash) State (?:Hwy|Highway|Rte|Rt))[ -]?\\d+[A-Za-z]?(?:\\s+(?:North|South|East|West|N|S|E|W))?\\b", RegexOption.IGNORE_CASE)
        val reference = referencePattern.find(action)?.value
        // "Take the I-5 N ramp to Seattle" names the ramp before "to".
        // The city is a sign destination, unlike "take the ramp to Pine St".
        val namedRampTo = Regex("^(?:take|follow)\\s+(?:the\\s+)?(.+?)\\s+(?:exit|ramp)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(action)
        val rampReference = namedRampTo?.groupValues?.get(1)?.takeIf(::highwayReference)
        if (namedRampTo != null && rampReference == null &&
            referencePattern.containsMatchIn(namedRampTo.groupValues[1])) return null
        val rampDestination = namedRampTo?.groupValues?.get(2)?.takeIf { rampReference != null && onRoad == null }
        val explicit = onRoad ?: toRoad?.takeIf { rampDestination == null }
        val towardTarget = towardParts.getOrNull(1)?.trim()?.trimEnd('.', ',')?.takeIf {
            maneuver.explicitTurn && namedRoad(it) && !Regex("\\btowards?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        val selected = explicit ?: exitRoad ?: rampReference ?: reference
        val road = (selected ?: towardTarget)?.trim()?.trimEnd('.', ',')
            ?.replace(Regex("^the\\s+|\\s+ramp$", RegexOption.IGNORE_CASE), "") ?: return null
        if (road.length !in 2..200 || TurnMatcher.roadAlternatives(road).isEmpty() || road.contains(';') ||
            Regex("\\b(?:to turn|to take|then)\\b", RegexOption.IGNORE_CASE).containsMatchIn(road) ||
            road.equals("the left", true) || road.equals("the right", true)) return null
        // An exit sign's destination is a routing proposal, never evidence of
        // the road entered. Keep it separate from the authoritative reference.
        val signDestination = if (towardParts.size > 1 && rampDestination != null) null
            else towardParts.getOrNull(1) ?: rampDestination
        val destination = signDestination?.trim()?.trimEnd('.', ',')?.takeIf {
            selected != null && kind in setOf(Kind.EXIT, Kind.EXIT_LEFT, Kind.EXIT_RIGHT,
                Kind.RAMP, Kind.RAMP_LEFT, Kind.RAMP_RIGHT) &&
                highwayReference(road) &&
                it.length in 2..100 && it.split(' ').size <= 8 &&
                it.any(Char::isLetter) && it.all { char -> char.isLetterOrDigit() || char in " '-.," }
        }
        return Hint(kind, road, selected == null && towardTarget != null, destination)
    }

    private fun highwayReference(value: String) =
        Regex("^(?:i|us|u s|wa|sr|(?:washington |wash )?state (?:route|hwy|rte|rt)) \\d+[a-z]?(?: [nsew])?$")
            .matches(TurnMatcher.normalize(value))

    private fun namedRoad(value: String): Boolean {
        if (value.length !in 2..200) return false
        val aliases = TurnMatcher.roadAlternatives(value)
        if (aliases.isEmpty()) return false
        return aliases.all { alias ->
            val normal = TurnMatcher.normalize(alias)
            Regex("\\b(?:st|ave|rd|blvd|dr|ln|ct|pl|pkwy|way)(?: (?:n|s|e|w|ne|nw|se|sw))?$").containsMatchIn(normal) ||
                Regex("^(?:i|us|u s|wa|sr|(?:washington |wash )?state (?:route|hwy|rte|rt)) \\d+[a-z]?(?: [nsew])?$").matches(normal)
        }
    }

    private fun roadLike(value: String) = Regex("\\b(?:street|st|avenue|ave|road|rd|drive|dr|highway|hwy|route|boulevard|blvd|way|lane|ln|court|ct|place|pl|parkway|pkwy)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(value) || Regex("\\b(?:I|US|WA|SR)[ -]?\\d+", RegexOption.IGNORE_CASE).containsMatchIn(value)
}
