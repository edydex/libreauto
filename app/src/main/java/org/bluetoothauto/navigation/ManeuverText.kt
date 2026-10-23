// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import java.util.Locale

/** One bounded English grammar for title arrows, phone/artwork icons and map admission. */
internal object ManeuverText {
    enum class Kind { LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, KEEP_LEFT, KEEP_RIGHT,
        EXIT, EXIT_LEFT, EXIT_RIGHT, RAMP, RAMP_LEFT, RAMP_RIGHT, MERGE, MERGE_LEFT,
        MERGE_RIGHT, STRAIGHT, DEPARTURE, UTURN, ROUNDABOUT, ARRIVE }
    enum class Display { LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, STRAIGHT, UTURN, ROUNDABOUT, ARRIVE, UNKNOWN }
    data class Parsed(val action: String, val kind: Kind, val display: Display, val explicitTurn: Boolean = false)

    private const val COUNT = "(?:[1-6]|one|two|three|four|five|six)"
    private val controls = Regex("[\\p{Cc}\\p{Cf}]+")
    private val whitespace = Regex("\\s+")
    private val lanePrefix = Regex("^use (?:the )?(?:any lane|either lane|both lanes|(?:(?:middle|center|centre)(?: $COUNT)?|$COUNT (?:middle|center|centre)) lanes?|(?:(left|right)(?: $COUNT)?|$COUNT (left|right)) lanes?) to (.+)$", RegexOption.IGNORE_CASE)
    private val atLight = Regex("^at (?:the )?(?:traffic )?lights?,? (.+)$", RegexOption.IGNORE_CASE)
    private val roundaboutComma = Regex("^((?:(?:at|enter) (?:the )?)?roundabout), ", RegexOption.IGNORE_CASE)
    private val turn = Regex("^(turn|bear) (?:(slight(?:ly)?|sharp(?:ly)?) )?(left|right)\\b", RegexOption.IGNORE_CASE)
    private val bareSlight = Regex("^slight(?:ly)? (left|right)\\b", RegexOption.IGNORE_CASE)
    private val takeTurn = Regex("^take (?:the )?(?:(?:first|second|third|[1-9][0-9]?(?:st|nd|rd|th)) )?(left|right)\\b", RegexOption.IGNORE_CASE)
    private val keep = Regex("^keep (left|right)\\b", RegexOption.IGNORE_CASE)
    private val merge = Regex("^merge(?: (left|right))?\\b", RegexOption.IGNORE_CASE)
    private val compound = Regex("\\bthen\\b|[;]|[,;:] *(?:turn|bear|keep|merge|take|exit|ramp|continue|go straight)\\b|[,;:] *(?:left|right) (?:onto|on|towards?|to)\\b|\\b(?:and|or|but) (?:turn|bear|keep|merge|take|exit|ramp|continue|go straight)\\b|\\b(?:and|or) (?:left|right) (?:onto|on|towards?|to)\\b", RegexOption.IGNORE_CASE)
    private val repeatedDirectionalAction = Regex("\\b(?:(?:turn|bear|keep|merge) (?:(?:slight(?:ly)?|sharp(?:ly)?) )?|take (?:the )?)(?:left|right)\\b", RegexOption.IGNORE_CASE)
    private val contraryRemainder = Regex("^(?:and|or|/) ?(?:left|right)\\b", RegexOption.IGNORE_CASE)
    private val side = Regex("\\b(?:on the|to the) (left|right)\\b|^(?:exit|ramp) (left|right)\\b", RegexOption.IGNORE_CASE)
    private const val CONTROL_SIDE = "the (?:left|right)(?=[.,]?(?:$| (?:onto|on|to|for|towards?) ))"
    private val roadStart = Regex(" (?:onto|for|towards?|on(?! $CONTROL_SIDE)|to(?! $CONTROL_SIDE)) ", RegexOption.IGNORE_CASE)
    private val nextActionRoad = Regex("^(?:onto|on|to|towards?|at)\\b", RegexOption.IGNORE_CASE)
    private val turnRoadStart = Regex("^(?:onto|on|to take) ", RegexOption.IGNORE_CASE)
    private val towardStart = Regex(" towards? ", RegexOption.IGNORE_CASE)
    private val uTurn = Regex("^(?:(?:make|take) (?:a )?)?u[ -]?turn\\b", RegexOption.IGNORE_CASE)
    private val roundabout = Regex("^(?:(?:at|enter) (?:the )?)?roundabout\\b", RegexOption.IGNORE_CASE)
    private val arrival = Regex("^(?:arriv(?:e|ed)\\b|you(?: have)? arrived\\b|destination reached\\b|your destination\\b)", RegexOption.IGNORE_CASE)
    private val laneWord = Regex("^lanes?\\b", RegexOption.IGNORE_CASE)
    private val rampAction = Regex("^(?:(?:take|follow) (?:the )?(?:.{1,180} )?)?(exit|ramp)\\b", RegexOption.IGNORE_CASE)
    private val continuation = Regex("^(?:continue (?:straight|on|onto|towards?|for|to stay on)|go straight|keep straight|proceed (?:on|onto|to|towards?))\\b", RegexOption.IGNORE_CASE)
    private val departure = Regex("^head (?:north|south|east|west|northeast|northwest|southeast|southwest|toward|onto)\\b", RegexOption.IGNORE_CASE)
    private val namedRamp = Regex("^(?:the )?(?:Interstate|I|U\\.?S\\.?|WA|SR|State (?:Route|Hwy|Highway|Rte|Rt)|(?:Washington|Wash) State (?:Hwy|Highway|Rte|Rt))[ -]?[0-9]+[A-Za-z]?(?: (?:North|South|East|West|N|S|E|W))? (ramp|exit)$", RegexOption.IGNORE_CASE)

