package org.openandroidauto.channel

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Display
import android.view.Surface
import org.openandroidauto.ServiceState
import org.openandroidauto.render.FrameCadenceCounters
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AVMessageType {
    const val AV_MEDIA_WITH_TIMESTAMP = 0x0000
    const val AV_MEDIA = 0x0001
    const val SETUP_REQUEST = 0x8000
    const val START_INDICATION = 0x8001
    const val STOP_INDICATION = 0x8002
    const val SETUP_RESPONSE = 0x8003
    const val AV_MEDIA_ACK = 0x8004
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_INDICATION = 0x8008
}
data class VideoConfig(val width: Int = 800, val height: Int = 480, val fps: Int = 30,
    val dpi: Int = 160, val bitrate: Int = 2_000_000)
interface VideoChannelCallback {
    fun onVideoFrame(channelId: UByte, payload: ByteArray)
    fun onSendMessage(channelId: UByte, payload: ByteArray)
    fun onVideoDisplayReady(display: Display) {}
    fun onVideoDisplayRemoved() {}
}
enum class VideoState { IDLE, SETUP_SENT, CONFIGURED, FOCUSED, STARTED }

/** Encodes only our own virtual display. No screen-capture consent or system input injection. */
class VideoChannel(private val channelId: UByte, private val callback: VideoChannelCallback,
    private val cadence: FrameCadenceCounters.Session = FrameCadenceCounters.currentSession()) {
    var config = VideoConfig(); private set
    @Volatile var state = VideoState.IDLE; private set
    @Volatile var maxUnacked = 1; private set
    var sessionId = 1; private set
    var configurationIndex = 0
    private var context: Context? = null
    private val lifecycleLock = Object()
    private val worker = RestartableChannelWorker("OpenAuto-Encoder") { generation ->
        val app = context
        if (app == null) true else encodeAndDrain(app, config, generation)
    }
    private var unackedFrames = 0
    private val unackedLock = Object()

    fun attachContext(context: Context) { this.context = context.applicationContext }
    fun configure(config: VideoConfig) {
        synchronized(lifecycleLock) {
            require(!worker.isActive) { "Cannot reconfigure an encoder before it has stopped" }
            require(config.width in 320..3840 && config.height in 240..2160 && config.fps in 1..60 && config.dpi in 80..640)
            this.config = config
        }
    }
    fun onMessage(messageType: Int, payload: ByteArray) {
        if (worker.isClosed) return
        when (messageType) {
            AVMessageType.SETUP_REQUEST -> sendMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(8, 2, 16, 5, 24, 0))
            AVMessageType.SETUP_RESPONSE -> {
                maxUnacked = field(payload, 2, 1).coerceIn(1, 32)
                if (field(payload, 1, 0) == 2) state = VideoState.CONFIGURED
            }
            AVMessageType.START_INDICATION -> {
                sessionId = field(payload, 1, 1); state = VideoState.STARTED; startEncoding()
            }
            AVMessageType.STOP_INDICATION -> pauseEncoding()
            AVMessageType.VIDEO_FOCUS_REQUEST -> sendMessage(AVMessageType.VIDEO_FOCUS_INDICATION, byteArrayOf(8, 1, 16, 0))
            AVMessageType.VIDEO_FOCUS_INDICATION -> if (field(payload, 1, 0) == 1 && state in setOf(VideoState.CONFIGURED, VideoState.SETUP_SENT)) {
                state = VideoState.FOCUSED; sendStart()
            }
            AVMessageType.AV_MEDIA_ACK -> synchronized(unackedLock) {
                unackedFrames = (unackedFrames - field(payload, 2, 1).coerceIn(1, 32)).coerceAtLeast(0)
                unackedLock.notifyAll()
            }
        }
    }
    fun sendSetup() { if (worker.isClosed) return; sendMessage(AVMessageType.SETUP_REQUEST, byteArrayOf(8, 3)); state = VideoState.SETUP_SENT }
    fun sendStart() {
        if (worker.isClosed) return
        sendMessage(AVMessageType.START_INDICATION, byteArrayOf(8, sessionId.toByte(), 16, configurationIndex.toByte()))
        state = VideoState.STARTED; startEncoding()
    }
    /** Terminal request only: permanent admission close, including pending peer
     * restart. All native teardown remains on the tracked owner thread. */
    fun stop() {
        worker.stop(); state = VideoState.IDLE
        synchronized(unackedLock) { unackedFrames = 0; unackedLock.notifyAll() }
    }
    private fun pauseEncoding() {
        worker.pause(); state = VideoState.IDLE
        synchronized(unackedLock) { unackedFrames = 0; unackedLock.notifyAll() }
    }
    /** Off-main; true means actual owner exit AND verified native resource release.
     * Self-join, native release failure or a still-stuck provider is not success. */
    fun awaitStopped(): Boolean = worker.awaitStopped()
    fun stopAndJoin(): Boolean { stop(); return awaitStopped() }

    private fun isCurrent(generation: Long): Boolean = worker.isCurrent(generation)
    private fun startEncoding() {
        context ?: return // JVM protocol tests never start Android resources.
        if (!worker.requestStart()) {
            state = VideoState.IDLE
            ServiceState.addEvent("Video worker could not start")
        }
    }
    private fun encodeAndDrain(app: Context, config: VideoConfig, generation: Long): Boolean {
        if (!org.openandroidauto.integration.ProjectionAvailability.isSupported()) return true
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var display: VirtualDisplay? = null
        var published = false
        var releaseVerified = true
        try {
          run encoding@ {
            if (!isCurrent(generation)) return@encoding
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, if (config.height > 720) MediaCodecInfo.CodecProfileLevel.AVCLevel4 else MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                // An idle UI produces no continuous draw loop. Repeat its last frame at 1Hz.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.fps.toFloat())
            }
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.supportedTypes.any { mime -> mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
            val selected = infos.firstOrNull { it.isHardwareAccelerated && it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }
                ?: infos.firstOrNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }
                ?: error("No H264 encoder supports negotiated format")
            if (!isCurrent(generation)) return@encoding
            codec = MediaCodec.createByCodecName(selected.name)
            if (!isCurrent(generation)) return@encoding
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (!isCurrent(generation)) return@encoding
            surface = codec.createInputSurface()
            if (!isCurrent(generation)) return@encoding
            codec.start()
            val cadenceStream = cadence.encoderStarted()
            if (!isCurrent(generation)) return@encoding
            val manager = app.getSystemService(DisplayManager::class.java)
            display = manager.createVirtualDisplay("Open Auto Dashboard", config.width, config.height, config.dpi,
                surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                ?: error("Could not create dashboard display")
            synchronized(lifecycleLock) {
                if (!isCurrent(generation)) return@encoding
                published = true
                callback.onVideoDisplayReady(display.display)
            }
            ServiceState.addEvent("Encoder ${selected.name} ${config.width}x${config.height}@${config.fps}")
            drainEncoder(codec, generation, cadenceStream)
          }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (failure: Exception) {
            if (isCurrent(generation)) {
                Log.e("OpenAutoVideo", "Encoder failed", failure)
                ServiceState.addEvent("Video encoder stopped")
            }
        } finally {
            // No other thread calls these resources, even if cancellation arrived
            // while configure/start/createVirtualDisplay was still in native code.
            try {
                try { if (published) callback.onVideoDisplayRemoved() }
                finally {
                    try { try { display?.release() } catch (_: Exception) { releaseVerified = false } }
                    finally {
                        try { try { codec?.stop() } catch (_: Exception) {} }
                        finally {
                            try { try { codec?.release() } catch (_: Exception) { releaseVerified = false } }
                            finally { try { surface?.release() } catch (_: Exception) { releaseVerified = false } }
                        }
                    }
                }
            } finally {
                if (isCurrent(generation)) state = VideoState.IDLE
                synchronized(unackedLock) { unackedFrames = 0; unackedLock.notifyAll() }
            }
        }
        return releaseVerified
    }
    private fun drainEncoder(codec: MediaCodec, generation: Long, cadenceStream: FrameCadenceCounters.EncoderStream) {
        val info = MediaCodec.BufferInfo()
        var firstTime = -1L
        var configBytes = byteArrayOf()
        try {
            while (isCurrent(generation)) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    configBytes = listOfNotNull(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1")).fold(byteArrayOf()) { a, b ->
                        val bytes = ByteArray(b.remaining()); b.duplicate().get(bytes); a + annexB(bytes)
                    }
                } else if (index >= 0) {
                    try {
                        val output = codec.getOutputBuffer(index)
                        if (output != null && info.size > 0) {
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); output.get(bytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                cadenceStream.configurationOutput()
                                configBytes = annexB(bytes)
                            }
                            else {
                                cadenceStream.encodedOutput(info.presentationTimeUs, info.size)
                                if (firstTime < 0) firstTime = info.presentationTimeUs
                                val data = (if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) configBytes else byteArrayOf()) + annexB(bytes)
                                if (sendVideoFrame(data, (info.presentationTimeUs - firstTime).coerceAtLeast(0), generation))
                                    cadenceStream.frameCallbackCompleted(data.size)
                            }
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Exception) { if (isCurrent(generation)) ServiceState.addEvent("Video stream stopped") }
    }
    private fun sendVideoFrame(data: ByteArray, timeUs: Long, generation: Long): Boolean {
        synchronized(unackedLock) {
            while (isCurrent(generation) && unackedFrames >= maxUnacked) unackedLock.wait(250)
            if (!isCurrent(generation)) return false
            unackedFrames++
        }
        callback.onVideoFrame(channelId, ByteBuffer.allocate(10 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(AVMessageType.AV_MEDIA_WITH_TIMESTAMP.toShort()).putLong(timeUs).put(data).array())
        return true
    }
    private fun sendMessage(type: Int, payload: ByteArray) = callback.onSendMessage(channelId,
        ByteBuffer.allocate(2 + payload.size).order(ByteOrder.BIG_ENDIAN).putShort(type.toShort()).put(payload).array())
    companion object {
        internal fun annexB(data: ByteArray): ByteArray {
            if (data.size < 4 || (data[0] == 0.toByte() && data[1] == 0.toByte() && (data[2] == 1.toByte() || data[2] == 0.toByte() && data[3] == 1.toByte()))) return data
            val out = java.io.ByteArrayOutputStream(data.size + 32); var p = 0
            while (p + 4 <= data.size) {
                val len = ByteBuffer.wrap(data, p, 4).int; p += 4
                require(len > 0 && len <= data.size - p) { "Invalid H264 NAL length" }
                out.write(byteArrayOf(0,0,0,1)); out.write(data,p,len); p += len
            }
            require(p == data.size) { "Incomplete H264 NAL" }
            return out.toByteArray()
        }
        private fun field(data: ByteArray, target: Int, fallback: Int): Int {
            var i = 0
            while (i < data.size) {
                val tag = data[i++].toInt() and 255
                if (tag and 7 != 0) break
                var value = 0; var shift = 0
                do { if (i >= data.size || shift > 28) return fallback; val b = data[i++].toInt() and 255
                    value = value or ((b and 127) shl shift); shift += 7
                    if (b and 128 == 0) break
                } while (true)
                if (tag ushr 3 == target) return value
            }
            return fallback
        }
    }
}
