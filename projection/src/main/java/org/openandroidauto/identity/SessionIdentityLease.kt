// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.identity

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Event-driven owner around BOTH action and cleanup. A stop requests real I/O close,
 * then cancellation/join; neither cancellation nor elapsed time proves cleanup.
 * There is no drive-duration cap or periodic watcher/proof polling. */
internal object SessionIdentityLease {
    data class Completion(val joined: Boolean, val cleanupVerified: Boolean, val operationFailed: Boolean)

    suspend fun run(stop: ProjectionStopSignal, requestStop: () -> Unit,
        cleanupVerified: () -> Boolean, onRetained: () -> Unit,
        body: suspend () -> Unit): Completion = withContext(NonCancellable) {
        supervisorScope {
            val operation = AtomicReference<Job?>()
            val failed = AtomicBoolean()
            fun requestClose() {
                try { requestStop() } catch (_: Exception) { failed.set(true) }
                operation.get()?.cancel()
            }
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                stop.awaitStop()
                requestClose()
            }
            try {
                val actual = async(start = CoroutineStart.UNDISPATCHED) { body() }
                operation.set(actual)
                if (stop.isStopped) requestClose()
                try { actual.await() }
                catch (_: CancellationException) { /* Join and prove cleanup below. */ }
                catch (_: Throwable) { failed.set(true); requestClose() }
                finally { actual.join(); watcher.cancelAndJoin() }
                if (!cleanupVerified()) {
                    requestClose()
                    try { onRetained() } catch (_: Exception) { }
                    // No normal future event can upgrade failed finalization. Keep the
                    // provider/material and global session owner held without waking CPU.
                    awaitCancellation()
                }
                Completion(true, true, failed.get())
            } catch (_: Throwable) {
                // A partially-started coroutine/native provider cannot be certified here.
                // Remain held, including allocation failures at operation publication.
                requestClose()
                try { onRetained() } catch (_: Throwable) { }
                awaitCancellation()
            } finally { watcher.cancelAndJoin() }
        }
    }
}
