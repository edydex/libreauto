// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class GoogleDirectionsStepKind { DEPARTURE, MANEUVER, ARRIVAL }

/** A leg distance belongs AFTER this instruction; it is not a live distance to this turn. */
data class GoogleDirectionsStep(val index: Int, val rowIndex: Int, val instruction: String,
    val followingDistanceMeters: Double?, val followingDistanceLabel: String,
    val kind: GoogleDirectionsStepKind, val key: String)

data class GoogleDirectionsSnapshot(val fingerprint: String, val steps: List<GoogleDirectionsStep>,
    val capturedAtElapsed: Long, val complete: Boolean, val destinationQuery: String? = null)

/** Plain copies from a Directions-list row. No Android nodes, coordinates or images are retained. */
data class GoogleDirectionsRow(val rowIndex: Int, val texts: List<String>,
    val descriptions: List<String> = emptyList())

/** A bounded parser for the actually observed English Google Maps Directions layout. */
object GoogleDirectionsParser {
    const val MAX_STEPS = 512
    const val MAX_ROWS = MAX_STEPS * 2 - 1
    const val MAX_TOTAL_TEXT_CHARS = 1_000_000
    private const val MAX_TEXTS = 32
    private const val MAX_TEXT = 600
    private val arrival = Regex("^(?:destination (?:will be|is) on(?: the)? (?:left|right)|your destination (?:will be|is)|you(?:'ve| have)? arrived|arriv(?:e|al) at|destination reached)\\b", RegexOption.IGNORE_CASE)
    private val departure = Regex("^head (?:north|south|east|west|northeast|northwest|southeast|southwest|toward|onto)\\b", RegexOption.IGNORE_CASE)
    private val narrative = Regex("^(?:head|turn|bear|keep|use|take|exit|merge|continue|go|proceed|make|at the roundabout|enter the roundabout)\\b", RegexOption.IGNORE_CASE)

    fun parse(rows: List<GoogleDirectionsRow>, capturedAtElapsed: Long): GoogleDirectionsSnapshot? {
        if (capturedAtElapsed <= 0 || rows.size !in 1..MAX_ROWS || rows.size % 2 != 1) return null
        if (rows.withIndex().any { it.value.rowIndex != it.index ||
            it.value.texts.size > MAX_TEXTS || it.value.descriptions.size > MAX_TEXTS ||
            (it.value.texts + it.value.descriptions).any { text -> text.length > MAX_TEXT } }) return null
        if (rows.sumOf { row -> (row.texts + row.descriptions).sumOf { it.length.toLong() } } > MAX_TOTAL_TEXT_CHARS) return null
        val pending = ArrayList<GoogleDirectionsStep>()
        for (rowIndex in rows.indices step 2) {
            val row = rows[rowIndex]
            val texts = row.texts.map(::clean).filter { it.isNotBlank() }.distinct()
            val descriptions = row.descriptions.map(::clean).filter { it.isNotBlank() }.distinct()
            val isArrival = (texts + descriptions).any(arrival::containsMatchIn)
            val instruction = if (isArrival) texts.firstOrNull() ?: descriptions.firstOrNull()
                else (texts + descriptions).firstOrNull(narrative::containsMatchIn)
            if (instruction == null || instruction.length !in 2..MAX_TEXT || isDistanceOnly(instruction)) return null
            if (isArrival && rowIndex != rows.lastIndex) return null
            val kind = when {
                isArrival -> GoogleDirectionsStepKind.ARRIVAL
                rowIndex == 0 && departure.containsMatchIn(instruction) -> GoogleDirectionsStepKind.DEPARTURE
                else -> GoogleDirectionsStepKind.MANEUVER
            }
            var distance: Double? = null
            var label = ""
            if (rowIndex < rows.lastIndex) {
                val leg = rows[rowIndex + 1]
                val values = leg.texts.map(::clean).filter { it.isNotBlank() }
                val parsed = values.mapNotNull { value ->
                    NotificationTextParser.parseDistance(value, Locale.US)?.takeIf { isDistanceOnly(value) }
                }
                if (parsed.isEmpty() || parsed.any { kotlin.math.abs(it.meters - parsed.first().meters) > .001 }) return null
                distance = parsed.first().meters
                label = parsed.first().label
                if (!distance.isFinite() || distance < 0 || distance > 2_000_000) return null
            }
            pending.add(GoogleDirectionsStep(pending.size, rowIndex, instruction, distance, label, kind, ""))
        }
        if (pending.size > MAX_STEPS) return null
        val destination = if (pending.lastOrNull()?.kind == GoogleDirectionsStepKind.ARRIVAL)
            rows.last().texts.map(::clean).filter { it.isNotBlank() && !arrival.containsMatchIn(it) }
                .distinct().take(2).joinToString(", ").take(360).ifBlank { null } else null
        val fingerprint = fingerprint(pending, destination)
        return GoogleDirectionsSnapshot(fingerprint, pending.map { it.copy(key = "$fingerprint:${it.index}") },
            capturedAtElapsed, pending.lastOrNull()?.kind == GoogleDirectionsStepKind.ARRIVAL, destination)
    }

    fun normalize(instruction: String): String = Normalizer.normalize(clean(instruction), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trimEnd('.', '!', ',')

    private fun clean(value: String): String = value.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("[\\s\\u00a0\\u202f]+"), " ").trim()

    private fun isDistanceOnly(value: String): Boolean {
        val parsed = NotificationTextParser.parseDistance(value, Locale.US) ?: return false
        return value.substring(0, parsed.start).isBlank() && value.substring(parsed.end).isBlank()
    }

    private fun fingerprint(steps: List<GoogleDirectionsStep>, destination: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (step in steps) {
            // Length-prefixed fields prevent delimiter-bearing names from aliasing another route.
            for (value in listOf(step.index.toString(), normalize(step.instruction), step.kind.name,
                // Maps' initial/current leg can shrink while the same route is being driven.
                if (step.index == 0) "current-leg" else step.followingDistanceMeters?.toString().orEmpty(),
                if (step.kind == GoogleDirectionsStepKind.ARRIVAL) destination.orEmpty() else "")) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Exact, ambiguity-rejecting alignment. Never guesses an index from a road-name substring. */
object GoogleDirectionsAlignment {
    fun find(snapshot: GoogleDirectionsSnapshot, instruction: String, previousIndex: Int? = null): Int? {
        if (!snapshot.complete || instruction.length !in 2..600) return null
        val wanted = GoogleDirectionsParser.normalize(instruction)
        val matches = snapshot.steps.filter { GoogleDirectionsParser.normalize(it.instruction) == wanted }
        if (previousIndex == null) return matches.singleOrNull()?.index
        if (previousIndex !in snapshot.steps.indices) return null
        return matches.filter { it.index >= previousIndex }.singleOrNull()?.index
    }
}
