// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test

class GoogleDirectionsParserTest {
    private fun rows() = listOf(
        GoogleDirectionsRow(0, listOf("Head north on Test Road")),
        GoogleDirectionsRow(1, listOf("500 ft"), listOf("Continue for 500 feet")),
        GoogleDirectionsRow(2, listOf("Turn left onto First Street"), listOf("Turn left onto First Street")),
        GoogleDirectionsRow(3, listOf("0.5 mi"), listOf("Continue for 0.5 miles")),
        GoogleDirectionsRow(4, listOf("Turn right onto Second Avenue")),
        GoogleDirectionsRow(5, listOf("100 m")),
        GoogleDirectionsRow(6, listOf("Synthetic destination", "Destination will be on the right")))
    private fun parse(rows: List<GoogleDirectionsRow> = rows(), now: Long = 1000) =
        requireNotNull(GoogleDirectionsParser.parse(rows, now))

    @Test fun fullListHasOrderedDepartureManeuversAndArrival() {
        val value = parse()
        assertTrue(value.complete)
        assertEquals(listOf(0, 1, 2, 3), value.steps.map { it.index })
        assertEquals(listOf(0, 2, 4, 6), value.steps.map { it.rowIndex })
        assertEquals(listOf(GoogleDirectionsStepKind.DEPARTURE, GoogleDirectionsStepKind.MANEUVER,
            GoogleDirectionsStepKind.MANEUVER, GoogleDirectionsStepKind.ARRIVAL), value.steps.map { it.kind })
    }
    @Test fun distancesBelongAfterTheirInstructions() {
        val value = parse().steps
        assertEquals(152.4, value[0].followingDistanceMeters!!, .0001)
        assertEquals(804.672, value[1].followingDistanceMeters!!, .0001)
        assertEquals(100.0, value[2].followingDistanceMeters!!, .0001)
        assertNull(value[3].followingDistanceMeters)
        assertEquals("500 ft", value[0].followingDistanceLabel)
    }
    @Test fun allDirectionsTextAndOrderSurviveOffscreenCopies() {
        val value = parse()
        assertEquals("Turn left onto First Street", value.steps[1].instruction)
        assertEquals("Turn right onto Second Avenue", value.steps[2].instruction)
        assertEquals("Synthetic destination", value.steps.last().instruction)
    }
    @Test fun duplicateInstructionsKeepSeparateStableKeys() {
        val copy = rows().toMutableList()
        copy[4] = copy[4].copy(texts = copy[2].texts)
        val value = parse(copy)
        assertEquals(value.steps[1].instruction, value.steps[2].instruction)
        assertNotEquals(value.steps[1].key, value.steps[2].key)
        assertEquals("${value.fingerprint}:2", value.steps[2].key)
    }
    @Test fun fingerprintsDoNotIncludeCaptureTimeDescriptionsOrWhitespace() {
        val first = parse()
        val copy = rows().map { it.copy(texts = it.texts.map { text -> "  $text  ".replace(" ", "\u00a0") },
            descriptions = it.descriptions.map { text -> text.uppercase() }) }
        val next = parse(copy, 2000)
        assertEquals(first.fingerprint, next.fingerprint)
        assertEquals(2000, next.capturedAtElapsed)
    }
    @Test fun instructionDistanceAndOrderChangeFingerprint() {
        val first = parse().fingerprint
        val instruction = rows().toMutableList().apply { this[2] = this[2].copy(texts = listOf("Turn left onto Other Street")) }
        val distance = rows().toMutableList().apply { this[3] = this[3].copy(texts = listOf("0.6 mi")) }
        val order = rows().toMutableList().apply { val a = this[2]; this[2] = this[4].copy(rowIndex = 2); this[4] = a.copy(rowIndex = 4) }
        assertNotEquals(first, parse(instruction).fingerprint)
        assertNotEquals(first, parse(distance).fingerprint)
        assertNotEquals(first, parse(order).fingerprint)
    }
    @Test fun shrinkingInitialLegDoesNotCreateANewTripFingerprint() {
        val copy = rows().toMutableList(); copy[1] = copy[1].copy(texts = listOf("100 ft"))
        assertEquals(parse().fingerprint, parse(copy).fingerprint)
        assertNotEquals(parse().steps.first().followingDistanceMeters, parse(copy).steps.first().followingDistanceMeters)
    }
    @Test fun arrivalNameAndAddressAreAvailableOnlyAsLocalDestinationQuery() {
        val copy = rows().toMutableList()
        copy[6] = copy[6].copy(texts = listOf("Synthetic destination", "123 Test Avenue", "Destination will be on the right"))
        val first = parse(copy)
        assertEquals("Synthetic destination, 123 Test Avenue", first.destinationQuery)
        copy[6] = copy[6].copy(texts = listOf("Synthetic destination", "456 Other Avenue", "Destination will be on the right"))
        assertNotEquals(first.fingerprint, parse(copy).fingerprint)
    }
    @Test fun partialListIsNeverMarkedComplete() {
        val partial = parse(rows().take(5))
        assertFalse(partial.complete)
        assertNull(GoogleDirectionsAlignment.find(partial, partial.steps.first().instruction))
    }
    @Test fun gapsDuplicatesAndUnorderedRowsAreRejected() {
        val first = rows()
        assertNull(GoogleDirectionsParser.parse(first.mapIndexed { i, row -> if (i == 2) row.copy(rowIndex = 3) else row }, 1))
        assertNull(GoogleDirectionsParser.parse(first.reversed(), 1))
        assertNull(GoogleDirectionsParser.parse(first + first.last(), 1))
    }
    @Test fun blankAndDistanceOnlyInstructionsAreRejected() {
        for (bad in listOf("", " ", "500 ft")) {
            val copy = rows().toMutableList(); copy[0] = GoogleDirectionsRow(0, listOf(bad))
            assertNull(GoogleDirectionsParser.parse(copy, 1))
        }
    }
    @Test fun missingMalformedOrConflictingLegsAreRejected() {
        for (bad in listOf(emptyList(), listOf("Continue straight"), listOf("-5 m"), listOf("5 m", "10 m"))) {
            val copy = rows().toMutableList(); copy[1] = copy[1].copy(texts = bad)
            assertNull(GoogleDirectionsParser.parse(copy, 1))
        }
    }
    @Test fun arrivalCannotAppearBeforeAnotherManeuver() {
        val copy = rows().toMutableList(); copy[2] = copy[2].copy(descriptions = listOf("Destination will be on the left"))
        assertNull(GoogleDirectionsParser.parse(copy, 1))
    }
    @Test fun anArbitraryDestinationNameIsNotProofOfArrival() {
        val copy = rows().toMutableList(); copy[6] = copy[6].copy(texts = listOf("Destination Coffee"))
        assertNull(GoogleDirectionsParser.parse(copy, 1))
    }
    @Test fun remainingSuffixChoosesInstructionAfterAuxiliaryCurrentLegLabel() {
        val copy = rows().drop(2).mapIndexed { index, row -> row.copy(rowIndex = index) }.toMutableList()
        copy[0] = copy[0].copy(texts = listOf("Synthetic current-leg label", "Turn left onto First Street"))
        val value = parse(copy)
        assertTrue(value.complete)
        assertEquals("Turn left onto First Street", value.steps.first().instruction)
        assertEquals(GoogleDirectionsStepKind.MANEUVER, value.steps.first().kind)
        assertEquals(804.672, value.steps.first().followingDistanceMeters!!, .0001)
        assertEquals(3, value.steps.size)
    }
    @Test fun boundsRejectOversizedRowsTextAndInvalidClock() {
        assertNull(GoogleDirectionsParser.parse(rows(), 0))
        assertNull(GoogleDirectionsParser.parse(emptyList(), 1))
        assertNull(GoogleDirectionsParser.parse(List(GoogleDirectionsParser.MAX_ROWS + 1) { GoogleDirectionsRow(it, listOf("x")) }, 1))
        assertNull(GoogleDirectionsParser.parse(rows().mapIndexed { i, row -> if (i == 0) row.copy(texts = listOf("x".repeat(601))) else row }, 1))
        assertNull(GoogleDirectionsParser.parse(rows().mapIndexed { i, row -> if (i == 0) row.copy(texts = List(33) { "x" }) else row }, 1))
    }
    @Test fun metricAndNonbreakingSpaceDistanceWorks() {
        val copy = rows().toMutableList(); copy[1] = copy[1].copy(texts = listOf("1.5\u00a0km"))
        assertEquals(1500.0, parse(copy).steps.first().followingDistanceMeters!!, 0.0)
    }
    @Test fun descriptionOnlyInstructionCanBeCopied() {
        val copy = rows().toMutableList(); copy[2] = copy[2].copy(texts = emptyList())
        assertEquals("Turn left onto First Street", parse(copy).steps[1].instruction)
    }
    @Test fun exactAlignmentDoesNotGuessFromStreetSubstrings() {
        val value = parse()
        assertEquals(1, GoogleDirectionsAlignment.find(value, "turn LEFT onto First Street."))
        assertNull(GoogleDirectionsAlignment.find(value, "First Street"))
        assertNull(GoogleDirectionsAlignment.find(value, "Turn right onto First Street"))
    }
    @Test fun repeatedInstructionsAreAmbiguousWithoutProgress() {
        val copy = rows().toMutableList(); copy[4] = copy[4].copy(texts = copy[2].texts)
        val value = parse(copy)
        assertNull(GoogleDirectionsAlignment.find(value, value.steps[1].instruction))
        assertNull(GoogleDirectionsAlignment.find(value, value.steps[1].instruction, 1))
        assertEquals(2, GoogleDirectionsAlignment.find(value, value.steps[1].instruction, 2))
    }
    @Test fun alignmentCannotMoveBackwardsOrUseInvalidProgress() {
        val value = parse()
        assertNull(GoogleDirectionsAlignment.find(value, value.steps[0].instruction, 2))
        assertNull(GoogleDirectionsAlignment.find(value, value.steps[1].instruction, 50))
        assertEquals(2, GoogleDirectionsAlignment.find(value, value.steps[2].instruction, 1))
    }
}
