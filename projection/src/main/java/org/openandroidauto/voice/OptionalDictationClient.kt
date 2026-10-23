// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import org.openandroidauto.voice.DictationProtocol as P

/** Optional same-signer add-on only. This APK owns no native recognizer, model or microphone.
 * acceptPcm copies before returning; one <=200ms queue plus one <=200ms IPC frame is bounded.
 * Binder input never runs on Main/protocol callbacks. No recognition or binding while idle.
 */
class OptionalDictationClient(
    context: Context,
    private val onTranscript: (Long, String) -> Unit,
    private val onStatus: (String) -> Unit = {}
) : VoiceSessionHandler {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var readiness: (Boolean) -> Unit = {}
    private var ended: () -> Unit = {}
    private var disposed = false
    @Volatile private var current: Turn? = null
    override val requiresCarMicrophone = true
    override val preparationTimeoutMs = 15_000L
    private class Turn(val token: Long, val expectedUid: Int, val replyToken: Long) {
        val queue = DictationPcmQueue()
        val resultGate = DictationResultGate(replyToken)
        val stopping = AtomicBoolean()
        val remoteClosed = AtomicBoolean()
        val dead = AtomicBoolean()
        val workerFinished = AtomicBoolean()
        val wake = Semaphore(0)
        @Volatile var service: IBinder? = null
        var bound = false // Main only.
        var ready = false
        var result = false
        var ended = false
        var worker: Thread? = null
        var death: IBinder.DeathRecipient? = null
        lateinit var connection: ServiceConnection
        lateinit var callback: IBinder
        val expires = SystemClock.elapsedRealtime() + 35_000L
        var timeout: Runnable? = null
    }
    override fun setReadinessListener(listener: (Boolean) -> Unit) { assertMain(); readiness = listener; listener(current?.ready == true) }
    override fun setEndedListener(listener: () -> Unit) { assertMain(); ended = listener }
    /** Advisory content-free flags; no turn token, transcript or recipient information. */
    fun diagnosticFlags(): String {
        val turn = current
        return "dictation_current=${turn != null} dictation_stopping=${turn?.stopping?.get() == true} " +
            "dictation_remote_closed=${turn?.remoteClosed?.get() == true} dictation_dead=${turn?.dead?.get() == true} " +
            "dictation_worker_finished=${turn?.workerFinished?.get() == true} dictation_ready=${turn?.ready == true} dictation_result=${turn?.result == true}"
    }
    override fun request(replyToken: Long): Boolean {
        assertMain()
        if (disposed || current != null || replyToken == 0L) return false
        val info = try { app.packageManager.getApplicationInfo(P.PACKAGE, 0) }
        catch (_: PackageManager.NameNotFoundException) { status("Install Open Auto Dictation to use offline replies."); return false }
        if (!info.enabled || app.packageManager.checkSignatures(Process.myUid(), info.uid) != PackageManager.SIGNATURE_MATCH) {
            status("Open Auto Dictation must use the same trusted signing key."); return false
        }
        var token: Long; do { token = random.nextLong() } while (token == 0L)
        val turn = Turn(token, info.uid, replyToken); current = turn
        turn.callback = callback(turn); turn.connection = connection(turn)
        val timeout = Runnable {
            if (current === turn && !turn.remoteClosed.get() && !turn.dead.get()) stopTurn(turn, "Dictation timed out. Try again after it closes.")
        }
        turn.timeout = timeout; main.postDelayed(timeout, 35_000L)
        status("Preparing offline dictation…")
        try {
            turn.bound = app.bindService(Intent().setComponent(ComponentName(P.PACKAGE, P.SERVICE)), turn.connection, Context.BIND_AUTO_CREATE)
            if (!turn.bound) { turn.remoteClosed.set(true); stopTurn(turn, "Open Auto Dictation could not start."); release(turn) }
        } catch (_: Exception) {
            turn.remoteClosed.set(true); stopTurn(turn, "Open Auto Dictation is unavailable."); release(turn)
        }
        return current === turn
    }
    override fun acceptPcm(pcm: ByteArray, sampleRate: Int): Boolean {
        val turn = current ?: return false
        if (turn.stopping.get()) return false
        // READY(false) can arrive on Binder before Main closes the car microphone.
        // A valid frame already admitted at that edge is consumed without retaining it,
        // rather than cancelling the still-running final inference.
        if (turn.resultGate.canDiscardEndedInput()) return P.validPcm(pcm, sampleRate)
        return when (turn.queue.offer(pcm, sampleRate)) {
            DictationPcmQueue.Offer.ACCEPTED -> true
            DictationPcmQueue.Offer.CLOSED -> turn.resultGate.canDiscardEndedInput() && P.validPcm(pcm, sampleRate)
            DictationPcmQueue.Offer.INVALID -> { stopTurn(turn, "The car audio format is unsupported."); false }
            DictationPcmQueue.Offer.OVERFLOW -> { stopTurn(turn, "Dictation could not keep up with car audio. Please retry."); false }
            else -> false
        }
    }
    override fun stop() { current?.let { stopTurn(it, null) } }
    override fun close() { assertMain(); disposed = true; stop() }
    private fun connection(turn: Turn) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (current !== turn) return
            if (name != ComponentName(P.PACKAGE, P.SERVICE) || turn.stopping.get() || turn.remoteClosed.get() || turn.dead.get()) {
                // No START has been issued by this binding. Refuse late/cancelled publication.
                turn.remoteClosed.set(true); turn.queue.close(); release(turn); return
            }
            turn.service = service
            val death = IBinder.DeathRecipient {
                turn.dead.set(true); turn.queue.close(); turn.wake.release()
                main.post { if (current === turn) { notReady(turn); if (!turn.result) status("Dictation stopped."); release(turn) } }
            }
            turn.death = death
            try { service.linkToDeath(death, 0) }
            catch (_: RemoteException) { death.binderDied(); return }
            catch (_: Exception) {
                // START has not been issued, so no remote turn can own native work yet.
                turn.remoteClosed.set(true); stopTurn(turn, "Dictation connection failed."); release(turn); return
            }
            startWorker(turn, service)
        }
        override fun onServiceDisconnected(name: ComponentName) { stopTurn(turn, "Dictation disconnected.") }
        override fun onNullBinding(name: ComponentName) { turn.remoteClosed.set(true); stopTurn(turn, "Dictation is unavailable."); release(turn) }
        override fun onBindingDied(name: ComponentName) { stopTurn(turn, "Dictation disconnected.") }
    }
    private fun startWorker(turn: Turn, service: IBinder) {
        if (current !== turn || turn.stopping.get() || turn.remoteClosed.get() || turn.dead.get()) {
            turn.remoteClosed.set(true); turn.queue.close(); release(turn); return
        }
        val worker = Thread({
            try {
                // Linearize START admission against cancel without holding a lock across Binder.
                if (current !== turn || turn.stopping.get() || turn.remoteClosed.get() || turn.dead.get() ||
                    !turn.resultGate.claimRemoteStart()) {
                    turn.remoteClosed.set(true); return@Thread
                }
                val started = call(service, P.START) {
                    it.writeInt(P.VERSION); it.writeLong(turn.token); it.writeStrongBinder(turn.callback)
                }
                if (!started) { turn.remoteClosed.set(true); stopTurn(turn, "Dictation is busy or its model is unavailable."); return@Thread }
                while (!turn.stopping.get() && !turn.remoteClosed.get() && !turn.dead.get()) {
                    val pcm = turn.queue.take() ?: break
                    try {
                        if (turn.stopping.get() || turn.remoteClosed.get()) break
                        val accepted = call(service, P.PCM) { it.writeLong(turn.token); it.writeInt(P.SAMPLE_RATE); it.writeByteArray(pcm) }
                        if (!accepted) { turn.queue.close(); break } // Input may have just endpointed; await final/closed.
                    } finally { pcm.fill(0) }
                }
                // Endpointing closes input before inference returns. Do not cancel it merely
                // because READY(false) or the last accepted PCM closed this queue.
                var stopSent = false
                while (!turn.remoteClosed.get() && !turn.dead.get()) {
                    if (turn.stopping.get() && !stopSent) {
                        call(service, P.STOP) { it.writeLong(turn.token) }; stopSent = true
                    }
                    turn.wake.acquire()
                }
            } catch (_: InterruptedException) { stopTurn(turn, null) }
            catch (_: Exception) { stopTurn(turn, "Dictation connection failed.") }
            finally {
                if (!turn.remoteClosed.get() && !turn.dead.get()) {
                    try { call(service, P.STOP) { it.writeLong(turn.token) } } catch (_: Exception) {}
                }
                turn.workerFinished.set(true)
                main.post { release(turn) }
            }
        }, "OpenAutoDictationInput").apply { isDaemon = true }
        turn.worker = worker
        try { worker.start() }
        catch (_: Exception) { workerStartFailed(turn) }
        catch (_: OutOfMemoryError) { workerStartFailed(turn) } // Only native Thread.start allocation failure.
    }
    private fun workerStartFailed(turn: Turn) {
        // Thread.start refused before any remote START; this is not a native-worker death claim.
        turn.workerFinished.set(true); turn.remoteClosed.set(true)
        stopTurn(turn, "Dictation could not start."); release(turn)
    }
    private fun callback(turn: Turn) = object : Binder() {
        init { attachInterface(null, P.CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(P.CALLBACK); return true }
            if (Binder.getCallingUid() != turn.expectedUid || flags and IBinder.FLAG_ONEWAY == 0) throw SecurityException("Unexpected dictation callback")
            data.enforceInterface(P.CALLBACK)
            if (data.readLong() != turn.token || current !== turn) return false
            when (code) {
                P.READY -> {
                    val value = data.readInt(); P.end(data); if (value !in 0..1) return false
                    if (value == 0) { turn.resultGate.inputClosed(); turn.queue.close() }
                    main.post {
                        if (current !== turn) return@post
                        if (value == 1 && !turn.stopping.get() && turn.queue.ready()) { turn.ready = true; turn.resultGate.ready(); try { readiness(true) } catch (_: Exception) { stopTurn(turn, null) } }
                        else notReady(turn)
                    }
                }
                P.STATUS -> {
                    val value = data.readInt(); P.end(data); if (!P.validStatus(value)) return false
                    if (value == P.CLOSED) { turn.remoteClosed.set(true); turn.queue.close(); turn.wake.release() }
                    main.post {
                        if (current !== turn) return@post
                        when (value) {
                            P.CLOSED -> { notReady(turn); release(turn) }
                            P.LISTENING -> if (!turn.stopping.get()) status("Listening for your reply…")
                            P.INPUT_FINISHED -> { notReady(turn); status("Finishing offline dictation…") }
                            P.MODEL_REQUIRED -> stopTurn(turn, "Open Open Auto Dictation and install its English model.")
                            P.BUSY -> stopTurn(turn, "Dictation is still closing. Please retry shortly.")
                            P.NO_SPEECH -> stopTurn(turn, "No speech detected. Please retry.")
                            P.TIMEOUT -> stopTurn(turn, "Dictation timed out. Please retry.")
                            P.INVALID_AUDIO, P.INPUT_OVERFLOW, P.FAILED -> stopTurn(turn, "Dictation failed. Please retry.")
                        }
                    }
                }
                P.TRANSCRIPT -> {
                    val text = P.readText(data); P.end(data)
                    turn.resultGate.inputClosed(); turn.queue.close()
                    main.post {
                        if (current !== turn || turn.stopping.get() || turn.result) return@post
                        notReady(turn)
                        val owner = turn.resultGate.claimTranscript()
                        if (owner == 0L) { stopTurn(turn, "Dictation returned an unexpected result."); return@post }
                        turn.result = true
                        try { onTranscript(owner, text); status("Review your reply before sending.") }
                        catch (_: Exception) { stopTurn(turn, null) }
                    }
                }
                else -> return false
            }
            return true
        }
    }
    private fun stopTurn(turn: Turn, message: String?) {
        turn.queue.close(); turn.resultGate.cancel()
        if (!turn.stopping.compareAndSet(false, true)) return
        turn.wake.release()
        main.post {
            if (current !== turn) return@post
            notReady(turn); if (message != null) status(message)
            // No START was issued before onServiceConnected; cancel this binding and refuse late callbacks.
            if (turn.service == null) { turn.remoteClosed.set(true); release(turn) }
        }
    }
    private fun release(turn: Turn) {
        if (current !== turn || (!turn.remoteClosed.get() && !turn.dead.get())) return
        if (turn.worker != null && !turn.workerFinished.get()) return
        turn.queue.close(); notReady(turn)
        if (turn.bound) {
            try { app.unbindService(turn.connection) } catch (_: IllegalArgumentException) {}
            turn.bound = false
        }
        turn.service?.let { service -> turn.death?.let { try { service.unlinkToDeath(it, 0) } catch (_: Exception) {} } }
        turn.timeout?.let(main::removeCallbacks)
        current = null
        if (!turn.ended) { turn.ended = true; try { ended() } catch (_: Exception) {} }
    }
    private fun notReady(turn: Turn) { turn.resultGate.inputClosed(); if (turn.ready) { turn.ready = false; try { readiness(false) } catch (_: Exception) {} } }
    private fun status(message: String) { try { onStatus(message) } catch (_: Exception) {} }
    private fun assertMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun call(service: IBinder, code: Int, payload: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(P.DESCRIPTOR); payload(data)
            if (!service.transact(code, data, reply, 0)) false else { reply.readException(); reply.readInt() != 0 }
        } finally { data.recycle(); reply.recycle() }
    }
    companion object { private val random = SecureRandom() }
}
