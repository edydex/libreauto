// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.transport

import java.io.IOException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.openandroidauto.wireless.CarNetworkJoiner
import org.openandroidauto.wireless.LegacyWppSession
import org.openandroidauto.wireless.WppCodec

/** Serialized legacy WPP bootstrap, followed by transport on its retained, exact car Network. */
class WirelessCarTransport internal constructor(
    private val bluetooth: Transport,
    private val networks: CarNetworkJoiner,
    private val onStatus: (Status) -> Unit,
    private val clockMillis: () -> Long,
    private val timing: Timing
) : Transport {
    constructor(bluetooth: Transport, networks: CarNetworkJoiner, onStatus: (Status) -> Unit = {}) :
        this(bluetooth, networks, onStatus, elapsedClock(), Timing())

    /** Fixed nonpersonal reasons only. Never append adapter exception messages or parameters. */
    enum class Status(val message: String) {
        CONNECTING_BLUETOOTH("Connecting to the selected car"),
        WAITING_ENDPOINT("Waiting for the car's wireless endpoint"),
        WAITING_CREDENTIALS("Waiting for the car's Wi-Fi details"),
        WAITING_NETWORK("Waiting for Wi-Fi approval and connection"),
        CONNECTING_TRANSPORT("Connecting through the car's Wi-Fi"),
        CONNECTED("Car network transport connected"),
        DISCONNECTED("The car connection ended"), CANCELLED("Connection cancelled"),
        TIMED_OUT("The car connection timed out"), NETWORK_FAILED("The car's Wi-Fi connection was not established"),
        NETWORK_LOST("The car's Wi-Fi connection was lost"), BLUETOOTH_FAILED("The car's Bluetooth connection failed"),
        TRANSPORT_FAILED("The car network transport failed"), PROTOCOL_ERROR("The car sent an invalid wireless exchange"),
        UNSUPPORTED_VERSION("This car's wireless setup version is unsupported"),
        UNSUPPORTED_MESSAGE("This car's wireless setup message is unsupported"),
        BUFFER_LIMIT("The car exceeded the wireless setup input limit")
    }
    class Failure(val status: Status) : IOException("Wireless connection ended: ${status.name}")
    internal data class Timing(
        val bluetoothTimeoutMs: Long = 15_000,
        val transportTimeoutMs: Long = 15_000,
        val writeTimeoutMs: Long = 5_000,
        val tickMs: Long = 50
    ) {
        init { require(bluetoothTimeoutMs > 0 && transportTimeoutMs > 0 && writeTimeoutMs > 0 && tickMs in 1..1000) }
    }
    private val ownerLock = Any()
    @Volatile private var attempt: Attempt? = null
    override val isConnected: Boolean get() = attempt?.connectedTransport() != null

    override suspend fun connect() {
        val next = synchronized(ownerLock) {
            if (attempt != null) throw IOException("Wireless connection is already active")
            Attempt().also { attempt = it }
        }
        next.start()
        try { next.ready.await() }
        catch (cancelled: CancellationException) { next.stop(); throw cancelled }
    }
    override suspend fun disconnect() { attempt?.stop() }
    override suspend fun read(buffer: ByteBuffer): Int {
        val current = attempt ?: throw IOException("Wireless transport is not connected")
        val transport = current.connectedTransport() ?: throw IOException("Wireless transport is not connected")
        return try {
            transport.read(buffer).also { if (it < 0) current.fail(Status.DISCONNECTED) }
        } catch (cancelled: CancellationException) { current.stop(); throw cancelled }
        catch (_: Exception) { current.fail(Status.TRANSPORT_FAILED); throw failure(Status.TRANSPORT_FAILED) }
    }
    override suspend fun write(buffer: ByteBuffer) {
        val current = attempt ?: throw IOException("Wireless transport is not connected")
        val transport = current.connectedTransport() ?: throw IOException("Wireless transport is not connected")
        try { transport.write(buffer) }
        catch (cancelled: CancellationException) { current.stop(); throw cancelled }
        catch (_: Exception) { current.fail(Status.TRANSPORT_FAILED); throw failure(Status.TRANSPORT_FAILED) }
    }

    private sealed class Event {
        object BluetoothConnected : Event()
        class Frame(val frame: WppCodec.Frame) : Event()
        class Available(val lease: CarNetworkJoiner.Lease) : Event()
        object Unavailable : Event()
        object Lost : Event()
        object TransportConnected : Event()
    }
    private fun dispose(event: Event) {
        when (event) {
            is Event.Frame -> event.frame.close()
            is Event.Available -> try { event.lease.close() } catch (_: Exception) { }
            else -> Unit
        }
    }
    private inner class Attempt {
        val ready = CompletableDeferred<Unit>()
        private val finished = CompletableDeferred<Unit>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val running = AtomicBoolean(true)
        private val terminal = AtomicReference<Status?>(null)
        private val events = Channel<Event>(8, onUndeliveredElement = ::dispose)
        private var reducer: LegacyWppSession? = null
        private var request: CarNetworkJoiner.Request? = null
        private var lease: CarNetworkJoiner.Lease? = null
        @Volatile private var tcp: Transport? = null
        @Volatile var connected = false
            private set
        private var stage = Status.CONNECTING_BLUETOOTH
        private var ioDeadline = 0L
        @Volatile private var watchdogDeadline = Long.MAX_VALUE
        private var watchdog: Job? = null
        private var previousTime = 0L

        fun start() {
            // Enter the try/finally before the first dispatch, even if disconnect races start().
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    previousTime = clockMillis()
                    if (previousTime < 0) throw failure(Status.PROTOCOL_ERROR)
                    ioDeadline = deadline(previousTime, timing.bluetoothTimeoutMs)
                    watchdogDeadline = ioDeadline
                    report(Status.CONNECTING_BLUETOOTH)
                    watchdog = scope.launch {
                        var last = clockMillis()
                        while (scope.isActive && running.get()) {
                            delay(timing.tickMs)
                            if (connected) return@launch
                            val now = clockMillis()
                            if (now < last) { fail(Status.PROTOCOL_ERROR); return@launch }
                            last = now
                            if (now >= watchdogDeadline) { fail(Status.TIMED_OUT); return@launch }
                        }
                    }
                    scope.launch {
                        try { withTimeout(timing.bluetoothTimeoutMs) { bluetooth.connect() }; post(Event.BluetoothConnected) }
                        catch (cancelled: CancellationException) { if (running.get()) fail(Status.TIMED_OUT) }
                        catch (_: Exception) { fail(Status.BLUETOOTH_FAILED) }
                    }
                    while (scope.isActive && running.get()) {
                        terminal.get()?.let { throw failure(it) }
                        val now = clockMillis()
                        if (now < previousTime) { fail(Status.PROTOCOL_ERROR); continue }
                        previousTime = now
                        if ((stage == Status.CONNECTING_BLUETOOTH || stage == Status.CONNECTING_TRANSPORT) && now >= ioDeadline) {
                            fail(Status.TIMED_OUT); continue
                        }
                        reducer?.let { execute(it.tick()) }
                        if (terminal.get() != null) continue
                        // No bootstrap deadline remains after readiness. Idle projection waits
                        // for a real callback/frame instead of waking on a polling timer.
                        val event = if (connected) events.receive()
                            else withTimeoutOrNull(timing.tickMs) { events.receive() } ?: continue
                        try { handle(event) } finally { if (event is Event.Frame) event.frame.close() }
                    }
                } catch (cancelled: CancellationException) { terminal.compareAndSet(null, Status.CANCELLED) }
                catch (_: Exception) { terminal.compareAndSet(null, Status.PROTOCOL_ERROR) }
                finally {
                    withContext(NonCancellable) {
                        running.set(false); connected = false
                        scope.cancel(); events.cancel()
                        reducer?.cancel(); reducer = null
                        try { request?.close() } catch (_: Exception) { }; request = null
                        try { lease?.close() } catch (_: Exception) { }; lease = null
                        try { tcp?.disconnect() } catch (_: Exception) { }; tcp = null
                        try { bluetooth.disconnect() } catch (_: Exception) { }
                        val reason = terminal.get() ?: Status.CANCELLED
                        synchronized(ownerLock) { if (attempt === this@Attempt) attempt = null }
                        ready.completeExceptionally(failure(reason))
                        report(reason)
                        finished.complete(Unit)
                    }
                }
            }
        }
        fun connectedTransport(): Transport? = if (connected && running.get() && terminal.get() == null) tcp else null
        fun fail(status: Status) {
            if (running.get() && terminal.compareAndSet(null, status)) { connected = false; scope.cancel() }
        }
        suspend fun stop() {
            terminal.compareAndSet(null, Status.CANCELLED)
            running.set(false); connected = false; scope.cancel()
            withContext(NonCancellable) { finished.await() }
        }
        private fun post(event: Event) {
            if (!running.get() || terminal.get() != null) { dispose(event); return }
            if (!events.trySend(event).isSuccess) { dispose(event); fail(Status.BUFFER_LIMIT) }
        }
        private suspend fun handle(event: Event) {
            if (!running.get() || terminal.get() != null) { if (event is Event.Available) dispose(event); return }
            when (event) {
                Event.BluetoothConnected -> {
                    if (reducer != null) { fail(Status.PROTOCOL_ERROR); return }
                    reducer = LegacyWppSession { clockMillis() }
                    watchdogDeadline = reducer!!.deadlineMillis()
                    report(Status.WAITING_ENDPOINT)
                    scope.launch { readBluetooth() }
                }
                is Event.Frame -> {
                    val current = reducer ?: run { fail(Status.PROTOCOL_ERROR); return }
                    execute(current.accept(event.frame))
                }
                is Event.Available -> {
                    val current = reducer
                    if (current == null || event.lease.token != current.token() || current.state() != LegacyWppSession.State.WAIT_NETWORK) {
                        if (event.lease !== lease) dispose(event)
                        return
                    }
                    lease = event.lease
                    execute(current.networkAvailable(event.lease.token))
                }
                Event.Unavailable -> reducer?.let { execute(it.networkFailed(it.token())) }
                Event.Lost -> reducer?.let { execute(it.networkLost(it.token())) }
                Event.TransportConnected -> {
                    if (stage != Status.CONNECTING_TRANSPORT || tcp?.isConnected != true || lease == null) {
                        fail(Status.TRANSPORT_FAILED); return
                    }
                    if (!running.get() || terminal.get() != null) return
                    connected = true; watchdogDeadline = Long.MAX_VALUE
                    watchdog?.cancel(); watchdog = null
                    report(Status.CONNECTED)
                    if (running.get() && terminal.get() == null) ready.complete(Unit)
                }
            }
        }
        private suspend fun execute(actions: List<LegacyWppSession.Action>) {
            if (stage != Status.CONNECTING_TRANSPORT) watchdogDeadline = reducer?.deadlineMillis() ?: ioDeadline
            for (action in actions) {
                if (!running.get() || terminal.get() != null) return
                when (action) {
                    is LegacyWppSession.SendFrame -> {
                        val bytes = action.frameCopy()
                        try { withTimeout(timing.writeTimeoutMs) { bluetooth.write(ByteBuffer.wrap(bytes)) } }
                        catch (cancelled: CancellationException) {
                            if (running.get()) fail(Status.TIMED_OUT)
                            throw cancelled
                        } catch (_: Exception) { fail(Status.BLUETOOTH_FAILED); return }
                        finally { Arrays.fill(bytes, 0) }
                        if (action.type == 2) report(Status.WAITING_CREDENTIALS)
                    }
                    is LegacyWppSession.RequestNetwork -> {
                        report(Status.WAITING_NETWORK)
                        try {
                            request = networks.request(action.token, action.credentials, object : CarNetworkJoiner.Callback {
                                override fun available(lease: CarNetworkJoiner.Lease) { post(Event.Available(lease)) }
                                override fun unavailable() { post(Event.Unavailable) }
                                override fun lost() { post(Event.Lost) }
                            })
                        } catch (_: Exception) { fail(Status.NETWORK_FAILED); return }
                    }
                    is LegacyWppSession.ConnectTransport -> {
                        val retained = lease ?: run { fail(Status.NETWORK_FAILED); return }
                        val ipv4 = action.endpoint.ipv4Copy()
                        try { tcp = retained.createTransport(ipv4, action.endpoint.port()) }
                        catch (_: Exception) { fail(Status.TRANSPORT_FAILED); return }
                        finally { Arrays.fill(ipv4, 0) }
                        report(Status.CONNECTING_TRANSPORT)
                        ioDeadline = deadline(clockMillis(), timing.transportTimeoutMs)
                        watchdogDeadline = ioDeadline
                        val transport = tcp ?: run { fail(Status.TRANSPORT_FAILED); return }
                        scope.launch {
                            try { withTimeout(timing.transportTimeoutMs) { transport.connect() }; post(Event.TransportConnected) }
                            catch (cancelled: CancellationException) { if (running.get()) fail(Status.TIMED_OUT) }
                            catch (_: Exception) { fail(Status.TRANSPORT_FAILED) }
                        }
                    }
                    is LegacyWppSession.Closed -> fail(map(action.reason))
                }
            }
        }
        private suspend fun readBluetooth() {
            val decoder = WppCodec.Decoder()
            val buffer = ByteBuffer.allocate(4096)
            try {
                while (scope.isActive && running.get()) {
                    buffer.clear()
                    val n = bluetooth.read(buffer)
                    if (n < 0) { decoder.finish(); fail(Status.DISCONNECTED); return }
                    if (n == 0) { delay(5); continue }
                    if (n > buffer.capacity() || buffer.position() != n) { fail(Status.PROTOCOL_ERROR); return }
                    try { decoder.feed(buffer.array(), 0, n) { frame ->
                        if (terminal.get() != null || !running.get()) throw IOException("Wireless session closed")
                        post(Event.Frame(frame.retainCopy()))
                        if (terminal.get() != null) throw IOException("Wireless input queue limit")
                    } } finally { Arrays.fill(buffer.array(), 0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: WppCodec.CodecException) {
                fail(if (error.issue == WppCodec.Issue.PAYLOAD_LIMIT || error.issue == WppCodec.Issue.INPUT_LIMIT) Status.BUFFER_LIMIT else Status.PROTOCOL_ERROR)
            } catch (_: Exception) { fail(Status.BLUETOOTH_FAILED) }
            finally { decoder.close(); Arrays.fill(buffer.array(), 0) }
        }
        private fun report(status: Status) { stage = status; try { onStatus(status) } catch (_: Exception) { } }
    }
    companion object {
        private fun elapsedClock(): () -> Long { val origin = System.nanoTime(); return { (System.nanoTime() - origin) / 1_000_000 } }
        private fun failure(status: Status) = Failure(status)
        private fun deadline(now: Long, interval: Long): Long = if (now > Long.MAX_VALUE - interval) Long.MAX_VALUE else now + interval
        private fun map(reason: LegacyWppSession.Reason): Status = when (reason) {
            LegacyWppSession.Reason.CANCELLED -> Status.CANCELLED
            LegacyWppSession.Reason.DEADLINE -> Status.TIMED_OUT
            LegacyWppSession.Reason.NETWORK_FAILED -> Status.NETWORK_FAILED
            LegacyWppSession.Reason.NETWORK_LOST -> Status.NETWORK_LOST
            LegacyWppSession.Reason.TRANSPORT_CLOSED -> Status.DISCONNECTED
            LegacyWppSession.Reason.UNSUPPORTED_VERSION -> Status.UNSUPPORTED_VERSION
            LegacyWppSession.Reason.UNSUPPORTED_MESSAGE -> Status.UNSUPPORTED_MESSAGE
            else -> Status.PROTOCOL_ERROR
        }
    }
}
