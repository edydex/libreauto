package org.openandroidauto

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Process ownership is released only by the monitor AFTER the session job really joins.
 * This gate contains no transport, identity or service reference. */
internal class ProjectionSessionLifetime {
    companion object { private val owner = AtomicReference<ProjectionSessionLifetime?>(null) }
    private val started = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private val completed = AtomicBoolean()
    fun acquire(): Boolean = owner.compareAndSet(null, this)
    fun begin(): Boolean = !stopping.get() && started.compareAndSet(false, true)
    fun requestStop(): Boolean = stopping.compareAndSet(false, true)
    val accepting: Boolean get() = !stopping.get() && !completed.get()
    fun completeAfterJoin(cleanupProven: Boolean): Boolean {
        if (!stopping.get() || !cleanupProven) return false
        if (completed.get()) return true
        if (!owner.compareAndSet(this, null)) return false
        completed.set(true)
        return true
    }
}
