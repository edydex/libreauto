package org.openandroidauto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import kotlinx.coroutines.*
import org.openandroidauto.channel.*
import org.openandroidauto.identity.InstalledAaIdentitySettings
import org.openandroidauto.identity.InstalledAaSessionProvider
import org.openandroidauto.identity.IdentityStatus
import org.openandroidauto.identity.ProjectionStopSignal
import org.openandroidauto.integration.ProjectionAvailability
import org.openandroidauto.integration.ProjectionIntegration
import org.openandroidauto.integration.RejectedForegroundStart
import org.openandroidauto.input.TouchInjector
import org.openandroidauto.protocol.*
import org.openandroidauto.tls.AaTlsServer
import org.openandroidauto.tls.InBandTls
import org.openandroidauto.transport.*
import org.openandroidauto.ui.ProjectionDashboard
import org.openandroidauto.notifications.MessagingNotifications
import org.openandroidauto.media.CallAudioOutputGate
import org.openandroidauto.media.PhoneCallAudioMonitor
import org.openandroidauto.bluetooth.CarRfcommTransport
import org.openandroidauto.wireless.AndroidCarNetworkJoiner
import org.openandroidauto.wireless.WirelessConnectionReport
import java.io.FileDescriptor
import java.io.PrintWriter
import org.openandroidauto.render.FrameCadenceCounters
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine

