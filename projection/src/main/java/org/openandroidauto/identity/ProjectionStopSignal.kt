// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.identity

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/** Durable, one-shot session cancellation. No timers, polling, or credential references. */
internal class ProjectionStopSignal {
    private val stopped = AtomicBoolean()
    private val event = CompletableDeferred<Unit>()
    val isStopped: Boolean get() = stopped.get()
    fun stop(): Boolean {
        val first = stopped.compareAndSet(false, true)
        event.complete(Unit)
        return first
    }
    suspend fun awaitStop() { event.await() }
}
