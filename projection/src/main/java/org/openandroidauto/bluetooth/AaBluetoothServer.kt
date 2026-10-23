package org.openandroidauto.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Bluetooth RFCOMM server for Android Auto wireless discovery.
 * Head unit connects via BT, requests WiFi connection details, then connects over TCP.
 */
class AaBluetoothServer(
    private val wifiIp: String,
    private val wifiPort: Int = 5277
) {
    companion object {
        private const val TAG = "AABluetooth"
        val AA_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private const val SERVICE_NAME = "AndroidAuto"

        // Bluetooth.proto MessageId values
        const val SOCKET_INFO_REQUEST = 1
        const val NETWORK_INFO_REQUEST = 2
        const val NETWORK_INFO_MESSAGE = 3
        const val SOCKET_INFO_RESPONSE = 7
    }

    private val ownerLock = Any()
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var generation = 0L
    private var running = false

    interface Callback {
        fun onHeadUnitConnected(address: String)
        fun onWifiHandoffReady()
    }

    fun start(adapter: BluetoothAdapter, callback: Callback) {
        val owner = synchronized(ownerLock) {
            if (running) return
            running = true
            ++generation
        }
        Thread {
            var listener: BluetoothServerSocket? = null
            try {
                // No Context is owned here. Handle revocable CONNECT access at this exact API call.
                val created = try { adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, AA_UUID) }
                    catch (_: SecurityException) {
                        Log.w(TAG, "Nearby devices access is required for the RFCOMM server")
                        return@Thread
                    }
                listener = created
                val published = synchronized(ownerLock) {
                    if (running && generation == owner) { serverSocket = created; true } else false
                }
                if (!published) return@Thread // Stop may have won while the listener was being created.
                Log.i(TAG, "RFCOMM server listening")

                while (isRunning(owner)) {
                    val socket = created.accept()
                    val accepted = synchronized(ownerLock) {
                        if (running && generation == owner) { clientSocket = socket; true } else false
                    }
                    if (!accepted) { closeQuietly(socket); break }
                    try { handleConnection(socket, callback, owner) }
                    finally { synchronized(ownerLock) { if (clientSocket === socket) clientSocket = null } }
                }
            } catch (_: SecurityException) {
                if (isRunning(owner)) Log.w(TAG, "Nearby devices access was revoked")
            } catch (_: IOException) {
                if (isRunning(owner)) Log.e(TAG, "RFCOMM server connection ended")
            } finally {
                closeQuietly(listener)
                synchronized(ownerLock) {
                    if (generation == owner) { running = false; serverSocket = null; clientSocket = null }
                }
            }
        }.apply {
            name = "AA-Bluetooth"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val sockets = synchronized(ownerLock) {
            running = false
            ++generation
            val owned = clientSocket to serverSocket
            clientSocket = null; serverSocket = null
            owned
        }
        closeQuietly(sockets.first)
        closeQuietly(sockets.second)
    }

    private fun isRunning(owner: Long): Boolean = synchronized(ownerLock) { running && generation == owner }
    private fun closeQuietly(socket: Closeable?) {
        try { socket?.close() } catch (_: IOException) { } catch (_: SecurityException) { }
    }

    private fun handleConnection(socket: BluetoothSocket, callback: Callback, owner: Long) {
        try {
            if (!isRunning(owner)) return
            callback.onHeadUnitConnected(socket.remoteDevice.address)
            val input = socket.inputStream
            val output = socket.outputStream
            while (isRunning(owner)) {
                // Read message: 2-byte length + payload
                val lenBuf = ByteArray(2)
                if (input.read(lenBuf) != 2) break
                val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

                val payload = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(payload, read, len - read)
                    if (n < 0) break
                    read += n
                }

                // Parse message ID from protobuf (first varint after tag)
                val messageId = parseMessageId(payload)

                when (messageId) {
                    SOCKET_INFO_REQUEST -> {
                        val response = buildSocketInfoResponse()
                        output.write(response)
                        output.flush()
                        callback.onWifiHandoffReady()
                    }
                    NETWORK_INFO_REQUEST -> {
                        val response = buildNetworkInfoResponse()
                        output.write(response)
                        output.flush()
                    }
                }
            }
        } catch (_: SecurityException) {
            Log.d(TAG, "Nearby devices access was revoked")
        } catch (_: IOException) {
            Log.d(TAG, "BT connection closed")
        } finally {
            closeQuietly(socket)
        }
    }

    /**
     * Build SocketInfoResponse: ip_address + port + status=SUCCESS
     */
    private fun buildSocketInfoResponse(): ByteArray {
        val ipBytes = wifiIp.toByteArray()
        // Protobuf: field 1 (ip) string, field 2 (port) varint, field 3 (status) varint=0 (SUCCESS)
        val proto = ByteBuffer.allocate(4 + ipBytes.size + 6)
            .put(0x0A.toByte()).put(ipBytes.size.toByte()).put(ipBytes) // field 1: ip
            .put(0x10.toByte()).put(encodeVarint(wifiPort)) // field 2: port
            .put(0x18.toByte()).put(0x00.toByte()) // field 3: status = SUCCESS(0)
        val protoBytes = proto.array().copyOf(proto.position())

        // Wrap with 2-byte length prefix
        val msg = ByteBuffer.allocate(2 + protoBytes.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(protoBytes.size.toShort())
            .put(protoBytes)
        return msg.array()
    }

    private fun buildNetworkInfoResponse(): ByteArray {
        // Minimal response - empty for now
        val proto = byteArrayOf()
        val msg = ByteBuffer.allocate(2 + proto.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(proto.size.toShort())
            .put(proto)
        return msg.array()
    }

    private fun parseMessageId(data: ByteArray): Int {
        // Simple: look for the message ID pattern in the protobuf
        // The BT messages use a simple enum-based ID system
        if (data.isEmpty()) return 0
        // First byte is typically the field tag, second is the value
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF
            val field = tag ushr 3
            i++
            if (i >= data.size) break
            val value = data[i].toInt() and 0x7F
            i++
            if (field == 1) return value // message_id is typically field 1
        }
        return data[0].toInt() and 0xFF
    }

    private fun encodeVarint(value: Int): ByteArray {
        if (value < 128) return byteArrayOf(value.toByte())
        return byteArrayOf(
            ((value and 0x7F) or 0x80).toByte(),
            (value ushr 7).toByte()
        )
    }
}