/** Phone-side projection session. All write/TLS framing runs through one bounded writer. */
open class ProjectionService : Service(), ProtocolCallback, VideoChannelCallback, InputChannelCallback, SensorChannelCallback {
    companion object {
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code" // legacy UI compatibility; no capture used
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_TRANSPORT = "transport" // auto, tcp-server, tcp-client, wireless-car
        const val EXTRA_PORT = "port"
        const val EXTRA_HOST = "host"
        const val EXTRA_BLUETOOTH_ADDRESS = "bluetooth_address"
        private const val NOTIFICATION_ID = 21
        private const val NOTIFICATION_CHANNEL = "projection-session"
    }
    private data class Outbound(val channel: UByte, val payload: ByteArray, val control: Boolean)
    // This owner survives Service.onDestroy until its session has actually joined.
    private val ownerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionRunner = ProjectionSessionRunner(ownerScope)
    private val lifetime = ProjectionSessionLifetime()
    private val outbound = ProjectionOutbound<Outbound>()
    private val cleanupProven = AtomicBoolean()
    private val identityStop = ProjectionStopSignal()
    private val channelsInstalled = AtomicBoolean()
    private val uiCloseRequested = AtomicBoolean()
    private val uiClosed = CompletableDeferred<Boolean>()
    private val serviceDestroyed = CompletableDeferred<Unit>()
    private var ownerAcquired = false
    private var hostSession: ProjectionIntegration.Session? = null
    private val shutdownLock = Any()
    @Volatile private var transportCloseJob: Job? = null
    @Volatile private var sessionChildren: CoroutineScope? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var transport: Transport? = null
    private var tlsEngine: SSLEngine? = null
    private var tls: InBandTls? = null
    @Volatile private var engine: ProtocolEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dashboard: ProjectionDashboard? = null
    @Volatile private var closingDashboard: ProjectionDashboard? = null
    private var sessionJob: Job? = null
    @Volatile private var video: VideoChannel? = null
    @Volatile private var input: InputChannel? = null
    private var frameMetrics = FrameCadenceCounters.disabledSession()
    @Volatile private var commandCancelDesired = false
    @Volatile private var handoffWaiting = false
    @Volatile private var handoffReleaseRequested = false
    private val keyBindingLock = Any()
    private var callHandoff: ((Boolean) -> Unit)? = null
    private var callHandoffTimeout: Runnable? = null
    private var sensors: SensorChannel? = null
    @Volatile private var audioIn: AudioInputChannel? = null
    @Volatile private var audioOut: AudioOutputChannel? = null
    private var phoneCallAudioMonitor: PhoneCallAudioMonitor? = null
    private val callAudio = CallAudioOutputGate({ pcm -> audioOut?.enqueuePcm(pcm) ?: false }, {
        audioOut?.stopPlayback(notifyFocus = true)
    })
    private var audioId = -1
    private var videoId = -1
    private var inputId = -1
    private var sensorId = -1
    private var microphoneId = -1
    private val microphoneTrace = MicrophoneProtocolTrace({
        applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }) { event ->
        android.util.Log.i("OpenAutoMic", "stage=${event.stage.name} ch=${event.channel} type=${event.type} bytes=${event.bytes} configured=${event.configured} requested=${event.requested} status=${event.status} session=${event.session} configIndex=${event.configurationIndex} inputMode=${event.inputMode?.name ?: "UNSPECIFIED"} channelOpened=${event.channelOpened} openReady=${event.openReady}")
    }
    private var touch = TouchInjector()
    private var sendCount = 0L
    private var touchEventCount = 0
    private var pingJob: Job? = null
    private val microphoneIngress = CarPcmIngress({ task ->
        main.post(task).also { accepted -> if (!accepted) { requestShutdown(); stopSelf() } }
    }, { pcm, rate ->
        if (lifetime.accepting) dashboard?.onCarMicrophonePcm(pcm, rate)
    }, ::onMicrophoneIngressFailure)
    private val microphoneGate = VoiceSessionGate { open ->
        microphoneIngress.setOpen(open)
        audioIn?.requestOpen(open)
    }
    private var microphoneTimeout: Runnable? = null
    private var pendingVoiceRequest = false
    private val foregroundStartup = ProjectionForegroundStartup()
    private val startRejection = RejectedForegroundStart()
    @Volatile private var wirelessMode = false
    private var wirelessReport: WirelessConnectionReport? = null
    @Volatile private var readinessDeadline: ProjectionReadinessDeadline? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        // A USB launch, stale PendingIntent, or explicit in-app start must all pass the
        // same host mode gate before acquiring ANY session resource. A rejected foreground
        // start still needs the framework handshake before stopSelf(), or Android crashes it.
        if (!ProjectionIntegration.isProjectionAllowed()) {
            ServiceState.addEvent(ProjectionAvailability.unavailableReason() ?: ProjectionAvailability.MODE_MESSAGE)
            rejectStart()
            return
        }
        ownerAcquired = lifetime.acquire()
        if (!ownerAcquired) {
            ServiceState.addEvent("The previous connection is still shutting down. Wait before reconnecting.")
            rejectStart()
            return
        }
        hostSession = ProjectionIntegration.beginSession()
        if (hostSession == null) {
            rejectStart()
            return
        }
        if (!foregroundStartup.start(::promoteProjectionForeground,
                { it is SecurityException || it is ForegroundServiceStartNotAllowedException }, {
                    ServiceState.projectionActive = false
                    ServiceState.connectionState.value = ServiceState.ConnectionState.ERROR
                    ServiceState.addEvent("Projection could not start. Reopen LibreAuto, review app permissions and try again")
                    rejectStart()
                })) return
        PreviewActivity.closeBeforeProjection()
        ServiceState.projectionActive = true
        phoneCallAudioMonitor = PhoneCallAudioMonitor(this) { mode ->
            if (lifetime.accepting && callAudio.setMode(mode)) {
                if (callAudio.blocked) {
                    pendingVoiceRequest = false
                    microphoneIngress.endAttempt()
                }
                if(callAudio.blocked) updateCommandCancelBinding(false)
                dashboard?.setPhoneCallActive(callAudio.blocked)
            }
        }
        touch.callback = object : TouchInjector.Callback {
            override fun injectMotionEvent(event: MotionEvent) {
                val copy = MotionEvent.obtain(event)
                main.post { try {
                    if (!lifetime.accepting) return@post
                    val consumed = dashboard?.dispatchTouchEvent(copy) == true
                    if (copy.actionMasked == MotionEvent.ACTION_UP && touchEventCount++ < 6) {
                        val result = "Car touch (${copy.x.toInt()},${copy.y.toInt()}) consumed=$consumed"
                        android.util.Log.i("OpenAutoInput", result)
                        ServiceState.addEvent(result)
                    }
                } finally { copy.recycle() } }
            }
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenAuto:Session")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startRejection.shouldReject(ProjectionIntegration.isProjectionAllowed())) {
            if (ownerAcquired) requestShutdown()
            rejectStart(startId)
            return START_NOT_STICKY
        }
        if (!ownerAcquired || hostSession == null || !foregroundStartup.runIfReady {
                if (lifetime.begin()) {
                    frameMetrics = FrameCadenceCounters.beginSession()
                    launchOwnedSession { factory -> runSession(intent ?: Intent(), factory) }
                }
            }) rejectStart(startId)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (ownerAcquired) {
            requestShutdown()
            if (sessionJob == null) {
                launchCleanupOnlySession()
            }
        } else ownerScope.cancel()
        // Never join codecs, sockets, coroutines or UI acknowledgements on Android's main thread.
        serviceDestroyed.complete(Unit)
        super.onDestroy()
    }

    private fun postSession(action: () -> Unit) {
        main.post { if (lifetime.accepting) action() }
    }
    private fun launchSession(action: suspend CoroutineScope.() -> Unit) {
        if (lifetime.accepting) sessionChildren?.launch(block = action)
    }
    private fun requestUiClose() {
        microphoneIngress.close()
        if (!uiCloseRequested.compareAndSet(false, true)) return
        val action = Runnable {
            var closed = true
            fun attempt(block: () -> Unit) { try { block() } catch (_: Exception) { closed = false } }
            attempt { phoneCallAudioMonitor?.close(); phoneCallAudioMonitor = null }
            attempt { callAudio.close() }
            attempt { audioIn?.stop() }
            attempt { microphoneGate.request(false) }
            microphoneTimeout?.let { main.removeCallbacks(it) }; microphoneTimeout = null
            finishCallHandoff(false)
            attempt {
                val closing = dashboard
                if (closing != null) closingDashboard = closing
                try { closing?.dismiss() } finally { dashboard = null }
            }
            uiClosed.complete(closed)
        }
        // Even onDestroy only posts this work; it never waits for Presentation, media or
        // voice input cleanup. Completion still requires the acknowledgement from this action.
        if (!main.post(action)) uiClosed.complete(false)
    }
    private fun requestShutdown(cancelSession: Boolean = true) {
        // Synchronous off-Main invalidation/wipe, before any queued UI acknowledgement.
        microphoneIngress.close()
        // Publish before ANY Job cancellation, including preparation and onDestroy.
        identityStop.stop()
        synchronized(shutdownLock) {
            MessagingNotifications.setProjectionActive(this, false)
            if (lifetime.requestStop()) {
                outbound.close() // Releases producers blocked by active-session backpressure.
                // Publish the close job before starting it. A concurrent finalizer can join it
                // even if the initiating thread is interrupted before calling start().
                transportCloseJob = ownerScope.launch(start = CoroutineStart.LAZY) {
                    // Signal every producer before a socket/provider close could block.
                    video?.stop(); audioOut?.stop()
                    closeTransport()
                }
            }
        }
        // Deadline invokes its timeout callback under its own lock. Never nest that lock
        // beneath shutdownLock: timeout also requests shutdown.
        readinessDeadline?.close()
        requestUiClose()
        transportCloseJob?.start()
        if (cancelSession) sessionJob?.cancel()
    }
    /** Default path preserves existing identity behavior. A debug subclass can instead
     * own a callback-local factory around BOTH action and finalization. */
    protected open suspend fun aroundSessionBody(
        body: suspend (() -> SSLEngine) -> Unit,
        cleanupVerified: () -> Boolean
    ) {
        if (InstalledAaIdentitySettings.isEnabled(this)) {
            InstalledAaSessionProvider.withIdentity(this, identityStop, ::requestScopedSessionStop,
                cleanupVerified, { status ->
                    ServiceState.addEvent(status.message)
                    if (status !in setOf(IdentityStatus.PREPARING, IdentityStatus.READY, IdentityStatus.CANCELLED))
                        ServiceState.connectionState.value = ServiceState.ConnectionState.ERROR
                }, body)
        } else if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Existing private development experiments keep their original default path.
            body(::createSessionTlsEngine)
        } else {
            ServiceState.addEvent(IdentityStatus.SETUP_REQUIRED.message)
            ServiceState.connectionState.value = ServiceState.ConnectionState.ERROR
            requestScopedSessionStop()
        }
    }
    protected open fun createSessionTlsEngine(): SSLEngine =
        AaTlsServer(AaTlsServer.createKeyStore(this)).createEngine()
    protected fun requestScopedSessionStop() { requestShutdown(); stopSelf() }
    private fun launchOwnedSession(action: suspend (() -> SSLEngine) -> Unit) {
        sessionJob = sessionRunner.launchScoped(action, ::finishSessionResources,
            ::completeAfterJoinedSession) { body -> aroundSessionBody(body, cleanupProven::get) }
    }
    private fun launchCleanupOnlySession() {
        // onDestroy without a started session MUST NOT derive/acquire an identity.
        sessionJob = sessionRunner.launch({}, ::finishSessionResources, ::completeAfterJoinedSession)
    }
    private suspend fun closeTransport(): Boolean = try {
        transport?.disconnect()
        true
    } catch (_: Exception) { false }

    /** Called only after structured reader/writer/control children have left. */
    private suspend fun finishSessionResources() = withContext(NonCancellable + Dispatchers.IO) {
        requestShutdown(cancelSession = false)
        transportCloseJob?.join()
        // Repeat after the reader joined, covering a channel published during cancellation.
        // Stop both before awaiting either: one frozen provider must not leave the other running.
        video?.stop(); audioOut?.stop()
        val transportClosed = closeTransport() // Catches resources published after the early close.
        val videoStopped = video?.awaitStopped() ?: true
        val pcmStopped = audioOut?.awaitStopped() ?: true
        val uiAcknowledged = uiClosed.await()
        // Dismissing a Presentation only requests decoder cancellation. A SAF provider or
        // codec may ignore interruption; retain the host owner until both actual threads exit.
        // Each join is bounded and off-main, so a stuck native call cannot freeze the phone UI.
        var mediaStopped = closingDashboard?.awaitMediaStopped(1_000) ?: true
        if (!mediaStopped) ServiceState.addEvent("Waiting for music and guidance decoders to finish stopping. A new session remains blocked.")
        while (!mediaStopped) {
            delay(100)
            mediaStopped = closingDashboard?.awaitMediaStopped(1_000) ?: true
        }
        closingDashboard = null
        if(videoStopped && uiAcknowledged) FrameCadenceCounters.endSession(frameMetrics)
        var tlsClosed = true
        tlsEngine?.let { current ->
            try { current.closeOutbound() } catch (_: Exception) { tlsClosed = false }
            // A peer may close the socket without TLS close-notify. closeInbound still closes
            // the engine, and its expected SSLException does not mean a worker is using it.
            try { current.closeInbound() } catch (_: javax.net.ssl.SSLException) { }
            catch (_: Exception) { tlsClosed = false }
        }
        if (tlsClosed) { tlsEngine = null; tls = null }
        engine = null; sessionChildren = null
        readinessDeadline?.close(); readinessDeadline = null
        wirelessReport?.record(WirelessConnectionReport.Projection.ENDED)
        cleanupProven.set(transportClosed && videoStopped && pcmStopped && mediaStopped && uiAcknowledged && tlsClosed)
    }
    private suspend fun completeAfterJoinedSession() {
        stopSelf()
        val wakeReleased = try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            true
        } catch (_: Exception) { false }
        if (cleanupProven.get() && wakeReleased) {
            // Keep ownership until Android has destroyed this service too. A reconnect must
            // create a new owner, rather than deliver a start to the stopped old instance.
            serviceDestroyed.await()
            withContext(Dispatchers.Main.immediate) {
                if (lifetime.completeAfterJoin(true)) {
                    ServiceState.connectionState.value = ServiceState.ConnectionState.DISCONNECTED
                    ServiceState.projectionActive = false
                    hostSession?.finish(); hostSession = null
                    ownerScope.cancel()
                }
            }
        } else {
            ServiceState.addEvent("Connection cleanup is unfinished. A new connection remains blocked.")
        }
    }

    private fun promoteProjectionForeground() {
        if (!ProjectionAvailability.isSupported()) throw IllegalStateException(ProjectionAvailability.REQUIREMENT_MESSAGE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL, "Car connection", NotificationManager.IMPORTANCE_LOW))
        val locationEnabled = try { getSystemService(LocationManager::class.java)?.isLocationEnabled }
            catch (_: SecurityException) { null }
        val locationMode = ProjectionForegroundPolicy.start(
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            locationEnabled
        ) { includeLocation ->
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (includeLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(NOTIFICATION_ID, notification("Waiting for car connection"), types)
        }
        ServiceState.addEvent(when (locationMode) {
            ProjectionForegroundPolicy.LocationMode.ENABLED -> "Location foreground mode enabled for this connection"
            ProjectionForegroundPolicy.LocationMode.PERMISSION_MISSING -> "Projection continues without location access; allow location and restart the connection to use it"
            ProjectionForegroundPolicy.LocationMode.DISABLED -> "Projection continues with phone location off; enable it and restart the connection to use it"
            ProjectionForegroundPolicy.LocationMode.UNAVAILABLE -> "Projection continues; phone location service is unavailable"
            ProjectionForegroundPolicy.LocationMode.START_REJECTED -> "Projection continues; Android did not allow location mode for this connection"
        })
    }

    /** Honor startForegroundService without claiming a projection session. Some Android versions
     * crash even an immediately stopped FGS if it never acknowledged startForeground(). The
     * transient connection notification is removed synchronously; no location type is requested.
     */
    private fun rejectStart(startId: Int? = null) {
        val completed = startRejection.stop(this, NOTIFICATION_ID,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, "Car display is off",
            android.R.drawable.stat_sys_data_bluetooth, NOTIFICATION_CHANNEL, startId)
        if (!completed) ServiceState.addEvent("The rejected car-display start could not complete its foreground acknowledgement. No projection session was started.")
    }
    private suspend fun runSession(intent: Intent, tlsFactory: () -> SSLEngine) {
        if (!ProjectionIntegration.isProjectionAllowed()) {
            requestShutdown(); stopSelf()
            return
        }
        val mode = intent.getStringExtra(EXTRA_TRANSPORT) ?: "auto"
        wirelessMode = mode == "wireless-car"
        if (wirelessMode) wirelessReport = WirelessConnectionReport.begin(this)
        try {
            coroutineScope {
                sessionChildren = this
                try {
                    ensureActive()
                    if (!lifetime.accepting) throw CancellationException("Projection stopped")
                    ServiceState.connectionState.value = ServiceState.ConnectionState.CONNECTING
                    val port = intent.getIntExtra(EXTRA_PORT, 5277)
                    require(port in 1024..65535)
                    ProjectionIntegration.reportCarSelected(if (wirelessMode) intent.getStringExtra(EXTRA_BLUETOOTH_ADDRESS) else null)
                    val connection = when (mode) {
                        "wireless-car" -> WirelessCarTransport(
                            CarRfcommTransport(this@ProjectionService, intent.getStringExtra(EXTRA_BLUETOOTH_ADDRESS).orEmpty()),
                            AndroidCarNetworkJoiner(this@ProjectionService)
                        ) { status ->
                            wirelessReport?.record(status)
                            if (lifetime.accepting) ServiceState.addEvent(status.message)
                        }
                        "tcp-client" -> TcpTransport(intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1", port)
                        "tcp-server" -> TcpServerTransport(port)
                        "auto" -> if (UsbAoaTransport.findAccessory(this@ProjectionService) != null)
                            UsbAoaTransport(this@ProjectionService) else TcpServerTransport(port)
                        else -> error("Unknown transport")
                    }
                    transport = connection
                    ensureActive()
                    if (!lifetime.accepting) throw CancellationException("Projection stopped")
                    ServiceState.addEvent(if (wirelessMode) "Connecting to the selected car." else "Waiting: $mode port $port")
                    connection.connect()
                    ensureActive()
                    if (!lifetime.accepting) throw CancellationException("Projection stopped")
                    wirelessReport?.record(WirelessConnectionReport.Projection.AUTHENTICATING)
                    readinessDeadline = ProjectionReadinessDeadline(this) {
                        wirelessReport?.record(WirelessConnectionReport.Projection.HANDSHAKE_TIMED_OUT)
                        ServiceState.addEvent("The car did not finish starting projection. The connection was released.")
                        ServiceState.connectionState.value = ServiceState.ConnectionState.ERROR
                        requestShutdown(); stopSelf()
                    }.also { it.start() }
                    tlsEngine = tlsFactory()
                    tls = InBandTls(checkNotNull(tlsEngine))
                    engine = ProtocolEngine(this@ProjectionService).apply { deviceName = "LibreAuto"; deviceBrand = "Open Source"; start() }
                    launch { writeLoop(connection) }
                    launch { delay(500); engine?.initiateVersionRequest() }
                    readLoop(connection)
                } finally {
                    withContext(NonCancellable) {
                        requestShutdown(cancelSession = false)
                        closeTransport()
                        // Includes writer, version, channel-open, readiness and ping jobs.
                        this@coroutineScope.coroutineContext.cancelChildren()
                    }
                }
            } // Does not return until every TLS reader/writer/control child has finished.
        } catch (cancelled: CancellationException) { /* Shutdown owns actual socket close and join. */ }
        catch (error: Exception) {
            if (wirelessMode) {
                wirelessReport?.record(WirelessConnectionReport.Projection.FAILED)
                ServiceState.addEvent("The wireless connection stopped. The last connection report contains its setup stages.")
            } else ServiceState.addEvent("Session error: ${error.message}")
            ServiceState.connectionState.value = ServiceState.ConnectionState.ERROR
        }
    }
    private suspend fun readLoop(connection: Transport) {
        val buffer = ByteBuffer.allocate(131072)
        // TLS records are decoded before their plaintext AAP fragments are reassembled.
        val decoder = MessageFramer.Decoder { payload, header ->
            if (header.isEncrypted) checkNotNull(tls).decrypt(payload) else payload
        }
        while (currentCoroutineContext().isActive && lifetime.accepting) {
            val n = connection.read(buffer)
            if (n < 0) break
            if (n == 0) { yield(); continue }
            buffer.flip()
            for (message in decoder.decode(buffer)) route(message)
            buffer.compact()
            check(buffer.hasRemaining()) { "Peer exceeded frame buffer" }
        }
    }
    private fun route(message: MessageFramer.Decoder.Message) {
        if (!lifetime.accepting) return
        if (message.payload.size < 2) return
        val type = ((message.payload[0].toInt() and 255) shl 8) or (message.payload[1].toInt() and 255)
        val body = message.payload.copyOfRange(2, message.payload.size)
        val channel = message.channelId.toInt()
        // Record channel-specific control errors before the control router can consume them.
        if (channel == microphoneId && type != 0 && type != 1)
            microphoneTrace.record(MicrophoneProtocolTrace.Stage.ROUTED_MESSAGE, channel, type, body.size)
        if (channel == 0 || ((message.flags.toInt() and 4) != 0 && type < 0x8000)) {
            engine?.onMessage(type, body, channel)
        } else when (channel) {
            videoId -> video?.onMessage(type, body)
            inputId -> input?.onMessage(type, body)
            sensorId -> sensors?.onMessage(type, body)
            microphoneId -> audioIn?.onMessage(type, body)
            audioId -> audioOut?.onMessage(type, body)
            else -> ServiceState.addEvent("Unhandled channel $channel type $type")
        }
    }
    private suspend fun writeLoop(connection: Transport) {
        try {
            while (currentCoroutineContext().isActive && lifetime.accepting) {
                val message = outbound.receive()
                val type = if (message.payload.size >= 2) ((message.payload[0].toInt() and 255) shl 8) or (message.payload[1].toInt() and 255) else -1
                val encrypted = tls?.isHandshakeComplete == true && !(message.channel.toInt() == 0 && type in 1..4)
                val chunkSize = if (encrypted) 12_000 else MessageFramer.MAX_FRAME_PAYLOAD
                var offset = 0
                while (offset < message.payload.size) {
                    val end = minOf(offset + chunkSize, message.payload.size)
                    val plain = message.payload.copyOfRange(offset, end)
                    val data = if (encrypted) checkNotNull(tls).encrypt(plain) else plain
                    val kind = when {
                        message.payload.size <= chunkSize -> FrameHeader.FrameType.SINGLE
                        offset == 0 -> FrameHeader.FrameType.FIRST
                        end == message.payload.size -> FrameHeader.FrameType.LAST
                        else -> FrameHeader.FrameType.MIDDLE
                    }
                    val header = FrameHeader.create(message.channel, kind, message.control, encrypted)
                    val frame = if (kind == FrameHeader.FrameType.FIRST) MessageFramer.buildFirstFrame(header, data, message.payload.size) else MessageFramer.buildFrame(header, data)
                    connection.write(ByteBuffer.wrap(frame))
                    offset = end
                }
                ServiceState.framesSent.value = ++sendCount
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (wirelessMode) {
                wirelessReport?.record(WirelessConnectionReport.Projection.WRITE_FAILED)
                ServiceState.addEvent("The car connection stopped while sending projection data.")
            } else ServiceState.addEvent("Transport write failed: ${e.message}")
            throw e // Fail the structured session, which closes and joins its reader too.
        }
    }
    override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
        if (!lifetime.accepting) return
        // Active sessions still apply full backpressure. Shutdown rejects the entire callback.
        outbound.send(Outbound(channelId, payload, control), channelId.toInt() == 0 || control)
    }
    override fun onTlsData(data: ByteArray) {
        val current = checkNotNull(tls)
        current.beginHandshake().forEach { engine?.sendTlsData(it) }
        current.feedHandshakeData(data).forEach { engine?.sendTlsData(it) }
        if (current.isHandshakeComplete && engine?.state == ProtocolState.TLS_HANDSHAKE) engine?.onTlsHandshakeComplete()
    }
    override fun onTlsComplete() {}
    override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {
        engine?.sendServiceDiscoveryResponse(ServiceDiscoveryBuilder.build())
    }
    override fun onServiceDiscoveryResponse(channels: List<ChannelDescriptor>) {
        if (!lifetime.accepting) return
        if (!channelsInstalled.compareAndSet(false, true)) {
            // Never replace a channel whose native producer is still owned by this session.
            ServiceState.addEvent("The receiver repeated channel discovery. The connection was released.")
            requestShutdown(); stopSelf()
            return
        }
        val descriptor = channels.firstOrNull { it.hasAv && it.avStreamType == 3 } ?: channels.firstOrNull { it.hasAv }
        descriptor?.let {
            videoId = it.channelId
            video = VideoChannel(videoId.toUByte(), this, frameMetrics).also { v ->
                v.configure(VideoConfig(width=it.videoWidth,height=it.videoHeight,dpi=it.videoDpi));v.configurationIndex=it.videoConfigurationIndex;v.attachContext(this)
            }
            val oldCallback=touch.callback
            val touchDescriptor=channels.firstOrNull { entry -> entry.hasInput }
            touch=TouchInjector(touchDescriptor?.touchWidth?:it.videoWidth,touchDescriptor?.touchHeight?:it.videoHeight,it.videoWidth,it.videoHeight).also{injector->injector.callback=oldCallback}
        }
        channels.firstOrNull { it.hasAv && it.avStreamType == 1 && it.audioType == 3 && it.audioConfigurationIndex >= 0 }?.let {
            audioId = it.channelId
            audioOut = AudioOutputChannel(audioId.toUByte(), this, configurationIndex = it.audioConfigurationIndex) { active ->
                // This is an ordered worker notification, not a new dashboard stop request.
                if (lifetime.accepting) engine?.sendAudioFocusRequest(if (active && !callAudio.blocked) 1 else 4)
            }
        }
        channels.firstOrNull { it.hasInput }?.let { inputId = it.channelId; input = InputChannel(inputId.toUByte(), this, supportedKeyCodes = it.supportedKeyCodes) }
        channels.firstOrNull { it.hasSensor }?.let { sensorId = it.channelId; sensors = SensorChannel(sensorId.toUByte(), this) }
        channels.firstOrNull { it.supportsMicrophonePcm }?.let {
            microphoneId = it.channelId
            microphoneTrace.record(MicrophoneProtocolTrace.Stage.DISCOVERED, microphoneId)
            audioIn = AudioInputChannel(microphoneId.toUByte(), this, trace = microphoneTrace, configurationIndex = 0, mode = AudioInputChannel.Mode.DIRECT_OPEN,
                onStopped = { microphoneIngress.endAttempt() },
                ownedReceiver = object : AudioInputChannel.OwnedPcmReceiver {
                    override fun captureEpoch() = microphoneIngress.openEpoch()
                    override fun invalid(epoch: Long) { microphoneIngress.invalidAudio(epoch) }
                    override fun accept(epoch: Long, pcm: ByteArray, sampleRate: Int) {
                        microphoneIngress.offerOwned(epoch, pcm, sampleRate)
                    }
                })
        }
        if (audioOut == null && channels.any { it.hasAv && it.avStreamType == 1 && it.audioType == 3 })
            ServiceState.addEvent("Receiver has no supported 48 kHz stereo16 media output; playback is unavailable.")
        if (audioIn == null && channels.any { it.hasAvInput })
            ServiceState.addEvent("Receiver microphone format is unsupported; 16 kHz mono16 PCM is required.")
        launchSession {
            for (id in listOf(videoId, inputId, sensorId, microphoneId, audioId).filter { it > 0 }.distinct()) engine?.sendChannelOpenRequest(id)
        }
    }
    override fun onChannelOpenRequest(channelId: Int, priority: Int) { engine?.sendChannelOpenResponse(channelId, 0) }
    override fun onChannelOpened(channelId: Int) {
        if (channelId == microphoneId) microphoneTrace.record(MicrophoneProtocolTrace.Stage.CHANNEL_OPENED, channelId)
        when (channelId) {
            videoId -> video?.sendSetup()
            audioId -> audioOut?.sendSetup()
            microphoneId -> audioIn?.onChannelOpened()
            inputId -> {
                input?.setCommandCancelEnabled(commandCancelDesired && !callAudio.blocked && !handoffWaiting)
                input?.sendBindingRequest()
            }
            sensorId -> sensors?.requestDefaultSensors()
        }
    }
    override fun onActive() {
        if (!lifetime.accepting) return
        if (readinessDeadline?.ready() != true) return
        synchronized(shutdownLock) {
            if (!lifetime.accepting) return
            MessagingNotifications.setProjectionActive(this, true)
        }
        wirelessReport?.record(WirelessConnectionReport.Projection.ACTIVE)
        ServiceState.connectionState.value = ServiceState.ConnectionState.CONNECTED
        ProjectionIntegration.reportReady()
        ServiceState.addEvent("Protocol active")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Connected to car"))
        if (pingJob == null) pingJob = sessionChildren?.launch { while (isActive) { delay(2000); engine?.sendPingRequest(System.nanoTime() / 1000) } }
        engine?.sendAudioFocusRequest(4)
    }
    override fun onShutdown() { requestShutdown(); stopSelf() }
    override fun onAudioFocusRequest(focusType: Int) { callAudio.applyFocus(focusType in 1..3) { audioOut?.setFocusGranted(it) } }
    override fun onAudioFocusResponse(focusState: Int) { callAudio.applyFocus(focusState == 1 || focusState == 2) { audioOut?.setFocusGranted(it) } }
    fun requestMediaFocus(active: Boolean) {
        // The PCM worker serializes acquisition and release. Acquire only after actual
        // PCM admission; a pause queues release behind even an already-popped acquire.
        if (!active) audioOut?.stopPlayback(notifyFocus = true)
    }
    override fun onNavigationFocusRequest(type: Int) {}
    override fun onVoiceSessionRequest(type: Int) {
        if (type != 1) microphoneIngress.endAttempt()
        postSession {
            pendingVoiceRequest = type == 1 && !callAudio.blocked
            if (pendingVoiceRequest) dashboard?.requestVoiceSession() else dashboard?.onVoiceSession(false)
        }
    }
    private fun onVoiceRequested(requested: Boolean) {
        val start = requested && !callAudio.blocked
        if (start) microphoneIngress.beginAttempt() else microphoneIngress.endAttempt()
        pendingVoiceRequest = start
        microphoneTimeout?.let { main.removeCallbacks(it) }; microphoneTimeout = null
        val carMicrophone = dashboard?.requiresCarMicrophone() == true
        val token = microphoneGate.request(start, carMicrophone, newAttempt = start)
        if (!carMicrophone) audioIn?.requestOpen(false)
        if (start && carMicrophone && !microphoneGate.hasOpened) {
            val readinessTimeout = (dashboard?.voicePreparationTimeoutMs() ?: 15_000L).coerceIn(1_000L, 30_000L)
            val timeout = Runnable {
                if (microphoneGate.expire(token)) {
                    pendingVoiceRequest = false
                    ServiceState.addEvent("Voice input did not become ready within ${readinessTimeout / 1000} seconds")
                    dashboard?.onVoiceSession(false)
                }
            }
            microphoneTimeout = timeout
            main.postDelayed(timeout, readinessTimeout)
        }
    }
    private fun onMicrophoneReady(ready: Boolean) {
        if (!ready) microphoneIngress.setOpen(false)
        postSession {
            val admitted = ready && !callAudio.blocked
            if (!admitted) microphoneIngress.setOpen(false)
            microphoneGate.recordingReady(admitted)
            if (microphoneGate.hasOpened) { microphoneTimeout?.let { main.removeCallbacks(it) }; microphoneTimeout = null }
        }
    }
    private fun onMicrophoneIngressFailure(attempt: Long, reason: CarPcmIngress.Failure) {
        if (!lifetime.accepting || !microphoneIngress.isFailedAttempt(attempt)) return
        pendingVoiceRequest = false
        ServiceState.addEvent(when(reason) {
            CarPcmIngress.Failure.OVERFLOW -> "Car microphone input could not keep up. Request voice input again."
            CarPcmIngress.Failure.INVALID_AUDIO -> "Car microphone audio format is unsupported. The voice turn stopped."
            else -> "Car microphone delivery stopped. Request voice input again."
        })
        try { dashboard?.onVoiceSession(false) }
        catch (_: Exception) { requestShutdown(); stopSelf() }
        finally { microphoneGate.request(false) }
    }
    override fun onVideoFrame(channelId: UByte, payload: ByteArray) = onSendFrame(channelId, payload, false)
    override fun onSendMessage(channelId: UByte, payload: ByteArray) = onSendFrame(channelId, payload, false)
    override fun onVideoDisplayReady(display: Display) { postSession {
        // Peer STOP/START can retire the old display while its Ready callback is queued.
        if (!display.isValid) return@postSession
        if (dashboard != null) microphoneIngress.endAttempt()
        try { dashboard?.dismiss(); dashboard = null }
        catch (_: Exception) { requestShutdown(); return@postSession }
        val view = ProjectionDashboard(this, display, frameMetrics)
        dashboard = view // Retain teardown ownership even if show() rejects the retired display.
        try {
            view.setAudioOutput { bytes -> callAudio.write(bytes) }
            view.setMediaFocusRequest { active -> requestMediaFocus(active) }
            view.setMicrophoneControl { ready -> onMicrophoneReady(ready && !callAudio.blocked) }
            view.setVoiceSessionListener { requested -> onVoiceRequested(requested) }
            view.setCancelableFlowListener(::updateCommandCancelBinding)
            view.setCallHandoffGate(::requestCallHandoff)
            view.setCallHandoffStillValid(::isCallHandoffStillValid)
            view.show()
            view.setPhoneCallActive(callAudio.blocked)
            if (pendingVoiceRequest) view.requestVoiceSession()
        } catch (_: WindowManager.InvalidDisplayException) { discardRetiredDashboard(view); return@postSession }
        catch (_: WindowManager.BadTokenException) { discardRetiredDashboard(view); return@postSession }
        ServiceState.connectionState.value = ServiceState.ConnectionState.STREAMING
    } }
    private fun discardRetiredDashboard(view: ProjectionDashboard) {
        microphoneIngress.endAttempt()
        try { view.dismiss(); if (dashboard === view) dashboard = null }
        catch (_: Exception) { requestShutdown() } // Retain the reference for acknowledged final teardown.
    }
    override fun onVideoDisplayRemoved() {
        microphoneIngress.endAttempt()
        postSession {
            try { dashboard?.dismiss(); dashboard = null }
            catch (_: Exception) { requestShutdown() }
        }
    }
    private fun updateCommandCancelBinding(active: Boolean) {
        // A fresh UI intent invalidates an earlier completed handoff lease.
        if(active && handoffWaiting) finishCallHandoff(false)
        commandCancelDesired=active
        // Input binding writes share the bounded transport queue; never block the UI on it.
        launchSession { synchronized(keyBindingLock) { input?.setCommandCancelEnabled(commandCancelDesired && !callAudio.blocked && !handoffWaiting) } }
    }
    private fun requestCallHandoff(completion: (Boolean) -> Unit) {
        finishCallHandoff(false)
        if(!lifetime.accepting || callAudio.blocked) { completion(false); return }
        handoffReleaseRequested=false; handoffWaiting=true; callHandoff=completion
        val timeout=Runnable { finishCallHandoff(false) }
        callHandoffTimeout=timeout; main.postDelayed(timeout,3_000L)
        launchSession {
            synchronized(keyBindingLock) {
                input?.setCommandCancelEnabled(false)
                handoffReleaseRequested=true
            }
            postSession { if(handoffWaiting && handoffReleaseRequested && input?.isCommandCancelReleased()==true) finishCallHandoff(true) }
        }
    }
    private fun finishCallHandoff(accepted: Boolean) {
        callHandoffTimeout?.let(main::removeCallbacks); callHandoffTimeout=null
        val valid=accepted && lifetime.accepting && !callAudio.blocked && input?.isCommandCancelReleased()==true
        val completion=callHandoff; callHandoff=null
        handoffWaiting=valid; handoffReleaseRequested=valid
        // Retain the release lease through the final async provider requery and dispatch.
        completion?.invoke(valid)
    }
    private fun isCallHandoffStillValid(): Boolean = lifetime.accepting && handoffWaiting &&
        !callAudio.blocked && input?.isCommandCancelReleased()==true
    override fun onInputBindingStatus(status: Int) {
        postSession {
            if(handoffWaiting && (status!=0 || handoffReleaseRequested && input?.isCommandCancelReleased()==true)) finishCallHandoff(status==0)
        }
        val result = if (status == 0) "Car input binding accepted" else "Car input binding failed (status $status); touch disabled"
        android.util.Log.i("OpenAutoInput", result)
        ServiceState.addEvent(result)
    }
    override fun onTouchEvent(event: InputEvent) = touch.inject(event)
    override fun onKeyEvent(event: org.openandroidauto.channel.KeyEvent) { postSession {
        val code = KeyCodeMap.toAndroidKeyCode(event.scanCode)
        val action = CarKeyActions.forKey(code)
        if (action != CarKeyActions.Action.NONE) {
            if (!event.isPressed) {
                when (action) {
                    CarKeyActions.Action.ASSISTANT -> {
                        pendingVoiceRequest = !callAudio.blocked
                        dashboard?.requestVoiceSession()
                    }
                    CarKeyActions.Action.CALLS -> dashboard?.confirmCallOrShowCalls()
                    CarKeyActions.Action.MEDIA -> dashboard?.onMediaKey(code)
                    CarKeyActions.Action.CANCEL_PENDING -> {
                        pendingVoiceRequest=false
                        microphoneIngress.endAttempt()
                        dashboard?.cancelPendingCommand()
                    }
                    CarKeyActions.Action.NONE -> Unit
                }
            }
        } else {
            val key = android.view.KeyEvent(if (event.isPressed) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP, code)
            dashboard?.dispatchKeyEvent(key)
        }
    } }
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        super.dump(fd, writer, args)
        FrameCadenceCounters.dump(writer)
        writer.println("OPEN_AUTO_COMMAND_STATE_V1")
        writer.println(dashboard?.diagnosticFlags() ?: "dashboard_absent=true")
        writer.println(audioIn?.diagnosticFlags() ?: "mic_absent=true")
        writer.println("input_binding=${input?.bindingState?.name ?: "NONE"} input_status=${input?.bindingStatus} end_call_released=${input?.isCommandCancelReleased()} handoff_waiting=$handoffWaiting")
    }
    private fun notification(message: String): Notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("LibreAuto · Car display").setContentText(message).setOngoing(true).build()
}
