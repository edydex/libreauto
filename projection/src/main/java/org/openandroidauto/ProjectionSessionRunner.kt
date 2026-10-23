package org.openandroidauto

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A scoped hook owns action AND finalization; onJoined follows actual owner completion. */
internal class ProjectionSessionRunner(private val owner: CoroutineScope) {
    fun launch(action: suspend () -> Unit, cleanup: suspend () -> Unit,
               onJoined: suspend () -> Unit): Job = launchScoped<Unit>(
        action = { action() }, cleanup = cleanup, onJoined = onJoined,
        aroundBody = { body -> body(Unit) })

    fun <T> launchScoped(action: suspend (T) -> Unit, cleanup: suspend () -> Unit,
                         onJoined: suspend () -> Unit,
                         aroundBody: suspend (suspend (T) -> Unit) -> Unit): Job {
        val ownerEntered = AtomicBoolean()
        val bodyEntered = AtomicBoolean()
        val cleanupEntered = AtomicBoolean()
        suspend fun cleanupOnce() {
            if (cleanupEntered.compareAndSet(false, true))
                withContext(NonCancellable) { cleanup() }
        }
        val job = owner.launch {
            ownerEntered.set(true)
            try {
                aroundBody { value ->
                    check(bodyEntered.compareAndSet(false, true)) { "Session body already entered" }
                    try { action(value) } finally { cleanupOnce() }
                }
            } finally {
                // Preparation refusal has created no scoped TLS consumer. Still close the
                // service's pre-existing UI/foreground resources, without acquiring identity.
                if (!bodyEntered.get()) cleanupOnce()
            }
        }
        owner.launch {
            job.join()
            // Cancellation before first dispatch enters neither the hook nor its finally.
            if (!ownerEntered.get()) cleanupOnce()
            onJoined()
        }
        return job
    }
}
