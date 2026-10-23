package org.openandroidauto.channel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.openandroidauto.protocol.ProtoFields

/** Phone-side head-unit PCM input. Modern AVInput sources use channel-open then
 * MicrophoneRequest directly; AV-sink codec setup is a different lifecycle. */
class AudioInputChannel(private val channelId: UByte, private val callback: VideoChannelCallback,
    private val trace: MicrophoneProtocolTrace? = null,
    private val configurationIndex: Int = 0,
    private val mode: Mode = Mode.DIRECT_OPEN,
    private val onInvalidPcm: () -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val ownedReceiver: OwnedPcmReceiver? = null,
    private val onPcm: (ByteArray, Int) -> Unit = { pcm, _ -> pcm.fill(0) }) {
    /** Epoch is captured before PCM copying; accept always consumes the owned array. */
    interface OwnedPcmReceiver {
        fun captureEpoch(): Long
        fun invalid(epoch: Long)
        fun accept(epoch: Long, pcm: ByteArray, sampleRate: Int)
    }
    enum class Mode { DIRECT_OPEN, LEGACY_SETUP_REQUIRED }
    constructor(channelId: UByte, callback: VideoChannelCallback, onPcm: (ByteArray, Int) -> Unit) :
        this(channelId, callback, null, 0, Mode.DIRECT_OPEN, {}, {}, null, onPcm)
    companion object { const val SAMPLE_RATE = 16000; const val CHANNEL_COUNT = 1 }
    /** Content-free advisory state. Totals count protocol-admitted PCM before mailbox delivery,
     * not recognition or audible content; no PCM, session identifiers or speech are exposed. */
    @Synchronized fun diagnosticFlags(): String = "mic_requested=$requested mic_pending_open=$pendingOpen mic_acknowledged_open=$acknowledgedOpen mic_close_required=$closeRequired mic_session_valid=$sessionValid mic_terminal=$terminal mic_pcm_packets=$pcmPackets mic_pcm_bytes=$pcmBytes"
    init { require(configurationIndex == 0) { "Microphone has one advertised configuration" } }
    private var channelOpened = false
    private var legacyConfigured = false
    private var requested = false
    private var session = 1
    private var sessionValid = false
    private var terminal = false
    private var firstPcmReported = false
    private var pendingOpen: Boolean? = null
    private var acknowledgedOpen = false
    private var closeRequired = false
    private var pcmPackets = 0L
    private var pcmBytes = 0L
    private fun readyForOpen() = channelOpened && !terminal && (mode == Mode.DIRECT_OPEN || legacyConfigured)

    /** Called only after the negotiated AVInput channel successfully opened. */
    @Synchronized fun onChannelOpened() {
        if (terminal || channelOpened) return
        channelOpened = true
        if (mode == Mode.DIRECT_OPEN) record(MicrophoneProtocolTrace.Stage.DIRECT_OPEN_READY)
        else {
            record(MicrophoneProtocolTrace.Stage.SETUP_SENT,AVMessageType.SETUP_REQUEST)
            // Old aasdk setup-required compatibility is explicit, never selected by an error.
            sendMessage(AVMessageType.SETUP_REQUEST,AvSetupConfiguration.encode(configurationIndex))
        }
        if (requested) sendRequestedOpen()
    }
    @Synchronized fun requestOpen(open: Boolean) {
        if (terminal) return
        requested = open
        if (!open) {
            sessionValid = false
            // Even if a new turn arrives before this OPEN response, the old capture
            // must cross an acknowledged CLOSE barrier before it can authorize PCM.
            if (pendingOpen == true || acknowledgedOpen) closeRequired = true
        }
        record(MicrophoneProtocolTrace.Stage.REQUEST_OPEN)
        sendRequestedOpen()
    }
    private fun sendRequestedOpen() {
        // Exactly one request is outstanding. A late close response cannot authorize
        // a newer turn, and an idle channel never emits an unsolicited close.
        if (!readyForOpen() || pendingOpen != null) return
        val next = if (closeRequired) false else requested
        if (!closeRequired && acknowledgedOpen == next) return
        if (next) sessionValid = false
        pendingOpen = next
        record(MicrophoneProtocolTrace.Stage.OPEN_SENT,0x8005)
        sendMessage(0x8005,byteArrayOf(8,if(next)1 else 0,16,0,24,0,32,5))
    }
    @Synchronized fun onMessage(type: Int,payload: ByteArray) {
        if (terminal) return
        if (type != AVMessageType.AV_MEDIA_WITH_TIMESTAMP && type != AVMessageType.AV_MEDIA)
            record(MicrophoneProtocolTrace.Stage.MESSAGE_RECEIVED,type,payload.size)
        when(type) {
            AVMessageType.SETUP_RESPONSE -> if (mode == Mode.LEGACY_SETUP_REQUIRED && channelOpened) {
                val status=field(payload,1,0);legacyConfigured=status==2
                record(MicrophoneProtocolTrace.Stage.SETUP_RESPONSE,type,payload.size,status)
                if (legacyConfigured && requested) sendRequestedOpen()
            }
            0x8006 -> if (channelOpened && pendingOpen != null) {
                val responseForOpen = pendingOpen == true
                val returnedSession=responseSession(payload)
                pendingOpen=null
                if(returnedSession!=null) {
                    session=returnedSession
                    acknowledgedOpen=responseForOpen
                    if (!responseForOpen) closeRequired=false
                    sessionValid=responseForOpen && requested && !closeRequired
                } else {
                    requested=false;acknowledgedOpen=false;sessionValid=false;closeRequired=false
                }
                record(MicrophoneProtocolTrace.Stage.OPEN_RESPONSE,type,payload.size,
                    if(returnedSession!=null)0 else -1,returnedSession ?: -1)
                if(returnedSession!=null)sendRequestedOpen()
            }
            AVMessageType.AV_MEDIA_WITH_TIMESTAMP -> receive(payload,true)
            AVMessageType.AV_MEDIA -> receive(payload,false)
            AVMessageType.STOP_INDICATION -> {
                val wasRequested = requested
                // Peer stopping this input stream does not close its negotiated channel.
                requested=false;sessionValid=false
                // Keep any outstanding response reserved. Reusing it for a new OPEN
                // would make a late pre-STOP response appear to belong to the new turn.
                closeRequired = pendingOpen == true
                acknowledgedOpen=false
                // A delayed stop for an idle/closing capture must not end the next
                // recognizer attempt, which may already be preparing with input closed.
                if (wasRequested) onStopped()
                record(MicrophoneProtocolTrace.Stage.STOP)
            }
            // 0x00ff/unknown messages never change the negotiated mode or start capture.
        }
    }
    /** Terminal service teardown, unlike peer stream STOP. */
    @Synchronized fun stop() {
        record(MicrophoneProtocolTrace.Stage.STOP)
        terminal=true;requested=false;channelOpened=false;legacyConfigured=false;sessionValid=false;pendingOpen=null;acknowledgedOpen=false;closeRequired=false
        onStopped()
    }
    private fun receive(payload: ByteArray,timestamp: Boolean) {
        val epoch = ownedReceiver?.captureEpoch() ?: 0
        val offset=if(timestamp)8 else 0
        if(!channelOpened || !sessionValid)return
        val size=payload.size-offset
        if(size<=0 || size%2!=0 || size>CarPcmIngress.MAX_BYTES) {
            if(requested) { if(ownedReceiver!=null)ownedReceiver.invalid(epoch) else onInvalidPcm() }
            sendMessage(AVMessageType.AV_MEDIA_ACK,
                AvSetupConfiguration.unsignedField(1,session)+AvSetupConfiguration.unsignedField(2,1))
            return
        }
        if (!firstPcmReported) {
            firstPcmReported=true
            record(MicrophoneProtocolTrace.Stage.FIRST_PCM,
                if(timestamp)AVMessageType.AV_MEDIA_WITH_TIMESTAMP else AVMessageType.AV_MEDIA,payload.size-offset,session=session)
        }
        // The receiver/mailbox consumes the owned copy even on refusal; legacy
        // callbacks also own it and must wipe it after synchronous consumption.
        if(requested) {
            pcmPackets++
            pcmBytes += size
            val pcm=payload.copyOfRange(offset,payload.size)
            if(ownedReceiver!=null)ownedReceiver.accept(epoch,pcm,SAMPLE_RATE) else onPcm(pcm,SAMPLE_RATE)
        }
        sendMessage(AVMessageType.AV_MEDIA_ACK,
            AvSetupConfiguration.unsignedField(1,session)+AvSetupConfiguration.unsignedField(2,1))
    }
    private fun record(stage:MicrophoneProtocolTrace.Stage,type:Int=-1,bytes:Int=0,status:Int=-1,session:Int=-1) {
        trace?.record(stage,channelId.toInt(),type,bytes,legacyConfigured,requested,status,session,
            configurationIndex,mode,channelOpened,readyForOpen())
    }
    private fun responseSession(payload: ByteArray): Int? {
        if(payload.size>128)return null
        val value=try { ProtoFields.read(payload).filter{it.number==1}.singleOrNull()?.integer }
            catch(_:IllegalArgumentException){null} catch(_:IllegalStateException){null}
        return if(value!=null && value in 0..Int.MAX_VALUE.toLong())value.toInt()else null
    }
    private fun sendMessage(type:Int,payload:ByteArray)=callback.onSendMessage(channelId,
        ByteBuffer.allocate(2+payload.size).order(ByteOrder.BIG_ENDIAN).putShort(type.toShort()).put(payload).array())
    private fun field(data:ByteArray,wanted:Int,fallback:Int):Int {
        return try { val value=ProtoFields.read(data).filter{it.number==wanted}.singleOrNull()?.integer
            if(value!=null && value in 0..Int.MAX_VALUE.toLong())value.toInt()else fallback
        } catch(_:IllegalArgumentException){fallback} catch(_:IllegalStateException){fallback}
    }
}
