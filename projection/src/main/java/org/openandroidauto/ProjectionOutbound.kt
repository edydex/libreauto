package org.openandroidauto

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select

/** Bounded backpressure while active; closing cancels queued work AND blocked producers. */
internal class ProjectionOutbound<T : Any>(priorityCapacity: Int = 32, mediaCapacity: Int = 8) {
    private val open = AtomicBoolean(true)
    private val priority = Channel<T>(priorityCapacity)
    private val media = Channel<T>(mediaCapacity)
    fun send(value: T, control: Boolean) {
        if (!open.get()) return
        try { runBlocking { (if (control) priority else media).send(value) } }
        catch (cancelled: CancellationException) { if (open.get()) throw cancelled }
        catch (closed: ClosedSendChannelException) { if (open.get()) throw closed }
    }
    suspend fun receive(): T = priority.tryReceive().getOrNull() ?: select {
        priority.onReceive { it }; media.onReceive { it }
    }
    fun close() {
        if (open.compareAndSet(true, false)) {
            val cause = CancellationException("Projection session stopped")
            priority.cancel(cause); media.cancel(cause)
        }
    }
}
