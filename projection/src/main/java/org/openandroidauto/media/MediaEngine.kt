package org.openandroidauto.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/** Local/SAF media -> actual head-unit PCM, with no phone-speaker fallback.
 * One worker owns extractor/decoder. At most one pending track replaces an old
 * request; PCM is paced in 20 ms packets and never grows a transport queue.
 */
class MediaEngine @JvmOverloads constructor(
    context: Context,
    private val sink: PcmSink,
    private val listener: Listener = object : Listener {}
) : Closeable {
    fun interface PcmSink {
        /** Worker-thread callback. Return false for transport backpressure.
         * Must return promptly (never wait for a future ACK); accepted bytes
         * belong to the sink. timestampUs is monotonic presentation time, not
         * track position. PCM is 48 kHz, stereo, signed PCM16 little endian.
         */
        fun write(pcm: ByteArray, timestampUs: Long): Boolean
    }

    interface Listener { fun onState(snapshot: Snapshot) {} }
    enum class Status { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }
    data class Snapshot(
        val status: Status = Status.IDLE,
        val title: String = "",
        val artist: String = "",
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        val queueIndex: Int = -1,
        val queueSize: Int = 0,
        val error: String? = null
    )

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val PACKET_FRAMES = 960
        const val PACKET_BYTES = PACKET_FRAMES * CHANNELS * 2
        private const val MAX_TRANSPORT_WAIT_NS = 5_000_000_000L
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    @Volatile private var current = Snapshot()
    val snapshot: Snapshot get() = current
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private val closeRequested = AtomicBoolean()
    private var paused = false
    private var paceEpoch = 0L
    private var requestedSeekUs: Long? = null
    private var queue = emptyList<Uri>()
    private var queueIndex = -1
    private var pending: Track? = null
    @Volatile private var gain = 1f
    @Volatile private var duck = 1f
    private data class Track(val uri: Uri, val generation: Long)
    private val worker = MediaWorkerLifetime({ workerLoop() }, "OpenAuto-MediaDecode")

    fun play(uri: Uri) = playQueue(listOf(uri))

    @JvmOverloads fun playQueue(uris: List<Uri>, startIndex: Int = 0) {
        require(uris.isNotEmpty() && uris.size <= 512) { "Choose between 1 and 512 audio files" }
        require(startIndex in uris.indices) { "Invalid queue index" }
        uris.forEach {
            require(it.scheme == "content" || it.scheme == "file") {
                "Choose a local audio file with the system file picker; streaming URLs are not supported"
            }
        }
        synchronized(lock) {
            check(!closed) { "Media engine is closed" }
            queue = uris.toList()
            queueIndex = startIndex
            requestTrackLocked()
        }
    }

    fun pause() {
        synchronized(lock) {
            if (closed || current.status !in setOf(Status.PLAYING, Status.LOADING)) return
            paused = true
            paceEpoch++
            publishLocked(current.copy(status = Status.PAUSED))
            lock.notifyAll()
        }
    }

    fun resume() {
        synchronized(lock) {
            if (closed) return
            if (current.status in setOf(Status.ENDED, Status.ERROR, Status.IDLE) && queueIndex in queue.indices) {
                requestTrackLocked()
                return
            }
            if (!paused || current.status != Status.PAUSED) return
            paused = false
            paceEpoch++
            publishLocked(current.copy(status = Status.PLAYING, error = null))
            lock.notifyAll()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (closed) return
            generation.incrementAndGet()
            pending = null
            paused = false
            requestedSeekUs = null
            paceEpoch++
            publishLocked(current.copy(status = Status.IDLE, positionMs = 0, error = null))
            lock.notifyAll()
        }
        worker.interrupt()
    }

    /** Discard the queue and every pending track/state delivery; stop() deliberately keeps it resumable. */
    fun clearQueue() {
        synchronized(lock) {
            if (closed) return
            generation.incrementAndGet()
            pending = null
            queue = emptyList()
            queueIndex = -1
            paused = false
            requestedSeekUs = null
            paceEpoch++
            publishLocked(Snapshot())
            lock.notifyAll()
        }
        worker.interrupt()
    }

    fun seek(positionMs: Long) {
        synchronized(lock) {
            if (closed || current.status !in setOf(Status.PLAYING, Status.PAUSED, Status.LOADING)) return
            val max = current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE / 1000
            requestedSeekUs = positionMs.coerceIn(0, max) * 1000
            paceEpoch++
            lock.notifyAll()
        }
    }

    fun seekTo(positionMs: Long) = seek(positionMs)

    fun next() {
        synchronized(lock) {
            if (!closed && queueIndex + 1 in queue.indices) {
                queueIndex++
                requestTrackLocked()
            }
        }
    }

    fun previous() {
        synchronized(lock) {
            if (!closed && queueIndex - 1 in queue.indices) {
                queueIndex--
                requestTrackLocked()
            } else if (current.positionMs > 0) seek(0)
        }
    }

    fun setVolume(value: Float) { gain = value.coerceIn(0f, 1f) }
    /** Set e.g. 0.2 during guidance/assistant speech, then restore 1.0. */
    fun setDucking(value: Float) { duck = value.coerceIn(0f, 1f) }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        // Do not wait on a provider, codec or in-flight sink's monitor from the UI. Volatile
        // invalidation and generation change make every later decode/state delivery stale.
        closed = true
        generation.incrementAndGet()
        worker.stop()
        main.removeCallbacksAndMessages(this)
    }

    /** Actual worker exit, including extractor/codec finally cleanup. Call off-main after close(). */
    fun awaitStopped(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Media decoder joins must run off-main" }
        return closed && worker.awaitStopped(timeoutMillis)
    }
    fun isWorkerAlive(): Boolean = worker.isAlive()

    private fun requestTrackLocked() {
        val token = generation.incrementAndGet()
        pending = Track(queue[queueIndex], token)
        paused = false
        requestedSeekUs = null
        paceEpoch++
        publishLocked(Snapshot(status = Status.LOADING, queueIndex = queueIndex, queueSize = queue.size))
        lock.notifyAll()
        if (!worker.start()) {
            generation.incrementAndGet()
            pending = null
            publishLocked(current.copy(status = Status.ERROR, error = "The audio decoder worker could not start"))
        }
        worker.interrupt()
    }

    private fun publishLocked(value: Snapshot) {
        current = value
        main.postAtTime({ if (!closed && current === value) listener.onState(value) }, this,
            android.os.SystemClock.uptimeMillis())
    }

    private fun update(token: Long, change: (Snapshot) -> Snapshot) {
        synchronized(lock) { if (activeLocked(token)) publishLocked(change(current)) }
    }

    private fun activeLocked(token: Long) = !closed && token == generation.get()
    private fun active(token: Long) = synchronized(lock) { activeLocked(token) }

    private fun workerLoop() {
        while (true) {
            val track = synchronized(lock) {
                while (!closed && pending == null) {
                    try { lock.wait() } catch (_: InterruptedException) {}
                }
                if (closed) return
                pending.also { pending = null }!!
            }
            Thread.interrupted()
            try {
                decode(track)
                synchronized(lock) {
                    if (activeLocked(track.generation)) {
                        if (queueIndex + 1 in queue.indices) {
                            queueIndex++
                            requestTrackLocked()
                        } else publishLocked(current.copy(status = Status.ENDED))
                    }
                }
            } catch (_: InterruptedException) {
                // Control changes wake a paused/paced worker; never restart a stale track.
            } catch (error: Exception) {
                update(track.generation) { it.copy(status = Status.ERROR,
                    error = error.message?.take(180) ?: "Audio playback failed") }
            }
        }
    }

    private fun awaitPlayable(token: Long): Boolean = synchronized(lock) {
        while (activeLocked(token) && paused && requestedSeekUs == null) {
            try { lock.wait() } catch (_: InterruptedException) {}
        }
        activeLocked(token)
    }

    private inner class PacketOutput(private val token: Long, private val startPositionUs: Long) {
        private val partial = ByteArray(PACKET_BYTES)
        private var used = 0
        private var frames = 0L
        private var anchorNs = 0L
        private var lastEpoch = -1L
        private var lastUpdateNs = 0L

        fun add(bytes: ByteArray): Boolean {
            var offset = 0
            while (offset < bytes.size) {
                if (!active(token) || synchronized(lock) { requestedSeekUs != null }) {
                    return false
                }
                val count = min(PACKET_BYTES - used, bytes.size - offset)
                System.arraycopy(bytes, offset, partial, used, count)
                used += count
                offset += count
                if (used == PACKET_BYTES && !send(PACKET_BYTES)) return false
            }
            return true
        }

        fun finish(): Boolean = used == 0 || send(used)

        private fun send(count: Int): Boolean {
            if (!awaitPlayable(token)) return false
            var dueNs: Long
            synchronized(lock) {
                if (!activeLocked(token) || requestedSeekUs != null) return false
                val now = System.nanoTime()
                if (lastEpoch != paceEpoch || anchorNs == 0L) {
                    anchorNs = now - frames * 1_000_000_000L / SAMPLE_RATE
                    lastEpoch = paceEpoch
                }
                dueNs = anchorNs + frames * 1_000_000_000L / SAMPLE_RATE
                if (now - dueNs > 100_000_000L) {
                    anchorNs += now - dueNs
                    dueNs = now
                }
                while (activeLocked(token) && !paused && requestedSeekUs == null) {
                    val wait = dueNs - System.nanoTime()
                    if (wait <= 0) break
                    try { lock.wait(wait / 1_000_000, (wait % 1_000_000).toInt()) }
                    catch (_: InterruptedException) {}
                }
            }
            if (!awaitPlayable(token)) return false
            if (synchronized(lock) { requestedSeekUs != null }) return false
            val packet = partial.copyOf(count)
            val level = gain * duck
            if (level != 1f) scale(packet, level)
            val waitingSince = System.nanoTime()
            while (true) {
                val accepted = synchronized(lock) {
                    // Queue clear/replace must finish after any in-flight prompt sink call, and
                    // a cancelled generation must never start another call after that control returns.
                    if (!awaitPlayable(token) || requestedSeekUs != null) return false
                    sink.write(packet, System.nanoTime() / 1000)
                }
                if (accepted) break
                if (System.nanoTime() - waitingSince > MAX_TRANSPORT_WAIT_NS)
                    throw IOException("Head-unit audio is unavailable; reconnect before playing")
                synchronized(lock) {
                    try { lock.wait(10) } catch (_: InterruptedException) {}
                }
            }
            frames += count / 4
            used = 0
            val now = System.nanoTime()
            if (now - lastUpdateNs >= 250_000_000L) {
                val positionMs = (startPositionUs + frames * 1_000_000L / SAMPLE_RATE) / 1000
                update(token) { it.copy(positionMs = if (it.durationMs > 0)
                    positionMs.coerceAtMost(it.durationMs) else positionMs) }
                lastUpdateNs = now
            }
            return true
        }
    }

    private fun decode(track: Track) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            app.contentResolver.openAssetFileDescriptor(track.uri, "r").use { afd ->
                if (afd == null) throw IOException("The selected audio file is unavailable")
                extractor.setDataSource(afd)
            }
            if (!active(track.generation)) return
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("The selected file has no supported audio track")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing audio format")
            if (format.containsKey("crypto-key")) throw IOException("Protected audio is unsupported")
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            val title = format.getString("title")?.takeIf { it.isNotBlank() } ?: displayName(track.uri)
            val artist = format.getString("artist").orEmpty()
            update(track.generation) { it.copy(title = title, artist = artist, durationMs = durationMs,
                status = synchronized(lock) { if (paused) Status.PAUSED else Status.PLAYING }) }
            if (mime == "audio/raw") {
                decodeRaw(extractor, format, track.generation)
                return
            }
            // Decoders normally emit PCM16; negotiate explicitly and confirm the real output format.
            if (mime != "audio/raw") format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputEnded = false
            var converter: PcmConverter? = null
            var output: PacketOutput? = null
            var outputRate = 0
            var outputChannels = 0
            var outputEncoding = 0
            var discardBeforeUs = 0L
            val info = MediaCodec.BufferInfo()
            var lastProgressNs = System.nanoTime()
            var lastControlEpoch = synchronized(lock) { paceEpoch }
            while (active(track.generation)) {
                if (!awaitPlayable(track.generation)) return
                val seekUs = synchronized(lock) {
                    if (!activeLocked(track.generation)) return
                    if (lastControlEpoch != paceEpoch) {
                        lastProgressNs = System.nanoTime()
                        lastControlEpoch = paceEpoch
                    }
                    requestedSeekUs.also { requestedSeekUs = null }
                }
                if (seekUs != null) {
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    inputEnded = false
                    converter = null
                    output = null
                    discardBeforeUs = seekUs
                    lastProgressNs = System.nanoTime()
                    update(track.generation) { it.copy(positionMs = seekUs / 1000) }
                    if (synchronized(lock) { paused }) continue
                }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: throw IOException("Missing decoder input")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                                throw IOException("Protected audio is unsupported")
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgressNs = System.nanoTime()
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (converter != null) {
                            output?.add(converter.convert(ByteBuffer.allocate(0), true))
                            output?.finish()
                        }
                        val actual = codec.outputFormat
                        outputRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        converter = null
                        output = null
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (System.nanoTime() - lastProgressNs > 10_000_000_000L) {
                            throw IOException("Audio decoder stopped producing data")
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        lastProgressNs = System.nanoTime()
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = codec.getOutputBuffer(outputIndex) ?: throw IOException("Missing decoder output")
                                if (outputRate == 0) {
                                    val actual = codec.getOutputFormat(outputIndex)
                                    outputRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    outputChannels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    outputEncoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                        actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                }
                                if (converter == null) converter = PcmConverter(outputRate, outputChannels, outputEncoding)
                                val frameBytes = converter.frameSize()
                                val skipFrames = ceil(((discardBeforeUs - info.presentationTimeUs).coerceAtLeast(0)
                                    * outputRate) / 1_000_000.0).toInt()
                                val skippedBytes = min(info.size, skipFrames * frameBytes)
                                if (skippedBytes < info.size) {
                                    buffer.position(info.offset + skippedBytes)
                                    buffer.limit(info.offset + info.size)
                                    if (output == null) output = PacketOutput(track.generation,
                                        (info.presentationTimeUs + skippedBytes / frameBytes * 1_000_000L / outputRate).coerceAtLeast(0))
                                    output.add(converter.convert(buffer, false))
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                converter?.let { output?.add(it.convert(ByteBuffer.allocate(0), true)) }
                                output?.finish()
                                if (synchronized(lock) { requestedSeekUs != null }) continue
                                update(track.generation) { it.copy(positionMs = if (it.durationMs > 0)
                                    it.durationMs else it.positionMs) }
                                return
                            }
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    /** WAV/PCM already needs no codec: avoid assuming a vendor exposes a raw
     * decoder, and avoid decoding/re-encoding a synthesized guidance file.
     */
    private fun decodeRaw(extractor: MediaExtractor, format: MediaFormat, token: Long) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        var converter = PcmConverter(rate, channels, encoding)
        val frameBytes = converter.frameSize()
        val buffer = ByteBuffer.allocate(65_536 * frameBytes)
        var output: PacketOutput? = null
        var discardBeforeUs = 0L
        while (active(token)) {
            if (!awaitPlayable(token)) return
            val seekUs = synchronized(lock) {
                if (!activeLocked(token)) return
                requestedSeekUs.also { requestedSeekUs = null }
            }
            if (seekUs != null) {
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                converter = PcmConverter(rate, channels, encoding)
                output = null
                discardBeforeUs = seekUs
                update(token) { it.copy(positionMs = seekUs / 1000) }
                if (synchronized(lock) { paused }) continue
            }
            if (android.os.Build.VERSION.SDK_INT >= 28 && extractor.sampleSize > buffer.capacity()) {
                throw IOException("Raw PCM sample block exceeds the bounded reader size")
            }
            buffer.clear()
            val ptsUs = extractor.sampleTime
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                output?.add(converter.convert(ByteBuffer.allocate(0), true))
                output?.finish()
                if (synchronized(lock) { requestedSeekUs != null }) continue
                update(token) { it.copy(positionMs = if (it.durationMs > 0) it.durationMs else it.positionMs) }
                return
            }
            if (size % frameBytes != 0) throw IOException("Raw PCM input contains an incomplete frame")
            val availableFrames = size / frameBytes
            val wantedSkip = ceil((discardBeforeUs - ptsUs).coerceAtLeast(0).toDouble() * rate / 1_000_000)
            val skippedFrames = minOf(availableFrames.toDouble(), wantedSkip).toInt()
            val skippedBytes = skippedFrames * frameBytes
            if (skippedBytes < size) {
                buffer.position(skippedBytes)
                buffer.limit(size)
                if (output == null) output = PacketOutput(token,
                    (ptsUs + skippedFrames * 1_000_000L / rate).coerceAtLeast(0))
                output.add(converter.convert(buffer, false))
            }
            extractor.advance()
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                if (cursor?.moveToFirst() == true) return cursor.getString(0).orEmpty()
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "Audio file"
    }

    private fun scale(bytes: ByteArray, gain: Float) {
        for (index in bytes.indices step 2) {
            val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort()
            val result = (sample * gain).toInt().coerceIn(-32768, 32767)
            bytes[index] = result.toByte()
            bytes[index + 1] = (result shr 8).toByte()
        }
    }
}
