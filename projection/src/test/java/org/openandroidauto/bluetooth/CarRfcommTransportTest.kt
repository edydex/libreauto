package org.openandroidauto.bluetooth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real bounded worker/cancellation tests with fake streams; no Bluetooth radio or Android services. */
class CarRfcommTransportTest {
    /** A real blocking stream: available() stays zero, as it can for both BT idle and EOF. */
    private class PendingInput : InputStream() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val reads = AtomicInteger()
        @Volatile var closed = false
        @Volatile var bytes: ByteArray? = null
        fun offer(value: ByteArray?) { bytes = value; released.countDown() }
        override fun available() = 0
        override fun read(): Int = error("The bounded bulk read must be used")
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet(); entered.countDown()
            try {
                released.await()
                if (closed) throw IOException("Synthetic peer closed")
                val value = bytes ?: return -1
                val count = minOf(value.size, length)
                value.copyInto(target, offset, 0, count)
                return count
            } finally { finished.countDown() }
        }
        override fun close() { closed = true; released.countDown() }
    }

    private class Peer : CarRfcommTransport.Peer {
        val connects = AtomicInteger()
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var connectBlocks = false
        var afterConnect: () -> Unit = {}
        var source: InputStream = ByteArrayInputStream(byteArrayOf())
        var target: OutputStream = ByteArrayOutputStream()
        var onClose: () -> Unit = {}
        override fun connect() {
            connects.incrementAndGet(); entered.countDown()
            if (connectBlocks) released.await()
            afterConnect()
        }
        override val input: InputStream get() = source
        override val output: OutputStream get() = target
        override fun close() { closes.incrementAndGet(); released.countDown(); onClose() }
    }

    @Test fun onlyValidSelectedBluetoothAddressesAreAccepted() {
        assertEquals("02:AB:00:00:10:01", PairedCarAddress.normalize("02:ab:00:00:10:01"))
        for (bad in listOf("", "192.0.2.1", "02:00:00:00:10:01:7", " 02:00:00:00:10:01", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) {
            try { PairedCarAddress.normalize(bad); fail("Invalid selection accepted") }
            catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains(bad.takeIf { it.isNotEmpty() } ?: "not-present")) }
        }
    }

    @Test fun actualStreamDataAndBufferPositionsArePreserved() = runBlocking {
        val peer = Peer().apply { source = ByteArrayInputStream(byteArrayOf(3, 4, 5)) }
        val transport = CarRfcommTransport({ peer })
        transport.connect(); assertTrue(transport.isConnected)
        val input = ByteBuffer.allocate(8).apply { position(2) }
        assertEquals(3, transport.read(input)); assertEquals(5, input.position())
        assertArrayEquals(byteArrayOf(3, 4, 5), input.array().copyOfRange(2, 5))
        val output = ByteBuffer.wrap(ByteArray(40_000) { (it % 127).toByte() }).apply { position(3) }
        val expected = output.array().copyOfRange(3, output.limit())
        transport.write(output)
        assertEquals(output.limit(), output.position())
        assertArrayEquals(expected, (peer.target as ByteArrayOutputStream).toByteArray())
        transport.disconnect(); transport.disconnect()
        assertEquals(1, peer.closes.get()); assertFalse(transport.isConnected)
    }

    @Test fun connectDeadlineClosesTheActualInflightPeer() = runBlocking {
        val peer = Peer().apply { connectBlocks = true }
        val transport = CarRfcommTransport({ peer }, connectTimeoutMs = 50)
        try { withTimeout(2_000) { transport.connect() }; fail("Deadline expected") }
        catch (_: SocketTimeoutException) { }
        assertEquals(1, peer.closes.get()); assertFalse(transport.isConnected)
    }

    @Test fun cancellingConnectClosesItsSocketAndNeverPublishesIt() = runBlocking {
        val peer = Peer().apply { connectBlocks = true }
        val transport = CarRfcommTransport({ peer })
        val job = launch(Dispatchers.Default) { transport.connect() }
        assertTrue(peer.entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertEquals(1, peer.closes.get()); assertFalse(transport.isConnected)
        transport.disconnect(); assertEquals(1, peer.closes.get())
    }

    @Test fun socketCreatedAfterCancellationIsClosedWithoutConnecting() = runBlocking {
        val factoryEntered = CountDownLatch(1); val releaseFactory = CountDownLatch(1); val factoryDone = CountDownLatch(1)
        val peer = Peer()
        val transport = CarRfcommTransport({
            factoryEntered.countDown()
            while (releaseFactory.count > 0) try { releaseFactory.await() } catch (_: InterruptedException) { }
            peer.also { factoryDone.countDown() }
        })
        val job = launch(Dispatchers.Default) { transport.connect() }
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin(); releaseFactory.countDown()
        assertTrue(factoryDone.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { while (peer.closes.get() == 0) delay(5) }
        assertEquals(0, peer.connects.get()); assertEquals(1, peer.closes.get()); assertFalse(transport.isConnected)
    }

    @Test fun idleReadReturnsZeroWithoutEndingWifiConsentOrKeepalive() = runBlocking {
        val input = PendingInput()
        val peer = Peer().apply { source = input; onClose = { input.close() } }
        val transport = CarRfcommTransport({ peer }, readTimeoutMs = 10)
        transport.connect()
        repeat(6) { assertEquals(0, transport.read(ByteBuffer.allocate(8))) }
        assertEquals("Idle callers must retain one socket read", 1, input.reads.get())
        assertTrue(transport.isConnected); assertEquals(0, peer.closes.get())
        transport.disconnect()
        assertTrue(input.finished.await(2, TimeUnit.SECONDS))
    }

    @Test fun dataAfterAnIdleDeadlineUsesOnlyTheNextCallersBuffer() = runBlocking {
        val input = PendingInput()
        val peer = Peer().apply { source = input; onClose = { input.close() } }
        val transport = CarRfcommTransport({ peer }, readTimeoutMs = 25)
        transport.connect()
        val oldBuffer = ByteBuffer.allocate(4)
        assertEquals(0, withTimeout(2_000) { transport.read(oldBuffer) })
        input.offer(byteArrayOf(7, 8, 9))
        assertTrue(input.finished.await(2, TimeUnit.SECONDS))
        val nextBuffer = ByteBuffer.allocate(4)
        assertEquals(3, transport.read(nextBuffer))
        assertEquals(0, oldBuffer.position()); assertArrayEquals(ByteArray(4), oldBuffer.array())
        assertArrayEquals(byteArrayOf(7, 8, 9), nextBuffer.array().copyOf(3))
        assertEquals(1, input.reads.get()); assertTrue(transport.isConnected)
        transport.disconnect()
    }

    @Test fun cancellingAnIdleReadClosesItsSocketImmediately() = runBlocking {
        val input = PendingInput()
        val peer = Peer().apply { source = input; onClose = { input.close() } }
        val transport = CarRfcommTransport({ peer })
        transport.connect()
        val job = launch(Dispatchers.Default) { transport.read(ByteBuffer.allocate(4)) }
        assertTrue(input.entered.await(2, TimeUnit.SECONDS)); job.cancelAndJoin()
        assertEquals(1, peer.closes.get()); assertFalse(transport.isConnected)
        assertTrue(input.finished.await(2, TimeUnit.SECONDS))
    }

    @Test fun oneRetainedChunkIsConsumedBeforeAnotherSocketRead() = runBlocking {
        val input = PendingInput().apply { offer(byteArrayOf(1, 2, 3, 4, 5)) }
        val peer = Peer().apply { source = input; onClose = { input.close() } }
        val transport = CarRfcommTransport({ peer })
        transport.connect()
        val first = ByteBuffer.allocate(2); val second = ByteBuffer.allocate(3)
        assertEquals(2, transport.read(first)); assertEquals(3, transport.read(second))
        assertArrayEquals(byteArrayOf(1, 2), first.array())
        assertArrayEquals(byteArrayOf(3, 4, 5), second.array())
        assertEquals(1, input.reads.get()); transport.disconnect()
    }

    @Test fun remoteEofAfterIdleClosesAndIsRetainedForTheNextCaller() = runBlocking {
        val input = PendingInput()
        val peer = Peer().apply { source = input; onClose = { input.close() } }
        val transport = CarRfcommTransport({ peer }, readTimeoutMs = 10)
        transport.connect(); assertEquals(0, transport.read(ByteBuffer.allocate(4)))
        input.offer(null)
        withTimeout(2_000) { while (transport.isConnected) delay(1) }
        assertEquals(-1, transport.read(ByteBuffer.allocate(4)))
        assertEquals(1, peer.closes.get()); assertEquals(1, input.reads.get())
    }

    @Test fun disconnectCompletesAWaitingCallerAndClosesItsPendingRead() = runBlocking {
        val input = PendingInput()
        val peer = Peer().apply { source = input; onClose = { input.close() } }
        val transport = CarRfcommTransport({ peer })
        transport.connect()
        val buffer = ByteBuffer.allocate(4)
        val first = async(Dispatchers.Default) {
            try { transport.read(buffer); false } catch (_: IOException) { true }
        }
        assertTrue(input.entered.await(2, TimeUnit.SECONDS))
        transport.disconnect()
        assertTrue(withTimeout(2_000) { first.await() })
        assertEquals(0, buffer.position()); assertEquals(1, peer.closes.get())
        assertTrue(input.finished.await(2, TimeUnit.SECONDS))
    }

    @Test fun stalledWriteHasABoundedDeadlineAndDoesNotConsumeCallerBuffer() = runBlocking {
        val unblock = CountDownLatch(1)
        val peer = Peer().apply {
            target = object : OutputStream() { override fun write(b: Int) { unblock.await() } }
            onClose = { unblock.countDown() }
        }
        val transport = CarRfcommTransport({ peer }, writeTimeoutMs = 50)
        transport.connect()
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        try { withTimeout(2_000) { transport.write(buffer) }; fail("Deadline expected") }
        catch (_: SocketTimeoutException) { }
        assertEquals(0, buffer.position()); assertEquals(1, peer.closes.get()); assertFalse(transport.isConnected)
    }

    @Test fun overlappingReadsAreRejectedWithoutAQueueOfWaitingFrames() = runBlocking {
        val input = PendingInput()
        val peer = Peer().apply { source = input; onClose = { input.close() } }
        val transport = CarRfcommTransport({ peer })
        transport.connect()
        val first = launch(Dispatchers.Default) { transport.read(ByteBuffer.allocate(4)) }
        assertTrue(input.entered.await(2, TimeUnit.SECONDS))
        try { transport.read(ByteBuffer.allocate(4)); fail("Concurrent read accepted") } catch (_: IOException) { }
        assertTrue(transport.isConnected)
        first.cancelAndJoin(); assertEquals(1, peer.closes.get())
    }

    @Test fun eofReleasesTheConnectionAndAnInstanceCannotReconnect() = runBlocking {
        val peer = Peer().apply {
            source = object : InputStream() { override fun available() = 0; override fun read() = -1 }
        }
        val transport = CarRfcommTransport({ peer })
        transport.connect(); assertEquals(-1, transport.read(ByteBuffer.allocate(4)))
        assertFalse(transport.isConnected); assertEquals(1, peer.closes.get())
        try { transport.connect(); fail("Closed instance reconnected") } catch (_: IOException) { }
        assertEquals(1, peer.connects.get())
    }

    @Test fun vendorFailureDetailsDoNotBecomePublicStatusText() = runBlocking {
        val peer = Peer().apply { afterConnect = { throw IOException("synthetic-private-device-detail") } }
        val transport = CarRfcommTransport({ peer })
        try { transport.connect(); fail("Connect failure expected") }
        catch (error: IOException) { assertFalse(error.message.orEmpty().contains("synthetic-private-device-detail")) }
        assertFalse(transport.isConnected); assertEquals(1, peer.closes.get())
    }
}
