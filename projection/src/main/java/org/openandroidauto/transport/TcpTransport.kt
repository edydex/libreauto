package org.openandroidauto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * TCP transport for wireless Android Auto or testing.
 */
class TcpTransport(private val host: String, private val port: Int) : Transport {
    private val resources = SessionResourceGate()
    @Volatile private var channel: SocketChannel? = null

    @Volatile override var isConnected: Boolean = false
        private set

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val ch = SocketChannel.open()
        if (!resources.publish(ch)) throw IOException("Connection was cancelled")
        // Publish the actual channel BEFORE blocking connect, so disconnect can close it.
        if (!resources.whileOpen { channel = ch }) throw IOException("Connection was cancelled")
        ch.connect(InetSocketAddress(host, port))
        ch.configureBlocking(true)
        if (!resources.whileOpen { isConnected = true }) throw IOException("Connection was cancelled")
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        val closed = resources.close()
        isConnected = false
        channel = null
        if (!closed) throw IOException("Transport resource cleanup failed")
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        val ch = channel ?: throw IOException("Not connected")
        val read = ch.read(buffer)
        if (read < 0) { isConnected = false }
        read
    }

    override suspend fun write(buffer: ByteBuffer) = withContext(Dispatchers.IO) {
        val ch = channel ?: throw IOException("Not connected")
        while (buffer.hasRemaining()) {
            ch.write(buffer)
        }
    }
}
