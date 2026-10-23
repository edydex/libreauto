package org.openandroidauto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds a connected car's protocol handshake independently of socket activity. */
internal class ProjectionReadinessDeadline(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 30_000,
    private val onTimeout: () -> Unit
) : AutoCloseable {
    private enum class State { WAITING, READY, TIMED_OUT, CLOSED }
    private val lock = Any()
    private var state = State.WAITING
    private var started = false
    private val readiness = CompletableDeferred<Unit>()
    private var job: Job? = null
    init { require(timeoutMs > 0) }

    fun start() = synchronized(lock) {
        if (started || state != State.WAITING) return@synchronized
        started = true
        job = scope.launch {
            val ready = withTimeoutOrNull(timeoutMs) { readiness.await(); true } ?: false
            synchronized(lock) {
                if (!ready && state == State.WAITING) { state = State.TIMED_OUT; onTimeout() }
            }
        }
    }

    fun ready(): Boolean = synchronized(lock) {
        when (state) {
            State.WAITING -> { state = State.READY; readiness.complete(Unit); true }
            State.READY -> true
            else -> false
        }
    }

    override fun close() = synchronized(lock) { state = State.CLOSED; job?.cancel(); Unit }
}
