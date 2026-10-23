package org.openandroidauto.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * TCP server transport for Desktop Head Unit (DHU) testing.
 * Listens on port 5277 and accepts one connection.
 */
class TcpServerTransport(private val port: Int = 5277) : Transport {
    private val resources = SessionResourceGate()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null

    @Volatile override var isConnected: Boolean = false
        private set

    override suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        Log.w("AATcpServer", "Listening on port $port for DHU connection...")
        val server = ServerSocket()
        if (!resources.publish(server)) throw IOException("Connection was cancelled")
        if (!resources.whileOpen { serverSocket = server }) throw IOException("Connection was cancelled")
        server.reuseAddress = true
        server.bind(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 1)
        val client = server.accept()
        if (!resources.publish(client)) throw IOException("Connection was cancelled")
        client.soTimeout = 0 // 10 second read timeout
        client.tcpNoDelay = true
        val input = client.getInputStream()
        val output = client.getOutputStream()
        if (!resources.whileOpen {
            clientSocket = client; inputStream = input; outputStream = output; isConnected = true
        }) throw IOException("Connection was cancelled")
        // Wait briefly for initial data to arrive through ADB tunnel
        Thread.sleep(500)
        if (!resources.isOpen) throw IOException("Connection was cancelled")
        Log.w("AATcpServer", "DHU connected from ${client.remoteSocketAddress}")
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        val closed = resources.close()
        isConnected = false
        inputStream = null
        outputStream = null
        clientSocket = null
        serverSocket = null
        if (!closed) throw IOException("Transport resource cleanup failed")
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        val stream = inputStream ?: throw IOException("Not connected")
        val maxRead = minOf(buffer.remaining(), 16384)
        val arr = ByteArray(maxRead)
        try {
            val read = stream.read(arr, 0, maxRead)
            if (read > 0) {
                buffer.put(arr, 0, read)
            } else if (read < 0) {
                Log.w("AATcpServer", "read returned -1 (EOF)")
                isConnected = false
            }
            read
        } catch (e: java.net.SocketTimeoutException) {
            0 // timeout, no data yet
        }
    }

    override suspend fun write(buffer: ByteBuffer) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IOException("Not connected")
        val arr = ByteArray(buffer.remaining())
        buffer.get(arr)
        stream.write(arr)
        stream.flush()
    }
}
