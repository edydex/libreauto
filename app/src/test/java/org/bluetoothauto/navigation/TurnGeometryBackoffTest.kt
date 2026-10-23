// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.junit.Assert.*
import org.junit.Test

class TurnGeometryBackoffTest {
    @Test fun initialRequestIsAvailableImmediately() {
        val policy = TurnGeometryBackoff()
        assertEquals(0L, policy.retryInMillis(0))
        assertEquals(0, policy.consecutiveFailures)
        assertEquals("", policy.lastFailureStatus)
    }

    @Test fun requestStartsEnforceMinimumSpacingWithoutCountingFailure() {
        val policy = TurnGeometryBackoff()
        policy.started(1_000)
        assertEquals(30_000L, policy.retryInMillis(1_000))
        assertEquals(1L, policy.retryInMillis(30_999))
        assertEquals(0L, policy.retryInMillis(31_000))
        assertEquals(0, policy.consecutiveFailures)
    }

    @Test fun successiveFailuresBackOffFromCompletionWithFiveMinuteCap() {
        val policy = TurnGeometryBackoff()
        var now = 1_000L
        for ((index, wait) in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L).withIndex()) {
            policy.started(now)
            now += 8_400
            policy.failed(now, "TIMEOUT")
            assertEquals(wait, policy.retryInMillis(now))
            assertEquals((index + 1).coerceAtMost(6), policy.consecutiveFailures)
            now += wait
        }
    }

    @Test fun repeatedLocalChecksCannotEraseLastNetworkFailureOrMoveDeadline() {
        val policy = TurnGeometryBackoff()
        policy.started(0); policy.failed(8_400, "TIMEOUT")
        val deadline = policy.nextAllowedAtMillis
        for (now in 9_000L..38_000L step 3_000) {
            policy.retryInMillis(now)
            assertEquals(deadline, policy.nextAllowedAtMillis)
            assertEquals("TIMEOUT", policy.lastFailureStatus)
            assertEquals(1, policy.consecutiveFailures)
        }
    }

    @Test fun cancellationDoesNotAdvanceOrErasePreviousFailure() {
        val policy = TurnGeometryBackoff()
        policy.started(0); policy.failed(5_000, "HTTP_503")
        val deadline = policy.nextAllowedAtMillis
        policy.failed(10_000, "CANCELLED")
        assertEquals(1, policy.consecutiveFailures)
        assertEquals("HTTP_503", policy.lastFailureStatus)
        assertEquals(deadline, policy.nextAllowedAtMillis)
    }

    @Test fun successfulFetchOrCachedMatchClearsStreakAndFailure() {
        val policy = TurnGeometryBackoff()
        policy.started(0); policy.failed(8_000, "TIMEOUT")
        policy.succeeded()
        assertEquals(0, policy.consecutiveFailures)
        assertEquals("", policy.lastFailureStatus)
        policy.failed(40_000, "TLS")
        assertEquals(30_000L, policy.retryInMillis(40_000))
    }

    @Test fun successCannotDefeatMinimumRequestSpacing() {
        val policy = TurnGeometryBackoff()
        policy.started(1_000); policy.succeeded()
        assertEquals(29_000L, policy.retryInMillis(2_000))
        assertEquals(0L, policy.retryInMillis(31_000))
    }

    @Test fun recoveryDropsExtendedFailureBackoffButRetainsRecentRequestSpacing() {
        val policy = TurnGeometryBackoff()
        repeat(5) { policy.failed(0, "TIMEOUT") }
        assertEquals(300_000L, policy.retryInMillis(0))
        policy.started(10_000)
        policy.succeeded()
        assertEquals(20_000L, policy.retryInMillis(20_000))
        assertEquals(0L, policy.retryInMillis(40_000))
    }

    @Test fun arbitraryFailureMessagesCannotEnterDiagnostics() {
        val policy = TurnGeometryBackoff()
        for (value in listOf("https://private.example", "point=47.123,-122.456", "WAITING_COURSE", "HTTP_999", "HTTP_-1")) {
            policy.failed(0, value)
            assertEquals("ERROR", policy.lastFailureStatus)
        }
        policy.failed(0, "HTTP_429")
        assertEquals("HTTP_429", policy.lastFailureStatus)
    }

    @Test fun cappedLongRunningFailuresDoNotOverflow() {
        val policy = TurnGeometryBackoff()
        repeat(100) { policy.failed(0, "NETWORK") }
        assertEquals(6, policy.consecutiveFailures)
        assertEquals(300_000L, policy.retryInMillis(0))
        policy.started(Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, policy.nextAllowedAtMillis)
    }
}
