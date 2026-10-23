package org.openandroidauto.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.openandroidauto.voice.VoiceSessionHandler
import org.openandroidauto.voice.OptionalDictationClient
import org.openandroidauto.voice.DictationRouteGate
import org.openandroidauto.voice.DictationRouteGate.Purpose
import org.openandroidauto.voice.OfflineCommand
import org.openandroidauto.render.FrameCadenceCounters
import org.openandroidauto.calls.CallsPanel
import org.openandroidauto.ServiceState
import org.openandroidauto.notifications.NotificationsPanel
import org.openandroidauto.notifications.MessagingNotifications
import org.openandroidauto.media.AudioLibraryActivity
import org.openandroidauto.media.MediaEngine
import org.openandroidauto.media.AudioOwnerGate
import java.io.File
import android.net.Uri
import org.openandroidauto.media.MediaLibraryStore
import org.openandroidauto.integration.SharedLibraryIntegration
import org.openandroidauto.navigation.NavigationPanel
import java.io.Closeable
import java.util.Locale

/** The same native product view is used by the actual encoder Presentation and phone preview. */
class NativeDashboardView(context: Context, private val projectionCapable: Boolean = false,
    metricsSession: FrameCadenceCounters.Session = FrameCadenceCounters.disabledSession()) : LinearLayout(context), Closeable {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var audioOutput: (ByteArray) -> Boolean = { false }
    private var mediaFocusRequest: (Boolean) -> Unit = {}
    private var voiceSessionListener: (Boolean) -> Unit = {}
    private var voiceStatusGeneration = 0L
    private var started = false
    @Volatile private var closed = false
    private enum class Tab { MAP, MESSAGES, CALLS, MUSIC }
    private var selectedTab = Tab.MAP
    private var uiReady = false
    private var commandSequence = 0L
    private var cancelableFlowListener: (Boolean) -> Unit = {}
    private var callHandoffGate: ((Boolean) -> Unit) -> Unit = { ready -> ready(false) }
    private var callHandoffStillValid: () -> Boolean = { false }
    private var voiceHandler: VoiceSessionHandler? = null
    private var handlerGeneration = 0L
    private var microphoneControl: (Boolean) -> Unit = {}
    private var messageSummary: Closeable? = null
    @Volatile private var voiceActive = false
    private val voiceRoutes = DictationRouteGate()
    @Volatile private var phoneCallActive = false
    private var voiceInputReady = false
    @Volatile private var microphoneUnavailableReported = false
    private var mediaWasPlaying = false
    private var guidanceActive = false
    private var resumeMediaAfterGuidance = false
    private var guidanceFile: File? = null
    private val audioOwners = AudioOwnerGate({ pcm -> audioOutput(pcm) }, { active -> mediaFocusRequest(active) })
    private var lastStatus = ""
    private val hideStatus = Runnable { status.visibility = GONE }
    private var queueLoaded = false
    private var knownLibrarySelection: List<Uri>? = null
    private var receiverRegistered = false
    private var draggingSeek = false
    private var mediaSnapshot = MediaEngine.Snapshot()
    private var sharedPlaybackRegistration: Closeable? = null
    private var sharedPlaybackQueue = emptyList<Uri>()
    private val sharedPlaybackController = object : SharedLibraryIntegration.PlaybackController {
        override fun play(queue: List<Uri>, startIndex: Int) = onMain {
            if (started && !closed) playQueue(queue, startIndex)
        }
        override fun mediaKey(keyCode: Int) = onMediaKey(keyCode)
        override fun seekTo(positionMs: Long) = onMain {
            if (started && !closed) media.seekTo(positionMs)
        }
        override fun clear() = onMain {
            if (started && !closed) resetMediaQueue()
        }
    }
    private val backKeys = DashboardBackRouter(object : DashboardBackRouter.Host {
        override fun cancelVoiceInput(): Boolean {
            if (!voiceActive) return false
            onVoiceSession(false); setStatus("Voice input cancelled"); return true
        }
        override fun dismissContext(): Boolean = when (selectedTab) {
            Tab.MAP -> navigation.handleBackNavigation()
            Tab.CALLS -> calls.handleBackNavigation()
            Tab.MESSAGES -> notifications.handleBackNavigation().also { handled ->
                if (handled) { cancelMessageRead(resume = false); reportCancelableFlow() }
            }
            Tab.MUSIC -> false
        }
        override fun returnToMap(): Boolean {
            if (selectedTab == Tab.MAP) return false
            selectTab(Tab.MAP); return true
        }
        override fun atMapRoot() {
            // A Presentation is a Dialog: falling through here would dismiss its Surface.
            if (!projectionCapable) setStatus("Use Exit preview to close the preview")
        }
    })

    private val content = FrameLayout(context)
    private val status = text("", 15f).apply { visibility = GONE }
    val navigation = NavigationPanel(context, object : NavigationPanel.Listener {
        // Map owns its search/route/status cards; do not repeat them over the map.
        override fun onStatus(text: String) { }
        override fun onNavigationInstruction(text: String, distanceMeters: Double) { }
        override fun onSpeechAudioFile(file: File) { playGuidance(file) }
        override fun onSpeechCancelled() { stopGuidance(resume = true) }
        override fun onDictatePlaceSearchRequested(token: Long): Boolean = beginDictation(Purpose.MAP_PLACE_SEARCH, token)
        override fun onDictationCancelled(token: Long) { if (voiceRoutes.owns(Purpose.MAP_PLACE_SEARCH, token)) onVoiceSession(false) }
        override fun onCancelableFlowChanged(active: Boolean) { reportCancelableFlow() }
    })
    private val mediaPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(10))
        visibility = GONE
    }
    private val musicKicker = text("LOCAL MUSIC", 12f).apply { setTextColor(CarUi.ACCENT); letterSpacing = .12f }
    private val trackTitle = text("Your music, on the road", 26f).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val trackDetail = text("Choose audio files from your phone", 15f).apply {
        setTextColor(CarUi.TEXT_MUTED); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val position = text("0:00 / 0:00", 13f).apply { setTextColor(CarUi.TEXT_MUTED); gravity = Gravity.END }
    private val play = button("Play", primary = true) { togglePlayback() }
    private val seek = SeekBar(context).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(CarUi.ACCENT)
        thumbTintList = android.content.res.ColorStateList.valueOf(CarUi.ACCENT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(CarUi.OUTLINE)
    }
    val notifications = NotificationsPanel(context, object : NotificationsPanel.Listener {
        override fun onStatus(text: String) { if (selectedTab == Tab.MESSAGES) setStatus(text) }
        override fun onReadAloud(text: String) {
            if (!started || closed) return
            if (phoneCallActive) { setStatus("Message reading is paused during the phone call"); return }
            if (text.isBlank() || text.length > 2000) { setStatus("This message is too long to read aloud"); return }
            if (voiceActive) onVoiceSession(false)
            stopGuidance(resume = false)
            navigation.readAloud(text)
            setStatus("Reading the selected message")
        }
        override fun onVoiceReplyRequested(token: Long): Boolean = beginDictation(Purpose.MESSAGE_REPLY, token)
        override fun onVoiceReplyCancelled(token: Long) {
            if (voiceRoutes.owns(Purpose.MESSAGE_REPLY, token)) onVoiceSession(false)
        }
        override fun onComposerOpened() {
            // Stop pending Read speech and remove its old HUD before the reviewed To card appears.
            cancelMessageRead(resume = false); main.removeCallbacks(hideStatus); status.visibility = GONE
        }
        override fun onFlowChanged() { reportCancelableFlow() }
    }).apply { visibility = GONE }
    val calls = CallsPanel(context, object : CallsPanel.Listener {
        override fun onStatus(text: String) { } // Calls owns its contextual result/confirmation notice.
        override fun onDictateSearchRequested(token: Long): Boolean = beginDictation(Purpose.CONTACT_SEARCH, token)
        override fun onDictationCancelled(token: Long) {
            if (voiceRoutes.owns(Purpose.CONTACT_SEARCH, token)) onVoiceSession(false)
        }
        override fun onCallStarting() { quiesceForPhoneCall() }
        override fun onCallHandoffReady(ready: (Boolean) -> Unit) { callHandoffGate(ready) }
        override fun isCallHandoffCurrent(): Boolean = callHandoffStillValid()
        override fun onFlowChanged() { reportCancelableFlow() }
    }).apply { visibility = GONE }
    private val mapButton = CarUi.dockButton(context, "Map", CarUi.ICON_MAP).apply { setOnClickListener { selectTab(Tab.MAP) } }
    private val messagesButton = CarUi.dockButton(context, "Messages", CarUi.ICON_MESSAGES).apply { setOnClickListener { selectTab(Tab.MESSAGES) } }
    private val callsButton = CarUi.dockButton(context, "Calls", CarUi.ICON_CALLS).apply { setOnClickListener { selectTab(Tab.CALLS) } }
    private val mediaButton = CarUi.dockButton(context, "Music", CarUi.ICON_MUSIC).apply { setOnClickListener { selectTab(Tab.MUSIC) } }
    private val voiceButton = CarUi.floatingIconButton(context, "Voice input", CarUi.ICON_MIC).apply {
        setOnClickListener { requestVoiceSession() }
    }
    private val media = MediaEngine(context, MediaEngine.PcmSink { pcm, _ -> audioOwners.write(AudioOwnerGate.Source.MEDIA, pcm) },
        object : MediaEngine.Listener {
            override fun onState(snapshot: MediaEngine.Snapshot) { updateMedia(snapshot) }
        })

    private val guidance = MediaEngine(context, MediaEngine.PcmSink { pcm, _ ->
        audioOwners.write(AudioOwnerGate.Source.NAVIGATION, pcm)
    }, object : MediaEngine.Listener {
        override fun onState(snapshot: MediaEngine.Snapshot) {
            if (guidanceActive && snapshot.status in setOf(MediaEngine.Status.ENDED, MediaEngine.Status.ERROR)) {
                if (snapshot.error != null) setStatus("Voice guidance playback unavailable")
                stopGuidance(resume = true)
            }
        }
    })

    init {
        orientation = VERTICAL
        setBackgroundColor(CarUi.BACKGROUND)
        content.addView(navigation, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(mediaPanel, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(notifications, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(calls, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        status.setPadding(dp(14), dp(9), dp(14), dp(9))
        status.maxLines = 2; status.ellipsize = TextUtils.TruncateAt.END
        status.background = CarUi.rounded(context, CarUi.SURFACE_RAISED, 14)
        status.elevation = dp(6).toFloat()
        content.addView(status, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(16), dp(10), dp(16), 0)
        })
        // Shared by every tab; panels leave this corner clear for their scroll/actions/keyboard.
        content.addView(voiceButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin = dp(16); bottomMargin = dp(12)
        })
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val nowPlaying = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = CarUi.rounded(context, CarUi.SURFACE, 20)
        }
        val musicIcon = ImageView(context).apply {
            setImageDrawable(CarUi.icon(context, CarUi.ICON_MUSIC, 56, CarUi.ACCENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        nowPlaying.addView(musicIcon, LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(20) })
        val trackInfo = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        trackInfo.addView(musicKicker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        trackInfo.addView(trackTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        trackInfo.addView(trackDetail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        nowPlaying.addView(trackInfo, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        mediaPanel.addView(nowPlaying, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        mediaPanel.addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(6) })
        mediaPanel.addView(position, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val playbackControls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        playbackControls.addView(button("Previous") { selectMediaAction { media.previous() } }, LayoutParams(0, dp(52), 1f))
        playbackControls.addView(play, LayoutParams(0, dp(52), 1.25f).apply { marginStart = dp(8); marginEnd = dp(8) })
        playbackControls.addView(button("Next") { selectMediaAction { media.next() } }, LayoutParams(0, dp(52), 1f))
        mediaPanel.addView(playbackControls, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        val libraryControls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        // Keep the last action row beside the floating microphone, rather than underneath it.
        libraryControls.setPadding(dp(68), 0, 0, 0)
        libraryControls.addView(button(if (SharedLibraryIntegration.delegate != null) "Music library on phone" else "Choose files on phone") { openAudioPicker() }, LayoutParams(0, dp(48), 2f))
        libraryControls.addView(button("Clear queue") {
            try {
                MediaLibraryStore.clear(context)
                cancelMessageRead(resume = false); stopGuidance(resume = false); resetMediaQueue()
                knownLibrarySelection = emptyList(); refreshLibrary()
            } catch (_: Exception) {
                setStatus("Queue kept. Open the music library on the phone to check its saved data.")
            }
        }, LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        mediaPanel.addView(libraryControls, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        var compactMusic = false
        nowPlaying.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val compact = bottom - top < dp(118)
            if (compact != compactMusic) {
                compactMusic = compact
                musicKicker.visibility = if (compact) GONE else VISIBLE
                trackTitle.textSize = if (compact) 21f else 26f
                trackTitle.maxLines = if (compact) 1 else 2
                trackDetail.maxLines = if (compact) 1 else 2
                musicIcon.layoutParams = musicIcon.layoutParams.apply { width = dp(if (compact) 40 else 56); height = width }
            }
        }
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(CarUi.SURFACE)
        }
        for (tab in listOf(mapButton, messagesButton, callsButton, mediaButton)) {
            bar.addView(tab, LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { draggingSeek = true }
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && mediaSnapshot.durationMs > 0) {
                    position.text = "${duration(value * mediaSnapshot.durationMs / 1000)} / ${duration(mediaSnapshot.durationMs)}"
                }
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                draggingSeek = false
                cancelMessageRead(resume = false)
                if (mediaSnapshot.durationMs > 0) media.seek((bar?.progress ?: 0) * mediaSnapshot.durationMs / 1000)
            }
        })
        refreshLibrary()
        navigation.setFrameCadenceSession(if (projectionCapable) metricsSession else FrameCadenceCounters.disabledSession())
        showMap(true)
        lateinit var optionalVoice: OptionalDictationClient
        optionalVoice = OptionalDictationClient(context, { replyToken, transcript -> onMain {
            if (!closed && started && !phoneCallActive && voiceHandler === optionalVoice) {
                val route = voiceRoutes.consume(replyToken, visibleVoicePurpose())
                if (route != null) {
                    val accepted = when (route.purpose) {
                        Purpose.MESSAGE_REPLY -> notifications.acceptTranscript(route.targetToken, transcript)
                        Purpose.CONTACT_SEARCH -> calls.acceptTranscript(route.targetToken, transcript)
                        Purpose.MAP_PLACE_SEARCH -> navigation.acceptTranscript(route.targetToken, transcript)
                        Purpose.COMMAND -> { onVoiceSession(false); applyCommand(OfflineCommand.parse(transcript)); true }
                    }
                    if (!accepted) setStatus("The dictation target changed; no text was entered")
                }
            }
        } }, { message -> onMain {
            if (!closed && voiceActive && voiceHandler === optionalVoice && voiceRoutes.canReportStatus()) setStatus(message)
        } })
        setVoiceHandler(optionalVoice)
        uiReady = true
    }

    fun setAudioOutput(output: (ByteArray) -> Boolean) {
        audioOutput = output
    }
    fun setMediaFocusRequest(request: (Boolean) -> Unit) {
        mediaFocusRequest = request
    }

    /** This dashboard owns only the explicitly supplied optional reply handler. */
    fun setVoiceHandler(handler: VoiceSessionHandler?) {
        if (closed) { handler?.close(); return }
        if (voiceHandler === handler) return
        onVoiceSession(false)
        handlerGeneration++
        voiceHandler?.let {
            it.setReadinessListener { }; it.setEndedListener { }; it.close()
        }
        voiceHandler = handler
        val token = handlerGeneration
        handler?.setReadinessListener { ready -> onMain {
            if (!closed && voiceHandler === handler && handlerGeneration == token) {
                voiceInputReady = ready && started && voiceActive && !phoneCallActive
                microphoneControl(voiceInputReady)
            }
        } }
        handler?.setEndedListener { onMain {
            if (!closed && voiceHandler === handler && handlerGeneration == token) onVoiceSession(false)
        } }
        voiceInputReady = false; microphoneControl(false)
    }
    /** Content-free advisory state for a normal Android service dump. */
    fun diagnosticFlags(): String = "dashboard_started=$started dashboard_closed=$closed dashboard_attached=$isAttachedToWindow dashboard_visible=${visibility == VISIBLE} dashboard_tab=${selectedTab.name} voice_active=$voiceActive voice_ready=$voiceInputReady phone_call_active=$phoneCallActive voice_purpose=${voiceRoutes.current()?.purpose?.name ?: "NONE"} media_worker_alive=${media.isWorkerAlive()} guidance_worker_alive=${guidance.isWorkerAlive()}" +
        ((voiceHandler as? OptionalDictationClient)?.let { " ${it.diagnosticFlags()}" } ?: "") + " ${navigation.diagnosticFlags()}"
    fun requiresCarMicrophone(): Boolean = voiceHandler?.requiresCarMicrophone == true
    fun voicePreparationTimeoutMs(): Long = (voiceHandler?.preparationTimeoutMs ?: 15_000L).coerceIn(1_000L,30_000L)
    fun setMicrophoneControl(control: (Boolean) -> Unit) { microphoneControl(false); microphoneControl = control; control(false) }
    fun setVoiceSessionListener(listener: (Boolean) -> Unit) { voiceSessionListener = listener }


    fun onStart() {
        if (started || closed) return
        started = true
        if (projectionCapable) {
            sharedPlaybackRegistration = SharedLibraryIntegration.registerPlayback(sharedPlaybackController)
            SharedLibraryIntegration.publishPlayback(sharedPlaybackController, media.snapshot, sharedPlaybackQueue)
        }
        applyTabLifecycle()
        messageSummary = MessagingNotifications.observeSummary(context) { count -> onMain {
            if (!closed) {
                messagesButton.text = if (count > 0) "Messages ${if (count > 99) "99+" else count}" else "Messages"
                messagesButton.contentDescription = if (count > 0) "Messages, $count notifications" else "Messages"
            }
        } }
        refreshLibrary()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(context.applicationContext, libraryReceiver,
                IntentFilter(MediaLibraryStore.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    fun onStop() {
        if (!started) return
        started = false
        backKeys.reset()
        main.removeCallbacks(hideStatus); status.visibility = GONE
        navigation.stop()
        navigation.cancelSpokenOutput()
        notifications.stop()
        calls.stop()
        messageSummary?.close(); messageSummary = null
        stopGuidance(resume = false)
        onVoiceSession(false)
        media.pause()
        audioOwners.select(AudioOwnerGate.Source.NONE)
        sharedPlaybackRegistration?.close(); sharedPlaybackRegistration = null
        if (receiverRegistered) {
            context.applicationContext.unregisterReceiver(libraryReceiver)
            receiverRegistered = false
        }
    }

    override fun close() {
        if (closed) return
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        attempt { onStop() }
        closed = true
        // Signal both native workers even if another UI component failed to close. Never join here.
        attempt { media.close() }
        attempt { guidance.close() }
        attempt { navigation.onDestroy() }
        attempt { notifications.close() }
        attempt { calls.close() }
        handlerGeneration++
        attempt { voiceHandler?.let { it.setReadinessListener { }; it.setEndedListener { }; it.close() } }
        voiceHandler = null
        attempt { guidanceFile?.delete(); guidanceFile = null }
        main.removeCallbacksAndMessages(null)
        failure?.let { throw it }
    }

    /** Called by the service cleanup owner on IO, after UI close has signalled both engines. */
    fun awaitMediaStopped(timeoutMillis: Long): Boolean {
        if (!closed) return false
        val started = System.nanoTime()
        val mediaStopped = media.awaitStopped(timeoutMillis)
        val remaining = (timeoutMillis - (System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        val guidanceStopped = guidance.awaitStopped(remaining)
        return mediaStopped && guidanceStopped
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) && (closed || !started)) {
            backKeys.reset(); return true
        }
        if (backKeys.dispatch(event.keyCode, event.action, event.isCanceled)) return true
        // In particular, DEL/FORWARD_DEL go to the focused EditText's normal key handler.
        return super.dispatchKeyEvent(event)
    }

    /** Back closes a local interaction or returns to Map; it never tears down projection. */
    fun handleBackNavigation(): Boolean {
        if (closed || !started) return false
        backKeys.back(); return true
    }

    fun requestVoiceSession() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { requestVoiceSession() }; return }
        if (closed || !started || voiceActive) return
        if (phoneCallActive) { setStatus("Dictation is paused during the phone call"); return }
        // The global/wheel button is a command unless a reviewed message body already owns it.
        val purpose = if (selectedTab == Tab.MESSAGES && notifications.hasReplyTarget()) Purpose.MESSAGE_REPLY else Purpose.COMMAND
        val target = if (purpose == Purpose.MESSAGE_REPLY) notifications.prepareVoiceReply() else ++commandSequence
        if (target == null) return
        beginDictation(purpose, target)
    }

    private fun visibleVoicePurpose(): Purpose? {
        if (voiceRoutes.current()?.purpose == Purpose.COMMAND) return Purpose.COMMAND
        return when (selectedTab) {
            Tab.MESSAGES -> Purpose.MESSAGE_REPLY
            Tab.CALLS -> Purpose.CONTACT_SEARCH
            Tab.MAP -> Purpose.MAP_PLACE_SEARCH
            Tab.MUSIC -> null
        }
    }
    private fun cancelTarget(purpose: Purpose, token: Long) {
        when (purpose) {
            Purpose.MESSAGE_REPLY -> notifications.cancelVoiceReply(token)
            Purpose.CONTACT_SEARCH -> calls.cancelDictationSearch(token)
            Purpose.MAP_PLACE_SEARCH -> navigation.cancelDictationSearch(token)
            Purpose.COMMAND -> Unit
        }
    }
    private fun beginDictation(purpose: Purpose, targetToken: Long): Boolean {
        if (closed || !started || phoneCallActive || (purpose != Purpose.COMMAND && visibleVoicePurpose() != purpose)) {
            cancelTarget(purpose, targetToken); return false
        }
        if (!projectionCapable || !ServiceState.projectionActive) {
            cancelTarget(purpose, targetToken); setStatus("Connect to the car to use its microphone"); return false
        }
        if (voiceActive) {
            if (voiceRoutes.owns(purpose, targetToken)) return true
            onVoiceSession(false)
            cancelTarget(purpose, targetToken)
            setStatus("Previous dictation is stopping. Tap Dictate again when it has ended.")
            return false
        }
        if (purpose == Purpose.MESSAGE_REPLY && !notifications.hasReplyTarget()) {
            cancelTarget(purpose, targetToken); microphoneControl(false); voiceSessionListener(false)
            setStatus("Open Messages and choose Reply first"); return false
        }
        val handler = voiceHandler
        if (handler == null) {
            cancelTarget(purpose, targetToken); microphoneControl(false); voiceSessionListener(false)
            setStatus("Set up the optional offline dictation add-on on your phone"); return false
        }
        val route = voiceRoutes.begin(purpose, targetToken) ?: run { cancelTarget(purpose, targetToken); return false }
        calls.cancelPendingHandoff() // A new voice intent supersedes a still-waiting real-call handoff.
        mediaWasPlaying = media.snapshot.status in setOf(MediaEngine.Status.PLAYING, MediaEngine.Status.LOADING) || resumeMediaAfterGuidance
        voiceActive = true; microphoneUnavailableReported = false
        stopGuidance(resume = false); media.pause(); audioOwners.select(AudioOwnerGate.Source.NONE)
        navigation.setSpeechSuppressed(true); voiceSessionListener(true)
        reportCancelableFlow()
        setStatus(when (purpose) {
            Purpose.MESSAGE_REPLY -> "Preparing voice input for this message"
            Purpose.CONTACT_SEARCH -> "Preparing contact name dictation"
            Purpose.MAP_PLACE_SEARCH -> "Preparing place name dictation"
            Purpose.COMMAND -> "Say Call a name, Directions to a place, or Message a name"
        })
        val accepted = try { handler.request(route.token) } catch (_: Exception) { false }
        if (!accepted) {
            onVoiceSession(false)
            setStatus("Dictation is not ready; finish optional add-on setup or try again after it stops")
        }
        return accepted && voiceActive
    }

    fun onMediaKey(keyCode: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onMediaKey(keyCode) }; return }
        if (closed) return
        if (phoneCallActive) { setStatus("Music controls are paused during the phone call"); return }
        when (keyCode) {
            85, 79 -> togglePlayback()
            126 -> if (media.snapshot.status !in setOf(MediaEngine.Status.PLAYING, MediaEngine.Status.LOADING)) togglePlayback()
            127 -> if (media.snapshot.status in setOf(MediaEngine.Status.PLAYING, MediaEngine.Status.LOADING)) togglePlayback()
            87 -> selectMediaAction { media.next() }
            88 -> selectMediaAction { media.previous() }
            86 -> selectMediaAction { media.stop() }
        }
    }

    fun onVoiceSession(start: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onVoiceSession(start) }; return }
        if (closed) return
        if (start) { requestVoiceSession(); return }
        val wasActive = voiceActive
        val ownedRoute = voiceRoutes.cancel()
        voiceActive = false; voiceInputReady = false; voiceStatusGeneration++
        if (ownedRoute != null) cancelTarget(ownedRoute.purpose, ownedRoute.targetToken)
        microphoneControl(false); voiceSessionListener(false)
        if (wasActive) voiceHandler?.stop()
        if (status.visibility == VISIBLE && !phoneCallActive) {
            main.removeCallbacks(hideStatus); main.postDelayed(hideStatus, 4_000)
        }
        navigation.setSpeechSuppressed(phoneCallActive)
        if (started && !phoneCallActive && mediaWasPlaying && media.snapshot.status == MediaEngine.Status.PAUSED) {
            audioOwners.select(AudioOwnerGate.Source.MEDIA); media.resume()
        }
        mediaWasPlaying = false
        reportCancelableFlow()
    }

    fun onCarMicrophonePcm(pcm: ByteArray, sampleRate: Int) {
        val handler = voiceHandler ?: return
        if (closed || phoneCallActive || !voiceActive || !voiceInputReady || !handler.requiresCarMicrophone) return
        val routeToken = voiceRoutes.current()?.token ?: return
        if (!handler.acceptPcm(pcm, sampleRate) && !microphoneUnavailableReported) {
            microphoneUnavailableReported = true
            onMain { if (voiceActive && !closed && voiceRoutes.current()?.token == routeToken) {
                onVoiceSession(false); setStatus("Dictation input stopped; no message or call was sent")
            } }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    fun setStatus(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { setStatus(message) }; return }
        if (closed || message.isBlank()) return
        lastStatus = message
        status.text = message
        status.contentDescription = message
        main.removeCallbacks(hideStatus)
        if (selectedTab == Tab.MESSAGES && notifications.showReplyStatus(message)) {
            status.visibility = GONE
            return
        }
        status.visibility = VISIBLE
        if (!voiceActive && !phoneCallActive) main.postDelayed(hideStatus, 4_500)
    }

    /** CALL confirms only an already-visible exact-number ticket; otherwise it just opens Calls. */
    fun confirmCallOrShowCalls() { onMain {
        if (closed || !started || phoneCallActive) return@onMain
        if (selectedTab != Tab.CALLS || !calls.confirmPending()) selectTab(Tab.CALLS)
    } }
    fun showCalls() { onMain { if (!closed) selectTab(Tab.CALLS) } }

    /** END_CALL cancels our pending interaction only. It never controls Android's live calls. */
    fun cancelPendingCommand() { onMain {
        if (closed || !started || phoneCallActive) return@onMain
        onVoiceSession(false)
        navigation.cancelCommandFlow(); calls.cancelCommandFlow(); notifications.cancelPendingFlow()
        cancelMessageRead(resume = false); reportCancelableFlow()
        setStatus("Pending interaction cancelled")
    } }
    fun setCancelableFlowListener(listener: (Boolean) -> Unit) { cancelableFlowListener = listener; reportCancelableFlow() }
    fun setCallHandoffGate(gate: ((Boolean) -> Unit) -> Unit) { callHandoffGate = gate }
    fun setCallHandoffStillValid(check: () -> Boolean) { callHandoffStillValid = check }
    fun hasCancelableFlow(): Boolean = uiReady && projectionCapable && started && !closed && !phoneCallActive &&
        (voiceActive || navigation.hasCancelableFlow() || calls.hasCancelableFlow() || notifications.hasPendingFlow())
    private fun reportCancelableFlow() { if (uiReady) cancelableFlowListener(hasCancelableFlow()) }
    private fun applyCommand(command: OfflineCommand) {
        if (closed || !started || phoneCallActive || !projectionCapable || !ServiceState.projectionActive) return
        when (command.kind) {
            OfflineCommand.Kind.CALL -> { selectTab(Tab.CALLS); calls.beginCommandSearch(command.query) }
            OfflineCommand.Kind.DIRECTIONS -> { selectTab(Tab.MAP); navigation.beginCommandSearch(command.query) }
            OfflineCommand.Kind.MESSAGE, OfflineCommand.Kind.REPLY -> {
                selectTab(Tab.MESSAGES); notifications.beginCommandMessage(command.query, command.kind == OfflineCommand.Kind.REPLY)
            }
            OfflineCommand.Kind.CANCEL -> cancelPendingCommand()
            OfflineCommand.Kind.UNKNOWN -> setStatus("Try Call a name, Directions to a place, or Message a name")
        }
        reportCancelableFlow()
    }

    /** Real Android audio-mode observation owns this flag; leaving a call never resumes old audio. */
    fun setPhoneCallActive(active: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { setPhoneCallActive(active) }; return }
        if (closed || phoneCallActive == active) return
        phoneCallActive = active
        calls.setPhoneCallActive(active)
        if (active) { quiesceForPhoneCall(); setStatus("Phone call audio is handled by Android") }
        else if (lastStatus == "Phone call audio is handled by Android") status.visibility = GONE
        navigation.setSpeechSuppressed(active || voiceActive)
    }
    private fun quiesceForPhoneCall() {
        // Clear resume intent BEFORE cancellation callbacks; no old media/read/guidance resumes.
        mediaWasPlaying = false; resumeMediaAfterGuidance = false
        onVoiceSession(false)
        navigation.cancelSpokenOutput()
        stopGuidance(resume = false)
        media.pause(); audioOwners.select(AudioOwnerGate.Source.NONE)
        microphoneControl(false)
        cancelableFlowListener(false)
    }

    /** Retained convenience for existing map/media preview controls. */
    fun showMap(show: Boolean) { selectTab(if (show) Tab.MAP else Tab.MUSIC) }

    private fun selectTab(tab: Tab) {
        if (selectedTab != tab) cancelMessageRead(resume = true)
        if (selectedTab != tab && voiceActive) onVoiceSession(false)
        selectedTab = tab
        if (!voiceActive && !phoneCallActive) { main.removeCallbacks(hideStatus); status.visibility = GONE }
        navigation.visibility = if (tab == Tab.MAP) VISIBLE else GONE
        navigation.setRenderingSuspended(tab != Tab.MAP)
        notifications.visibility = if (tab == Tab.MESSAGES) VISIBLE else GONE
        calls.visibility = if (tab == Tab.CALLS) VISIBLE else GONE
        mediaPanel.visibility = if (tab == Tab.MUSIC) { VISIBLE } else { GONE }
        CarUi.applyTab(mapButton, tab == Tab.MAP)
        CarUi.applyTab(messagesButton, tab == Tab.MESSAGES)
        CarUi.applyTab(callsButton, tab == Tab.CALLS)
        CarUi.applyTab(mediaButton, tab == Tab.MUSIC)
        applyTabLifecycle()
        when (tab) {
            Tab.MAP, Tab.MESSAGES, Tab.CALLS -> Unit
            Tab.MUSIC -> { refreshLibrary(); updateMedia(media.snapshot) }
        }
    }
    private fun applyTabLifecycle() {
        if (!started) return
        // Tracking and spoken turns survive a temporary tab change during an active route.
        if (selectedTab == Tab.MAP || navigation.route != null) navigation.start() else navigation.stop()
        if (selectedTab == Tab.MESSAGES) notifications.start(projectionCapable && ServiceState.projectionActive) else notifications.stop()
        if (selectedTab == Tab.CALLS) calls.start(projectionCapable && ServiceState.projectionActive) else calls.stop()
        reportCancelableFlow()
    }

    fun playQueue(uris: List<android.net.Uri>, startIndex: Int = 0) {
        if (phoneCallActive) { setStatus("Music is paused during the phone call"); return }
        cancelMessageRead(resume = false)
        if (voiceActive) { mediaWasPlaying = false; onVoiceSession(false) }
        stopGuidance(resume = false)
        queueLoaded = true
        sharedPlaybackQueue = uris.toList()
        knownLibrarySelection = MediaLibraryStore.read(context)
        audioOwners.select(AudioOwnerGate.Source.MEDIA)
        media.playQueue(uris, startIndex)
    }

    fun getMediaSnapshot(): MediaEngine.Snapshot = media.snapshot

    private fun togglePlayback() {
        if (phoneCallActive) { setStatus("Music is paused during the phone call"); return }
        cancelMessageRead(resume = false)
        if (voiceActive) { mediaWasPlaying = false; onVoiceSession(false) }
        stopGuidance(resume = false)
        when (media.snapshot.status) {
            MediaEngine.Status.PLAYING, MediaEngine.Status.LOADING -> { media.pause(); audioOwners.select(AudioOwnerGate.Source.NONE) }
            MediaEngine.Status.PAUSED -> { audioOwners.select(AudioOwnerGate.Source.MEDIA); media.resume() }
            else -> {
                val queue = MediaLibraryStore.read(context)
                if (queue.isEmpty()) openAudioPicker()
                else playQueue(queue)
            }
        }
    }

    private fun selectMediaAction(action: () -> Unit) {
        if (phoneCallActive) { setStatus("Music is paused during the phone call"); return }
        cancelMessageRead(resume = false)
        if (voiceActive) { mediaWasPlaying = false; onVoiceSession(false) }
        stopGuidance(resume = false)
        audioOwners.select(AudioOwnerGate.Source.MEDIA)
        action()
        if (media.snapshot.status in setOf(MediaEngine.Status.IDLE, MediaEngine.Status.ENDED, MediaEngine.Status.ERROR)) audioOwners.select(AudioOwnerGate.Source.NONE)
    }

    private fun cancelMessageRead(resume: Boolean) {
        if (!navigation.isReadingAnnouncement) return
        // Stop current PCM first so the synthesizer's cancellation callback cannot
        // resume old music immediately before a manual Play/Pause/Next action.
        stopGuidance(resume)
        navigation.cancelReadAloud()
    }

    private fun playGuidance(file: File) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { playGuidance(file) }; return }
        if (closed || !started || voiceActive || phoneCallActive) { file.delete(); return }
        val resume = resumeMediaAfterGuidance || media.snapshot.status in setOf(MediaEngine.Status.PLAYING, MediaEngine.Status.LOADING)
        stopGuidance(resume = false)
        resumeMediaAfterGuidance = resume
        media.pause()
        guidanceActive = true
        guidanceFile = file
        audioOwners.select(AudioOwnerGate.Source.NAVIGATION)
        guidance.play(Uri.fromFile(file))
    }

    private fun stopGuidance(resume: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stopGuidance(resume) }; return }
        if (!guidanceActive) return
        val shouldResume = resume && resumeMediaAfterGuidance && started && !voiceActive && !phoneCallActive && !closed && media.snapshot.status == MediaEngine.Status.PAUSED
        guidanceActive = false
        resumeMediaAfterGuidance = false
        guidance.stop()
        guidanceFile?.delete(); guidanceFile = null
        if (audioOwners.owner() == AudioOwnerGate.Source.NAVIGATION) audioOwners.select(AudioOwnerGate.Source.NONE)
        if (shouldResume) { audioOwners.select(AudioOwnerGate.Source.MEDIA); media.resume() }
    }

    private fun updateMedia(snapshot: MediaEngine.Snapshot) {
        if (closed) return
        mediaSnapshot = snapshot
        if (projectionCapable && started) {
            SharedLibraryIntegration.publishPlayback(sharedPlaybackController, snapshot, sharedPlaybackQueue)
        }
        trackTitle.text = snapshot.title.ifBlank {
            if (MediaLibraryStore.read(context).isEmpty()) "Your music, on the road" else "Audio files selected"
        }
        trackDetail.text = snapshot.error ?: listOf(snapshot.artist,
            if (snapshot.queueIndex >= 0) "Track ${snapshot.queueIndex + 1} of ${snapshot.queueSize}" else "Local audio queue",
            snapshot.status.name.lowercase().replaceFirstChar { it.uppercaseChar() })
            .filter { it.isNotBlank() }.joinToString(" · ")
        play.text = if (snapshot.status in setOf(MediaEngine.Status.PLAYING, MediaEngine.Status.LOADING)) "Pause" else "Play"
        if (!draggingSeek) {
            seek.progress = if (snapshot.durationMs > 0) ((snapshot.positionMs * 1000) / snapshot.durationMs).toInt() else 0
            position.text = "${duration(snapshot.positionMs)} / ${duration(snapshot.durationMs)}"
        }
        if (audioOwners.owner() == AudioOwnerGate.Source.MEDIA && snapshot.status in setOf(MediaEngine.Status.ENDED, MediaEngine.Status.ERROR, MediaEngine.Status.IDLE)) audioOwners.select(AudioOwnerGate.Source.NONE)
        if (snapshot.error != null) setStatus(snapshot.error)
    }

    private fun refreshLibrary() {
        val queue = MediaLibraryStore.read(context)
        if (knownLibrarySelection != null && knownLibrarySelection != queue) resetMediaQueue()
        knownLibrarySelection = queue
        if (media.snapshot.status == MediaEngine.Status.IDLE || !queueLoaded) {
            trackTitle.text = if (queue.isEmpty()) "Your music, on the road" else "${queue.size} audio files ready"
            trackDetail.text = if (queue.isEmpty()) "Choose audio files from your phone" else "Press Play to listen"
        }
    }

    private fun resetMediaQueue() {
        sharedPlaybackQueue = emptyList()
        media.clearQueue()
        mediaWasPlaying = false; resumeMediaAfterGuidance = false; queueLoaded = false
        if (audioOwners.owner() == AudioOwnerGate.Source.MEDIA) audioOwners.select(AudioOwnerGate.Source.NONE)
        updateMedia(media.snapshot)
    }

    private fun openAudioPicker() {
        cancelMessageRead(resume = false)
        setStatus(if (SharedLibraryIntegration.delegate != null) "Choose a playlist or folder on the phone" else "Choose audio files on the phone, then press Play")
        try {
            PhoneActivityLauncher.open(context, Intent(context, AudioLibraryActivity::class.java))
        } catch (_: Exception) { setStatus("Open the phone setup screen to choose audio files") }
    }

    private val libraryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaLibraryStore.ACTION_CHANGED) {
                resetMediaQueue(); knownLibrarySelection = MediaLibraryStore.read(this@NativeDashboardView.context)
                refreshLibrary(); setStatus("Audio queue ready · press Play")
            }
        }
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = CarUi.button(context, label, primary).apply {
        setOnClickListener { action() }
    }

    private fun text(label: String, size: Float) = CarUi.text(context, label, size, CarUi.TEXT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun duration(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
}

/** Existing preview callers use this short name; the runtime class remains explicit about native UI. */
typealias DashboardView = NativeDashboardView
