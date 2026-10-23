package org.openandroidauto.ui

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import org.openandroidauto.voice.VoiceSessionHandler
import org.openandroidauto.render.FrameCadenceCounters

/** One native map/messages/calls/music view on the actual encoded display. */
class ProjectionDashboard(context: Context, display: Display,
    private val metricsSession: FrameCadenceCounters.Session = FrameCadenceCounters.disabledSession()
) : Presentation(context, display) {
    @Volatile private var product: NativeDashboardView? = null
    @Volatile private var closingProduct: NativeDashboardView? = null
    private var audioOutput: (ByteArray) -> Boolean = { false }
    private var mediaFocus: (Boolean) -> Unit = {}
    private var microphoneControl: (Boolean) -> Unit = {}
    private var voiceSessionListener: (Boolean) -> Unit = {}
    private var voiceHandler: VoiceSessionHandler? = null
    private var phoneCallActive = false
    private var callsRequested = false
    private var cancelableFlowListener: (Boolean) -> Unit = {}
    private var cancelableFlow = false
    private var callHandoffStillValid: () -> Boolean = { false }
    private var callHandoffGate: ((Boolean) -> Unit) -> Unit = { it(false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        product = NativeDashboardView(context, projectionCapable = true, metricsSession = metricsSession).also {
            it.setAudioOutput(audioOutput); it.setMediaFocusRequest(mediaFocus)
            it.setMicrophoneControl(microphoneControl); it.setVoiceSessionListener(voiceSessionListener)
            voiceHandler?.let(it::setVoiceHandler)
            it.setCancelableFlowListener(::onCancelableFlowChanged)
            it.setCallHandoffGate(callHandoffGate)
            it.setCallHandoffStillValid(callHandoffStillValid)
            it.setPhoneCallActive(phoneCallActive)
            if (callsRequested) it.showCalls()
            setContentView(it)
        }
    }
    override fun onStart() { super.onStart(); product?.onStart() }
    override fun onStop() { product?.onStop(); super.onStop() }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if(event.keyCode==android.view.KeyEvent.KEYCODE_BACK || event.keyCode==android.view.KeyEvent.KEYCODE_ESCAPE)
            return product?.dispatchKeyEvent(event) ?: true
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Back navigates the dashboard instead of dismissing its Presentation")
    override fun onBackPressed() { product?.handleBackNavigation() }
    override fun dismiss() {
        val view=product;product=null
        if(view!=null)closingProduct=view
        try {
            if(view!=null)view.close() else voiceHandler?.close()
        } finally {
            voiceHandler=null
            try { onCancelableFlowChanged(false) } finally { super.dismiss() }
        }
    }
    /** Preserve the detached view's worker handles until actual exit has been observed off-main. */
    fun awaitMediaStopped(timeoutMillis: Long): Boolean {
        val view = closingProduct ?: return product == null
        val stopped = view.awaitMediaStopped(timeoutMillis)
        if (stopped && closingProduct === view) closingProduct = null
        return stopped
    }
    fun setAudioOutput(output: (ByteArray) -> Boolean) { audioOutput=output;product?.setAudioOutput(output) }
    fun setMediaFocusRequest(request: (Boolean) -> Unit) { mediaFocus=request;product?.setMediaFocusRequest(request) }
    fun setMicrophoneControl(control: (Boolean) -> Unit) { microphoneControl=control;product?.setMicrophoneControl(control) }
    fun setVoiceSessionListener(listener: (Boolean) -> Unit) { voiceSessionListener=listener;product?.setVoiceSessionListener(listener) }
    fun setVoiceHandler(handler: VoiceSessionHandler?) {
        if(voiceHandler===handler && product==null)return
        if(product==null)voiceHandler?.close()
        voiceHandler=handler;product?.setVoiceHandler(handler)
    }
    fun requiresCarMicrophone(): Boolean = product?.requiresCarMicrophone() ?: (voiceHandler?.requiresCarMicrophone==true)
    fun voicePreparationTimeoutMs(): Long = product?.voicePreparationTimeoutMs() ?: 15_000L
    fun requestVoiceSession() { product?.requestVoiceSession() ?: run { microphoneControl(false);voiceSessionListener(false) } }
    fun onMediaKey(keyCode: Int) { product?.onMediaKey(keyCode) }
    fun onVoiceSession(start: Boolean) { product?.onVoiceSession(start) ?: run { microphoneControl(false);voiceSessionListener(false) } }
    fun onCarMicrophonePcm(pcm: ByteArray,sampleRate: Int) { product?.onCarMicrophonePcm(pcm,sampleRate) }
    fun showCalls() { callsRequested=true; product?.showCalls() }
    fun confirmCallOrShowCalls() {
        val view=product
        // A delayed presentation may show contacts; it must never replay a dial confirmation.
        if(view==null) callsRequested=true else view.confirmCallOrShowCalls()
    }
    fun cancelPendingCommand() {
        callsRequested=false
        product?.cancelPendingCommand() ?: run { microphoneControl(false); voiceSessionListener(false); onCancelableFlowChanged(false) }
    }
    fun setCancelableFlowListener(listener: (Boolean) -> Unit) {
        cancelableFlowListener=listener; listener(cancelableFlow)
    }
    fun setCallHandoffGate(gate: ((Boolean) -> Unit) -> Unit) {
        callHandoffGate=gate; product?.setCallHandoffGate(gate)
    }
    fun setCallHandoffStillValid(check: () -> Boolean) {
        callHandoffStillValid=check; product?.setCallHandoffStillValid(check)
    }
    fun hasCancelableFlow(): Boolean = cancelableFlow
    private fun onCancelableFlowChanged(active: Boolean) {
        if(cancelableFlow==active)return
        cancelableFlow=active; cancelableFlowListener(active)
    }
    fun setPhoneCallActive(active: Boolean) { phoneCallActive=active; product?.setPhoneCallActive(active) }
    fun diagnosticFlags(): String = "presentation_showing=$isShowing " + ((product ?: closingProduct)?.diagnosticFlags() ?: "dashboard_absent=true")
    fun dashboardView(): NativeDashboardView? = product
}
