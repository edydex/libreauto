// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.notifications

import java.util.concurrent.atomic.AtomicLong

/** Binds one asynchronous transcript to the composer that requested it.
 * Tokens are correlation values, not security credentials. No message, recipient
 * identifier or draft content is retained. A stale result/cancellation cannot
 * consume a replacement composer's permit.
 */
internal class ReplyTurnGate {
    private data class Turn(val token: Long, val targetGeneration: Long)
    private var turn: Turn? = null

    @Synchronized fun prepare(targetGeneration: Long): Long {
        turn?.takeIf { it.targetGeneration == targetGeneration }?.let { return it.token }
        return mint().also { turn = Turn(it, targetGeneration) }
    }

    @Synchronized fun current(targetGeneration: Long): Long? =
        turn?.takeIf { it.targetGeneration == targetGeneration }?.token

    @Synchronized fun consume(token: Long, targetGeneration: Long): Boolean {
        val active = turn ?: return false
        if (active.token != token || active.targetGeneration != targetGeneration) return false
        turn = null
        return true
    }

    @Synchronized fun cancel(token: Long? = null) {
        if (token == null || turn?.token == token) turn = null
    }

    companion object {
        private val sequence = AtomicLong()
        private fun mint(): Long {
            while (true) {
                val value = sequence.incrementAndGet()
                if (value != 0L) return value
            }
        }
    }
}
