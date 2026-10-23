package org.openandroidauto.media

/** Admission and queue flush share a lock, so an old producer cannot enqueue after a call mutes us. */
internal class CallAudioOutputGate(
    private val output: (ByteArray) -> Boolean,
    private val flush: () -> Unit
) {
    @Volatile var blocked = false
        private set
    private var closed = false

    @Synchronized fun write(pcm: ByteArray): Boolean = !closed && !blocked && output(pcm)

    /** Decide and apply a peer's grant under the same lock as a call-mode flush. */
    @Synchronized fun applyFocus(granted: Boolean, update: (Boolean) -> Unit) {
        update(granted && !closed && !blocked)
    }

    @Synchronized fun setMode(mode: Int): Boolean {
        if (closed) return false
        // MODE_NORMAL=0; ringtone, managed calls, VoIP and call-redirection modes are positive.
        // Sentinel MODE_CURRENT=-1 / MODE_INVALID=-2 do not fabricate an active call.
        val next = mode > 0
        if (next == blocked) return false
        blocked = next
        if (blocked) flush()
        return true
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        blocked = true
        flush()
    }
}
