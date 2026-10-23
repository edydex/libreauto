package org.openandroidauto.transport

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.coroutines.resumeWithException

/** One TCP session on one retained car Network. It never changes process/default routing. */
class NetworkTcpTransport internal constructor(
    private val factory: SocketFactory,
    ipv4: ByteArray,
    private val port: Int,
    private val timeouts: Timeouts = Timeouts(),
    private val worker: Executor = Dispatchers.IO.asExecutor(),
    private val scheduler: Scheduler = deadlines
) : Transport, AutoCloseable {
    constructor(network: Network, ipv4: ByteArray, port: Int) : this(network.socketFactory, ipv4, port)

    internal data class Timeouts(val connectMs: Long = 10_000, val readMs: Long = 2_000, val writeMs: Long = 2_000) {
        init { require(connectMs in 1..60_000 && readMs in 1..60_000 && writeMs in 1..60_000) }
    }
    internal fun interface Ticket { fun cancel() }
    internal fun interface Scheduler { fun schedule(milliseconds: Long, action: () -> Unit): Ticket }
    private enum class State { NEW, CONNECTING, CONNECTED, CLOSED }
    private val address = InetAddress.getByAddress(ipv4.copyOf().also { require(it.size == 4) })
    private val lock = Any()
    private var state = State.NEW
    private var socket: Socket? = null
    private val operations = mutableSetOf<Operation<*>>() // At most connect, or one reader + one writer.
    private val reader = AtomicBoolean()
    private val writer = AtomicBoolean()
    init { require(port in 1..65535) }
    override val isConnected: Boolean get() = synchronized(lock) { state == State.CONNECTED }

    override suspend fun connect() {
        synchronized(lock) {
            check(state == State.NEW) { "This car transport has already been used" }
            state = State.CONNECTING
        }
        io(timeouts.connectMs) {
            // Cancellation/deadline can win while createSocket is in flight. Publish only if live.
            val candidate = factory.createSocket()
            val accepted = synchronized(lock) {
                if (state == State.CONNECTING) { socket = candidate; true } else false
            }
            if (!accepted) { quietlyClose(candidate); throw IOException("Car connection was cancelled") }
            candidate.connect(InetSocketAddress(address, port), timeouts.connectMs.toInt())
            candidate.tcpNoDelay = true
            candidate.soTimeout = minOf(1000, timeouts.readMs.toInt())
            synchronized(lock) {
                if (state != State.CONNECTING) throw IOException("Car connection was cancelled")
                state = State.CONNECTED
            }
        }
    }

    override suspend fun read(buffer: ByteBuffer): Int {
        val connected = connectedSocket()
        if (!buffer.hasRemaining()) return 0
        if (!reader.compareAndSet(false, true)) throw IOException("A car read is already pending")
        val readSize = minOf(buffer.remaining(), MAX_IO_BYTES)
        try {
            val result = io(timeouts.readMs, { value: Pair<Int, ByteArray> -> value.second.fill(0) }) {
                val bytes = ByteArray(readSize)
                try {
                    val count = try { connected.getInputStream().read(bytes) } catch (_: SocketTimeoutException) { 0 }
                    Pair(count, bytes)
                } catch (failure: Exception) { bytes.fill(0); throw failure }
            }
            try {
                if (result.first < 0) close()
                if (result.first > 0) buffer.put(result.second, 0, result.first)
                return result.first
            }
            finally { result.second.fill(0) }
        } finally { reader.set(false) }
    }

    override suspend fun write(buffer: ByteBuffer) {
        val connected = connectedSocket()
        if (!buffer.hasRemaining()) return
        if (buffer.remaining() > MAX_IO_BYTES) throw IOException("Car write exceeds the bounded frame size")
        if (!writer.compareAndSet(false, true)) throw IOException("A car write is already pending")
        val bytes = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        try {
            io(timeouts.writeMs) {
                try { connected.getOutputStream().apply { write(bytes); flush() } }
                finally { bytes.fill(0) }
            }
            buffer.position(buffer.position() + bytes.size)
        } finally { bytes.fill(0); writer.set(false) }
    }

    private fun connectedSocket(): Socket = synchronized(lock) {
        if (state != State.CONNECTED) throw IOException("Car transport is not connected")
        checkNotNull(socket)
    }
    override suspend fun disconnect() = close()
    override fun close() = close(IOException("Car transport closed"))
    private fun close(reason: Exception) {
        val oldSocket: Socket?
        val pending: List<Operation<*>>
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED; oldSocket = socket; socket = null
            pending = operations.toList(); operations.clear()
        }
        quietlyClose(oldSocket)
        pending.forEach { it.fail(reason) }
    }

    private suspend fun <T> io(timeoutMs: Long, discard: (T) -> Unit = {}, action: () -> T): T = suspendCancellableCoroutine { continuation ->
        val operation = Operation<T>({ value ->
            continuation.resume(value, onCancellation = { _, abandoned, _ -> discard(abandoned) })
        }, { continuation.resumeWithException(it) })
        val accepted = synchronized(lock) {
            if (state == State.CLOSED) false else { operations.add(operation); true }
        }
        if (!accepted) { operation.fail(IOException("Car transport closed")); return@suspendCancellableCoroutine }
        continuation.invokeOnCancellation { operation.cancel(); close() }
        try {
            operation.timer(scheduler.schedule(timeoutMs) {
                // A cancelled timer may already be queued/running; an old deadline cannot close a later operation.
                val failure = SocketTimeoutException("Car I/O deadline exceeded")
                if (operation.fail(failure)) close(failure)
            })
            if (!operation.done.get()) worker.execute {
                if (!operation.done.get()) try { val result = action(); if (!operation.succeed(result)) discard(result) }
                catch (failure: Exception) { close(failure) }
            }
        } catch (failure: Exception) { close(failure) }
    }

    private inner class Operation<T>(private val success: (T) -> Unit, private val failure: (Exception) -> Unit) {
        val done = AtomicBoolean()
        private val timeout = AtomicReference<Ticket?>()
        fun timer(ticket: Ticket) { timeout.set(ticket); if (done.get()) timeout.getAndSet(null)?.cancel() }
        private fun settle(): Boolean {
            if (!done.compareAndSet(false, true)) return false
            timeout.getAndSet(null)?.cancel()
            synchronized(lock) { operations.remove(this) }
            return true
        }
        fun succeed(value: T): Boolean { if (!settle()) return false; success(value); return true }
        fun fail(error: Exception): Boolean { if (!settle()) return false; failure(error); return true }
        fun cancel() { settle() }
    }

    companion object {
        private const val MAX_IO_BYTES = 131_072
        private fun quietlyClose(socket: Socket?) { try { socket?.close() } catch (_: Exception) {} }
        private val timer = ScheduledThreadPoolExecutor(1) { job ->
            Thread(job, "OpenAuto-CarNetworkDeadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        internal val deadlines = Scheduler { milliseconds, action ->
            val future = timer.schedule(action, milliseconds, TimeUnit.MILLISECONDS)
            Ticket { future.cancel(false) }
        }
    }
}
