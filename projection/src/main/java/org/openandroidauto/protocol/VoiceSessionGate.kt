package org.openandroidauto.protocol

/** Pure session gate: requesting an assistant never implies its recorder is ready. */
class VoiceSessionGate(private val setMicrophone: (Boolean) -> Unit) {
    var requested = false; private set
    var microphoneOpen = false; private set
    var hasOpened = false; private set
    var generation = 0; private set
    var requiresMicrophone = true; private set
    fun request(start: Boolean, requiresMicrophone: Boolean = true, newAttempt: Boolean = false): Int {
        if (start == requested && requiresMicrophone == this.requiresMicrophone && !newAttempt) return generation
        generation++
        requested = start
        this.requiresMicrophone = requiresMicrophone
        hasOpened = false
        transition(false)
        return generation
    }
    fun recordingReady(ready: Boolean) {
        val open = requested && requiresMicrophone && ready
        if (open) hasOpened = true
        transition(open)
    }
    /** A stale timeout can never cancel a later session; only initial readiness is bounded. */
    fun expire(token: Int): Boolean {
        if (token != generation || !requested || !requiresMicrophone || hasOpened) return false
        request(false)
        return true
    }
    private fun transition(open: Boolean) {
        if (microphoneOpen == open) return
        microphoneOpen = open
        setMicrophone(open)
    }
}
