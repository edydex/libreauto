package org.openandroidauto.channel

import java.util.concurrent.atomic.AtomicInteger

/** Temporary typed diagnostics. Never receives PCM bytes, addresses or text. */
class MicrophoneProtocolTrace(private val enabled: () -> Boolean,
                              private val sink: (Event) -> Unit) {
    enum class Stage { DISCOVERED, CHANNEL_OPENED, DIRECT_OPEN_READY, SETUP_SENT, ROUTED_MESSAGE, MESSAGE_RECEIVED,
        SETUP_RESPONSE, REQUEST_OPEN, OPEN_SENT, OPEN_RESPONSE, FIRST_PCM, STOP }
    data class Event(val stage: Stage, val channel: Int, val type: Int,
                     val bytes: Int, val configured: Boolean, val requested: Boolean,
                     val status: Int, val session: Int, val configurationIndex: Int,
                     val inputMode: AudioInputChannel.Mode?, val channelOpened: Boolean, val openReady: Boolean)
    private val emitted = AtomicInteger()
    fun record(stage: Stage, channel: Int, type: Int = -1, bytes: Int = 0,
               configured: Boolean = false, requested: Boolean = false,
               status: Int = -1, session: Int = -1, configurationIndex: Int = -1,
               inputMode: AudioInputChannel.Mode? = null, channelOpened: Boolean = false, openReady: Boolean = false) {
        if (emitted.get() >= 32 || !enabled()) return
        while (true) {
            val old = emitted.get()
            if (old >= 32) return
            if (emitted.compareAndSet(old, old + 1)) break
        }
        sink(Event(stage, channel, type, bytes, configured, requested, status, session, configurationIndex,
            inputMode,channelOpened,openReady))
    }
}
