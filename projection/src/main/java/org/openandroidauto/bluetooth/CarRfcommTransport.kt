package org.openandroidauto.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import org.openandroidauto.transport.Transport
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One explicit, paired-car secure RFCOMM connection. A disconnected instance is not reused. */
class CarRfcommTransport internal constructor(
    private val createPeer: () -> Peer,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS
) : Transport {
    constructor(context: Context, address: String) : this(androidPeerFactory(context.applicationContext, address))

    internal interface Peer : Closeable {
        fun connect()
        val input: InputStream
        val output: OutputStream
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val READ_TIMEOUT_MS = 1_000L
        const val WRITE_TIMEOUT_MS = 10_000L
        private const val CHUNK_BYTES = 16_384
        private val threads = ThreadFactory { task -> Thread(task, "OpenAuto-CarBluetooth").apply { isDaemon = true } }
        private val deadlines = ScheduledThreadPoolExecutor(1, ThreadFactory { task ->
            Thread(task, "OpenAuto-CarBluetoothDeadline").apply { isDaemon = true }
        }).apply { removeOnCancelPolicy = true }

        private fun androidPeerFactory(context: Context, selected: String): () -> Peer {
            val address = PairedCarAddress.normalize(selected)
            return {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    throw IOException("Allow Nearby devices access before connecting to the car")
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: throw IOException("Bluetooth is unavailable on this phone")
                if (!adapter.isEnabled) throw IOException("Turn on Bluetooth in phone settings before connecting")
                // Only the actual paired-device set is eligible. Never look up an arbitrary remote address.
                val device = adapter.bondedDevices.singleOrNull { it.address.equals(address, ignoreCase = true) }
                    ?: throw IOException("Pair and select the car in phone settings before connecting")
                if (device.bondState != BluetoothDevice.BOND_BONDED)
                    throw IOException("The selected car is no longer paired")
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                object : Peer {
                    override fun connect() {
                        // The earlier paired-device check can precede a runtime permission revoke.
                        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                            throw IOException("Allow Nearby devices access before connecting to the car")
                        try { socket.connect() }
                        catch (_: SecurityException) {
                            // The owning operation closes this published socket on every failure.
                            throw IOException("Nearby devices access was revoked before connecting")
                        }
                    }
                    override val input: InputStream get() = socket.inputStream
                    override val output: OutputStream get() = socket.outputStream
                    override fun close() = socket.close()
                }
            }
        }
    }

    private val lock = Any()
    private val started = AtomicBoolean()
    private val reading = AtomicBoolean()
    private val writing = AtomicBoolean()
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2), threads)
        .apply { allowCoreThreadTimeOut(true) }
    private var peer: Peer? = null
    private var closed = false
    private var pendingRead = false
    private var readWaiter: ReadRequest? = null
    private var readChunk: ByteArray? = null
    private var chunkOffset = 0
    private var chunkCount = 0
    @Volatile private var readEof = false
    @Volatile override var isConnected = false; private set

    private class ReadRequest(val buffer: ByteBuffer, val continuation: CancellableContinuation<Int>) {
        val done = AtomicBoolean()
        val timer = AtomicReference<ScheduledFuture<*>?>()
    }

    override suspend fun connect() {
        if (!started.compareAndSet(false, true)) throw IOException("Start a new car connection before reconnecting")
        operation(connectTimeoutMs, "Car Bluetooth connection timed out") {
            val candidate = createPeer()
            synchronized(lock) {
                if (closed) { quietlyClose(candidate); throw IOException("Car Bluetooth connection was stopped") }
                peer = candidate // Publish before connect so cancellation closes the actual in-flight socket.
            }
            candidate.connect()
            synchronized(lock) {
                ensureOpen()
                isConnected = true
            }
        }
    }

    override suspend fun disconnect() { closeConnection() }

    override suspend fun read(buffer: ByteBuffer): Int {
        if (!buffer.hasRemaining()) return 0
        require(!buffer.isReadOnly) { "Car Bluetooth needs a writable read buffer" }
        if (!reading.compareAndSet(false, true)) throw IOException("A car Bluetooth read is already pending")
        return try {
            suspendCancellableCoroutine { continuation ->
                val request = ReadRequest(buffer, continuation)
                continuation.invokeOnCancellation {
                    request.done.set(true); request.timer.get()?.cancel(false)
                    closeConnection()
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                try {
                    val deliver = synchronized(lock) {
                        if (readEof) reserveReadLocked(request, -1)
                        else {
                            connectedPeer()
                            readWaiter = request
                            if (readChunk != null) consumeReadLocked(request)
                            else { startReadLocked(); null }
                        }
                    }
                    deliver?.invoke()
                    val timer = deadlines.schedule({
                        val idle = synchronized(lock) {
                            if (readWaiter === request) reserveReadLocked(request, 0) else null
                        }
                        idle?.invoke() // Keep the one pending socket read alive across idle callers.
                    }, readTimeoutMs, TimeUnit.MILLISECONDS)
                    request.timer.set(timer)
                    if (request.done.get()) timer.cancel(false)
                } catch (error: Exception) {
                    if (request.done.compareAndSet(false, true)) {
                        closeConnection()
                        continuation.resumeWithException(IOException("Car Bluetooth connection could not continue", error))
                    }
                }
            }
        } finally { reading.set(false) }
    }

    /** One blocking read, one retained 16KB chunk, one current waiter; idle calls add no callbacks/jobs. */
    private fun startReadLocked() {
        if (pendingRead || readChunk != null || readEof) return
        val socket = connectedPeer()
        pendingRead = true
        workers.execute {
            val bytes = ByteArray(CHUNK_BYTES)
            try {
                val count = socket.input.read(bytes)
                val deliver = synchronized(lock) {
                    pendingRead = false
                    if (closed) { bytes.fill(0); return@execute }
                    when {
                        count < 0 -> { readEof = true; bytes.fill(0); readWaiter?.let { reserveReadLocked(it, -1) } }
                        count == 0 -> { bytes.fill(0); readWaiter?.let { reserveReadLocked(it, 0) } }
                        else -> {
                            readChunk = bytes; chunkOffset = 0; chunkCount = count
                            readWaiter?.let { consumeReadLocked(it) }
                        }
                    }
                }
                if (count < 0) closeConnection()
                deliver?.invoke()
            } catch (_: Exception) {
                bytes.fill(0)
                closeConnection()
            }
        }
    }

    /** Caller buffer is touched only while its current request is live and the connection lock is held. */
    private fun consumeReadLocked(request: ReadRequest): (() -> Unit)? {
        val chunk = readChunk ?: return null
        if (!request.done.compareAndSet(false, true)) return null
        readWaiter = null; request.timer.get()?.cancel(false)
        val count = minOf(request.buffer.remaining(), chunkCount - chunkOffset)
        return try {
            request.buffer.put(chunk, chunkOffset, count)
            chunk.fill(0, chunkOffset, chunkOffset + count)
            chunkOffset += count
            if (chunkOffset == chunkCount) { readChunk = null; chunkOffset = 0; chunkCount = 0 }
            val delivery: () -> Unit = { request.continuation.resume(count) }
            delivery
        } catch (error: Exception) {
            { closeConnection(); request.continuation.resumeWithException(IOException("Car Bluetooth read could not complete", error)) }
        }
    }

    private fun reserveReadLocked(request: ReadRequest, count: Int): (() -> Unit)? {
        if (!request.done.compareAndSet(false, true)) return null
        if (readWaiter === request) readWaiter = null
        request.timer.get()?.cancel(false)
        return { request.continuation.resume(count) }
    }

    override suspend fun write(buffer: ByteBuffer) {
        if (!buffer.hasRemaining()) return
        if (!writing.compareAndSet(false, true)) throw IOException("A car Bluetooth write is already pending")
        try {
            operation(writeTimeoutMs, "The car did not accept Bluetooth data in time") {
                val socket = connectedPeer()
                val copy = buffer.duplicate()
                val bytes = ByteArray(minOf(CHUNK_BYTES, copy.remaining()))
                try {
                    while (copy.hasRemaining()) {
                        val count = minOf(bytes.size, copy.remaining())
                        synchronized(lock) { ensureOpen(); copy.get(bytes, 0, count) }
                        socket.output.write(bytes, 0, count)
                    }
                    socket.output.flush()
                    synchronized(lock) { ensureOpen(); buffer.position(copy.position()) }
                } finally { bytes.fill(0) }
            }
        } finally { writing.set(false) }
    }

    private fun connectedPeer(): Peer = synchronized(lock) {
        ensureOpen()
        if (!isConnected) throw IOException("Car Bluetooth is not connected")
        requireNotNull(peer)
    }
    private fun ensureOpen() { if (closed) throw IOException("Car Bluetooth connection was stopped") }

    private fun closeConnection() {
        val (socket, waiter) = synchronized(lock) {
            if (closed) return
            closed = true; isConnected = false
            readChunk?.fill(0); readChunk = null; chunkCount = 0; chunkOffset = 0
            val waiting = readWaiter.also { readWaiter = null }
            peer.also { peer = null } to waiting
        }
        quietlyClose(socket)
        workers.shutdownNow()
        if (waiter?.done?.compareAndSet(false, true) == true) {
            waiter.timer.get()?.cancel(false)
            waiter.continuation.resumeWithException(IOException("Car Bluetooth connection was stopped"))
        }
    }
    private fun quietlyClose(socket: Peer?) { try { socket?.close() } catch (_: Exception) { } }

    private suspend fun <T> operation(timeoutMs: Long, timeoutMessage: String, action: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean()
            val timer = AtomicReference<ScheduledFuture<*>?>()
            val worker = AtomicReference<Future<*>?>()
            continuation.invokeOnCancellation {
                done.set(true)
                timer.get()?.cancel(false)
                closeConnection()
                worker.get()?.cancel(true)
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val deadline = deadlines.schedule({
                if (done.compareAndSet(false, true)) {
                    closeConnection()
                    continuation.resumeWithException(SocketTimeoutException(timeoutMessage))
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
            timer.set(deadline)
            if (done.get()) deadline.cancel(false)
            try {
                val task = workers.submit {
                    try {
                        synchronized(lock) { ensureOpen() }
                        val result = action()
                        if (done.compareAndSet(false, true)) {
                            deadline.cancel(false)
                            continuation.resume(result)
                        }
                    } catch (error: Exception) {
                        if (done.compareAndSet(false, true)) {
                            deadline.cancel(false)
                            closeConnection()
                            // Vendor exception messages may contain a device address. Expose only fixed status text.
                            continuation.resumeWithException(IOException("Car Bluetooth connection could not continue", error))
                        }
                    }
                }
                worker.set(task)
                if (done.get()) task.cancel(true)
            } catch (error: Exception) {
                if (done.compareAndSet(false, true)) {
                    deadline.cancel(false); closeConnection()
                    continuation.resumeWithException(IOException("Car Bluetooth connection was stopped", error))
                }
            }
        }
}

internal object PairedCarAddress {
    fun normalize(value: String): String {
        require(value.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))) { "Choose a paired car before connecting" }
        val normalized = value.uppercase(Locale.ROOT)
        require(normalized != "00:00:00:00:00:00" && normalized != "FF:FF:FF:FF:FF:FF") { "Choose a paired car before connecting" }
        return normalized
    }
}
