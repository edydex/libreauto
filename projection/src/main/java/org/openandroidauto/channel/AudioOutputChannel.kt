package org.openandroidauto.channel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.locks.LockSupport

/** Owned PCM source. A false enqueue result is explicit backpressure, never silent dropping. */
class AudioOutputChannel(private val channelId: UByte, private val callback: VideoChannelCallback,
    private val configurationIndex: Int = 0,
    private val requestFocus: (Boolean) -> Unit = {}) {
    constructor(channelId: UByte, callback: VideoChannelCallback, requestFocus: (Boolean) -> Unit) :
        this(channelId, callback, 0, requestFocus)
    init { require(configurationIndex in 0..65535) { "Configuration index out of bounds" } }
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2
        const val BIT_DEPTH = 16
        const val BUFFER_SIZE = 19200
        private const val MAX_QUEUE = 8
        private const val PACKET_BYTES = 3840 // 20ms stereo16
        private const val MAX_FOCUS_EVENTS = 16
    }
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var configured = false
    private var focusGranted = false
    private var requested = false
    private var started = false
    private var running = false
    private var session = 1
    private var maxUnacked = 1
    private var unacked = 0
    private var framesSent = 0L
    private var epochNanos = 0L
    private val worker = JoinedChannelWorker()
    private var closed = false
    private val focusEvents = ArrayDeque<Boolean>()
    private var playbackGeneration = 0
    // Modern gal.Setup field1 is the codec (PCM=1), not a format-list index.
    fun sendSetup() = sendMessage(AVMessageType.SETUP_REQUEST, AvSetupConfiguration.unsignedField(1,1))
    fun onMessage(type: Int, payload: ByteArray) {
        when (type) {
            AVMessageType.SETUP_REQUEST -> sendMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(8, 2, 16, 5, 24, 0))
            AVMessageType.SETUP_RESPONSE -> synchronized(lock) {
                configured = field(payload, 1, 0) == 2
                maxUnacked = field(payload, 2, 1).coerceIn(1, 32); lock.notifyAll()
            }
            AVMessageType.START_INDICATION -> synchronized(lock) { session = field(payload,1,1); configured = true; lock.notifyAll() }
            AVMessageType.STOP_INDICATION -> stopPlayback()
            AVMessageType.AV_MEDIA_ACK -> synchronized(lock) { unacked = (unacked - field(payload,2,1).coerceIn(1,32)).coerceAtLeast(0); lock.notifyAll() }
        }
    }
    fun enqueuePcm(bytes: ByteArray): Boolean {
        require(bytes.isNotEmpty() && bytes.size <= 65536 && bytes.size % 4 == 0) { "Expected bounded 48kHz stereo16 PCM frames" }
        synchronized(lock) {
            if (closed || !configured || queue.size >= MAX_QUEUE ||
                !requested && focusEvents.size > MAX_FOCUS_EVENTS - 2) return false
            val copy = bytes.copyOf()
            if (!running) {
                // Native exit, not only a stop flag, is required before reuse.
                if (worker.isActive) { copy.fill(0); return false }
                running = true
                val candidate = Thread({ pump() }, "OpenAuto-PCM").apply { isDaemon = true }
                if (worker.start(candidate) != JoinedChannelWorker.StartResult.STARTED) {
                    running = false; closed = true; copy.fill(0); lock.notifyAll()
                    return false
                }
            }
            queue.addLast(copy)
            if (!requested) { requested = true; focusEvents.addLast(true) }
            lock.notifyAll()
        }
        return true
    }
    fun setFocusGranted(granted: Boolean) { synchronized(lock) {
        if (closed) return
        focusGranted = granted; if (!granted) epochNanos = 0; lock.notifyAll()
    } }
    fun stopPlayback(notifyFocus: Boolean = true) {
        synchronized(lock) {
            clearQueue(); playbackGeneration++
            if (requested && notifyFocus) focusEvents.addLast(false) // Capacity reserved by admission.
            if (!notifyFocus) focusEvents.clear()
            requested = false; focusGranted = false; started = false
            framesSent = 0; epochNanos = 0; unacked = 0; lock.notifyAll()
        }
        // Focus callbacks run on the tracked worker too, never on an untracked
        // producer or the main thread. Release precedes a later play request.
    }
    /** Request-only terminal stop. Late producers are refused. */
    fun stop() {
        synchronized(lock) {
            closed = true; running = false; clearQueue(); playbackGeneration++
            requested = false; focusEvents.clear()
            focusGranted = false; started = false; lock.notifyAll()
        }
        worker.interrupt()
    }
    /** Off-main. Interruption does not turn a live worker into a cleanup proof. */
    fun awaitStopped(): Boolean = worker.awaitStopped()
    fun stopAndJoin(): Boolean { stop(); return awaitStopped() }

    private fun clearQueue() { while (queue.isNotEmpty()) queue.removeFirst().fill(0) }
    private fun pump() {
        try {
            while (true) {
                var chunk: ByteArray? = null
                var generation = 0
                var sendStart = false
                var focusChange: Boolean? = null
                synchronized(lock) {
                    while (running && focusEvents.isEmpty() &&
                        (queue.isEmpty() || !configured || !focusGranted)) lock.wait()
                    if (!running) return
                    when {
                        focusEvents.isNotEmpty() -> { focusChange = focusEvents.removeFirst() }
                        else -> {
                            if (!started) { started = true; sendStart = true }
                            chunk = queue.removeFirst(); generation = playbackGeneration
                        }
                    }
                }
                if (focusChange != null) { requestFocus(focusChange!!); continue }
                val selected = chunk ?: continue
                try {
                    if (sendStart) {
                        synchronized(lock) { if (!running) return; if (!requested || generation != playbackGeneration) continue }
                        sendMessage(AVMessageType.START_INDICATION,
                            AvSetupConfiguration.unsignedField(1,session)+AvSetupConfiguration.unsignedField(2,configurationIndex))
                    }
                    var offset = 0
                    while (offset < selected.size) {
                        val size = minOf(PACKET_BYTES, selected.size - offset)
                        val timestampUs: Long
                        val targetNanos: Long
                        synchronized(lock) {
                            while (running && generation == playbackGeneration && (unacked >= maxUnacked || !focusGranted)) lock.wait()
                            if (!running) return
                            if (!requested || generation != playbackGeneration) break
                            if (epochNanos == 0L) epochNanos = System.nanoTime() - framesSent * 1_000_000_000L / SAMPLE_RATE
                            timestampUs = framesSent * 1_000_000L / SAMPLE_RATE
                            targetNanos = epochNanos + framesSent * 1_000_000_000L / SAMPLE_RATE
                            framesSent += size / 4; unacked++
                        }
                        val delay = targetNanos - System.nanoTime()
                        if (delay > 0) LockSupport.parkNanos(delay)
                        // A paused/stopped generation must not emit its reserved
                        // packet after pacing. Active packets remain whole frames.
                        synchronized(lock) {
                            if (!running) return
                            if (!requested || generation != playbackGeneration) break
                        }
                        val packet = ByteBuffer.allocate(10+size).order(ByteOrder.BIG_ENDIAN)
                            .putShort(AVMessageType.AV_MEDIA_WITH_TIMESTAMP.toShort()).putLong(timestampUs)
                            .put(selected,offset,size).array()
                        callback.onVideoFrame(channelId, packet); offset += size
                    }
                } finally { selected.fill(0) }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally {
            synchronized(lock) {
                running = false; closed = true; clearQueue(); requested = false
                focusEvents.clear()
                focusGranted = false; started = false; lock.notifyAll()
            }
        }
    }
    private fun sendMessage(type: Int, payload: ByteArray) = callback.onSendMessage(channelId,
        ByteBuffer.allocate(2+payload.size).order(ByteOrder.BIG_ENDIAN).putShort(type.toShort()).put(payload).array())
    private fun field(data: ByteArray, wanted: Int, fallback: Int): Int {
        var i=0
        while(i<data.size) { val tag=data[i++].toInt() and 255; if(tag and 7 != 0) return fallback
            var value=0;var shift=0
            while(i<data.size) { val b=data[i++].toInt() and 255; value=value or ((b and 127) shl shift);if(b and 128==0) break;shift+=7;if(shift>28)return fallback }
            if(tag ushr 3==wanted)return value
        }
        return fallback
    }
}