    fun parse(instruction: String): Parsed? {
        if (instruction.isBlank() || instruction.length > 2048) return null
        var action = instruction.replace(controls, " ").replace(whitespace, " ").trim()
        if (action.length > 500) return null
        atLight.matchEntire(action)?.let { action = it.groupValues[1] }
        action = action.replace(roundaboutComma, "$1 ")
        if (compound.containsMatchIn(action)) return null
        val firstRoad = roadStart.find(action)?.range?.first ?: action.length
        if (repeatedDirectionalAction.findAll(action).drop(1).any { command ->
            val rest = action.substring(command.range.last + 1).trim().trimEnd('.', ',')
            command.range.first < firstRoad || rest.isEmpty() || nextActionRoad.containsMatchIn(rest)
        }) return null
        var laneSide: String? = null
        lanePrefix.matchEntire(action)?.let {
            laneSide = it.groupValues.drop(1).take(2).firstOrNull(String::isNotEmpty)?.lowercase(Locale.ROOT)
            action = it.groupValues[3]
        }
        if (action.startsWith("use ", true)) return null
        fun result(kind: Kind, explicit: Boolean = false, display: Display? = null) = Parsed(action, kind, display ?: when (kind) {
            Kind.LEFT -> Display.LEFT; Kind.RIGHT -> Display.RIGHT
            Kind.SLIGHT_LEFT, Kind.KEEP_LEFT, Kind.EXIT_LEFT, Kind.RAMP_LEFT, Kind.MERGE_LEFT -> Display.SLIGHT_LEFT
            Kind.SLIGHT_RIGHT, Kind.KEEP_RIGHT, Kind.EXIT_RIGHT, Kind.RAMP_RIGHT, Kind.MERGE_RIGHT -> Display.SLIGHT_RIGHT
            Kind.STRAIGHT, Kind.DEPARTURE -> Display.STRAIGHT
            Kind.UTURN -> Display.UTURN; Kind.ROUNDABOUT -> Display.ROUNDABOUT; Kind.ARRIVE -> Display.ARRIVE
            Kind.EXIT, Kind.RAMP, Kind.MERGE -> Display.UNKNOWN
        }, explicit)
        fun turnResult(left: Boolean, slight: Boolean, remainder: String): Parsed? {
            if (contraryRemainder.containsMatchIn(remainder)) return null
            val road = remainder.replace(turnRoadStart, "").split(towardStart, limit = 2)[0].trimEnd('.', ',')
            val qualifier = namedRamp.matchEntire(road)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            val kind = when (qualifier) {
                "ramp" -> if (left) Kind.RAMP_LEFT else Kind.RAMP_RIGHT
                "exit" -> if (left) Kind.EXIT_LEFT else Kind.EXIT_RIGHT
                else -> if (left) if (slight) Kind.SLIGHT_LEFT else Kind.LEFT else if (slight) Kind.SLIGHT_RIGHT else Kind.RIGHT
            }
            val display = if (left) if (slight) Display.SLIGHT_LEFT else Display.LEFT else if (slight) Display.SLIGHT_RIGHT else Display.RIGHT
            return result(kind, true, display)
        }
        if (uTurn.containsMatchIn(action)) return result(Kind.UTURN)
        if (roundabout.containsMatchIn(action)) return result(Kind.ROUNDABOUT)
        if (arrival.containsMatchIn(action)) return result(Kind.ARRIVE)
        turn.find(action)?.let { return turnResult(it.groupValues[3].equals("left", true),
            it.groupValues[1].equals("bear", true) || it.groupValues[2].startsWith("slight", true), action.substring(it.range.last + 1).trim()) }
        bareSlight.find(action)?.let { return turnResult(it.groupValues[1].equals("left", true), true, action.substring(it.range.last + 1).trim()) }
        takeTurn.find(action)?.let {
            val rest = action.substring(it.range.last + 1).trim()
            if (laneWord.containsMatchIn(rest)) return null
            return turnResult(it.groupValues[1].equals("left", true), false, rest)
        }
        keep.find(action)?.let {
            if (contraryRemainder.containsMatchIn(action.substring(it.range.last + 1).trim())) return null
            return result(if (it.groupValues[1].equals("left", true)) Kind.KEEP_LEFT else Kind.KEEP_RIGHT)
        }
        merge.find(action)?.let {
            val rest = action.substring(it.range.last + 1).trim()
            if (contraryRemainder.containsMatchIn(rest)) return null
            val direction = it.groupValues[1].lowercase(Locale.ROOT).ifEmpty { laneSide.orEmpty() }
            return result(when (direction) { "left" -> Kind.MERGE_LEFT; "right" -> Kind.MERGE_RIGHT; else -> Kind.MERGE })
        }
        val head = roadStart.split(action, limit = 2)[0]
        val rampExit = rampAction.find(head)
        if (rampExit != null) {
            val directions = side.findAll(head).map { match -> match.groupValues.drop(1).first(String::isNotEmpty).lowercase(Locale.ROOT) }.toSet()
            if (directions.size > 1) return null
            val direction = directions.singleOrNull() ?: laneSide
            return result(if (rampExit.groupValues[1].equals("exit", true)) when (direction) {
                "left" -> Kind.EXIT_LEFT; "right" -> Kind.EXIT_RIGHT; else -> Kind.EXIT
            } else when (direction) { "left" -> Kind.RAMP_LEFT; "right" -> Kind.RAMP_RIGHT; else -> Kind.RAMP })
        }
        if (continuation.containsMatchIn(action)) return result(Kind.STRAIGHT)
        if (departure.containsMatchIn(action)) return result(Kind.DEPARTURE)
        return null
    }
}
