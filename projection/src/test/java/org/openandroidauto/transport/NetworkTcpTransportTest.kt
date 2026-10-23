package org.openandroidauto.transport

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.*
import javax.net.SocketFactory
import kotlin.coroutines.CoroutineContext

class NetworkTcpTransportTest {
    private class Clock : NetworkTcpTransport.Scheduler {
        val tasks = mutableListOf<Pair<() -> Unit, BooleanArray>>()
        override fun schedule(milliseconds: Long, action: () -> Unit): NetworkTcpTransport.Ticket {
            val cancelled = booleanArrayOf(false); tasks += action to cancelled
            return NetworkTcpTransport.Ticket { cancelled[0] = true }
        }
        fun fire() { tasks.toList().forEach { if (!it.second[0]) it.first() } }
        fun forceOldTimers() { tasks.toList().forEach { it.first() } }
    }
    private class FakeSocket : Socket() {
        var closedCount = 0
        var destination: InetSocketAddress? = null
        var connectBlocked = false
        var outputBlocked = false
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var input: InputStream = ByteArrayInputStream(byteArrayOf(7, 8, 9))
        val output = ByteArrayOutputStream()
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            destination = endpoint as InetSocketAddress
            if (connectBlocked) { entered.countDown(); check(released.await(3, TimeUnit.SECONDS)); throw SocketException("Synthetic close") }
        }
        override fun setTcpNoDelay(on: Boolean) {}
        override fun setSoTimeout(timeout: Int) {}
        override fun getInputStream() = input
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (outputBlocked) { entered.countDown(); check(released.await(3, TimeUnit.SECONDS)); throw SocketException("Synthetic close") }
                output.write(bytes, offset, length)
            }
        }
        override fun close() { closedCount++; released.countDown() }
    }
    private class Factory(private val value: Socket, val beforeReturn: (() -> Unit)? = null) : SocketFactory() {
        var calls = 0
        override fun createSocket(): Socket { calls++; beforeReturn?.invoke(); return value }
        override fun createSocket(host: String?, port: Int): Socket = error("DNS/default path forbidden")
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = error("Forbidden")
        override fun createSocket(host: InetAddress?, port: Int): Socket = error("Forbidden")
        override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = error("Forbidden")
    }
    private fun transport(factory: SocketFactory, clock: Clock, worker: Executor = Executor { it.run() }) =
        NetworkTcpTransport(factory, byteArrayOf(192.toByte(),168.toByte(),77,1),5277,
            NetworkTcpTransport.Timeouts(1000,1000,1000),worker,clock)
    private fun pool() = Executors.newFixedThreadPool(2) { Thread(it,"Synthetic-CarIo").apply { isDaemon=true } }

    @Test fun suppliedFactoryLiteralAddressAndPartialBuffers() = runBlocking {
        val socket=FakeSocket();val factory=Factory(socket);val clock=Clock();val connection=transport(factory,clock)
        connection.connect();assertTrue(connection.isConnected);assertEquals(1,factory.calls)
        clock.forceOldTimers();assertTrue(connection.isConnected) // A cancelled deadline can race scheduler delivery.
        assertArrayEquals(byteArrayOf(192.toByte(),168.toByte(),77,1),socket.destination!!.address.address)
        val output=ByteBuffer.wrap(byteArrayOf(0,1,2,3));output.position(1);connection.write(output)
        assertArrayEquals(byteArrayOf(1,2,3),socket.output.toByteArray());assertEquals(4,output.position())
        val input=ByteBuffer.allocateDirect(8);assertEquals(3,connection.read(input));assertEquals(3,input.position())
        assertEquals(-1,connection.read(input));assertFalse(connection.isConnected)
        connection.close();assertEquals(1,socket.closedCount);clock.fire();assertEquals(1,socket.closedCount)
    }
    @Test fun idleReadTimeoutIsNotLoss() = runBlocking {
        val socket=FakeSocket().apply { input=object:InputStream(){override fun read():Int=throw SocketTimeoutException()} }
        val connection=transport(Factory(socket),Clock());connection.connect()
        assertEquals(0,connection.read(ByteBuffer.allocate(4)));assertTrue(connection.isConnected);connection.close()
    }
    @Test fun cancelledBeforeQueuedCreationNeverCallsFactory() = runBlocking {
        val queued=mutableListOf<Runnable>();val factory=Factory(FakeSocket());val connection=transport(factory,Clock(),Executor { queued+=it })
        val pending=launch(start=CoroutineStart.UNDISPATCHED) { connection.connect() }
        pending.cancelAndJoin();queued.forEach { it.run() };assertEquals(0,factory.calls);assertFalse(connection.isConnected)
    }
    @Test fun lateCreatedSocketIsClosedAfterCancellation() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val socket=FakeSocket();val executor=pool()
        val connection=transport(Factory(socket){entered.countDown();check(release.await(3,TimeUnit.SECONDS))},Clock(),executor)
        try {
            val pending=launch(start=CoroutineStart.UNDISPATCHED) { connection.connect() }
            assertTrue(entered.await(1,TimeUnit.SECONDS));withTimeout(1000){pending.cancelAndJoin()};release.countDown()
            executor.shutdown();assertTrue(executor.awaitTermination(1,TimeUnit.SECONDS));assertEquals(1,socket.closedCount)
        } finally {release.countDown();connection.close();executor.shutdownNow()}
    }
    @Test fun lateCreatedSocketIsClosedAfterDeadline() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val socket=FakeSocket();val executor=pool();val clock=Clock()
        val connection=transport(Factory(socket){entered.countDown();check(release.await(3,TimeUnit.SECONDS))},clock,executor)
        try {
            val pending=async(start=CoroutineStart.UNDISPATCHED){runCatching{connection.connect()}.exceptionOrNull()}
            assertTrue(entered.await(1,TimeUnit.SECONDS));clock.fire()
            assertTrue(withTimeout(1000){pending.await()} is SocketTimeoutException);release.countDown()
            executor.shutdown();assertTrue(executor.awaitTermination(1,TimeUnit.SECONDS));assertEquals(1,socket.closedCount)
        } finally {release.countDown();connection.close();executor.shutdownNow()}
    }
    @Test fun promptCancellationErasesReadResultBeforeDelivery() = runBlocking {
        val work=ArrayDeque<Runnable>();val resumes=ArrayDeque<Runnable>();val socket=FakeSocket()
        val dispatcher=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable){resumes.addLast(block)}}
        var resultBytes:ByteArray?=null
        socket.input=object:InputStream(){
            override fun read():Int=error("Bulk read expected")
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {resultBytes=bytes;bytes[offset]=9;return 1}
        }
        val connection=transport(Factory(socket),Clock(),Executor{work.addLast(it)})
        try {
            val connecting=async(start=CoroutineStart.UNDISPATCHED){connection.connect()}
            work.removeFirst().run();connecting.await()
            val buffer=ByteBuffer.allocate(8)
            val reading=launch(dispatcher,start=CoroutineStart.UNDISPATCHED){connection.read(buffer)}
            work.removeFirst().run();assertEquals(9,resultBytes!![0].toInt())
            reading.cancel();while(resumes.isNotEmpty())resumes.removeFirst().run();reading.join()
            assertEquals(0,buffer.position());assertTrue(resultBytes!!.all{it==0.toByte()})
        } finally {connection.close()}
    }
    @Test fun blockedConnectDeadlineClosesActualSocket() = runBlocking {
        val socket=FakeSocket().apply {connectBlocked=true};val clock=Clock();val executor=pool()
        val connection=transport(Factory(socket),clock,executor)
        try {
            val pending=async(start=CoroutineStart.UNDISPATCHED) {runCatching{connection.connect()}.exceptionOrNull()}
            assertTrue(socket.entered.await(1,TimeUnit.SECONDS));clock.fire()
            assertTrue(withTimeout(1000){pending.await()} is SocketTimeoutException);assertEquals(1,socket.closedCount)
        } finally {connection.close();executor.shutdownNow()}
    }
    @Test fun blockedWriteDeadlineAndConcurrentWriterAreBounded() = runBlocking {
        val socket=FakeSocket();val clock=Clock();val executor=pool();val connection=transport(Factory(socket),clock,executor)
        try {
            connection.connect();socket.outputBlocked=true
            val data=ByteBuffer.wrap(byteArrayOf(4,5,6))
            val pending=async(start=CoroutineStart.UNDISPATCHED) {runCatching{connection.write(data)}.exceptionOrNull()}
            assertTrue(socket.entered.await(1,TimeUnit.SECONDS))
            assertTrue(runCatching{connection.write(ByteBuffer.wrap(byteArrayOf(1)))}.exceptionOrNull() is IOException)
            clock.fire();assertTrue(withTimeout(1000){pending.await()} is SocketTimeoutException)
            assertEquals(0,data.position());assertEquals(1,socket.closedCount);assertFalse(connection.isConnected)
        } finally {connection.close();executor.shutdownNow()}
    }
    @Test fun cancellationClosesBlockedWrite() = runBlocking {
        val socket=FakeSocket();val executor=pool();val connection=transport(Factory(socket),Clock(),executor)
        try {
            connection.connect();socket.outputBlocked=true
            val pending=launch(start=CoroutineStart.UNDISPATCHED){connection.write(ByteBuffer.wrap(byteArrayOf(2)))}
            assertTrue(socket.entered.await(1,TimeUnit.SECONDS));withTimeout(1000){pending.cancelAndJoin()}
            assertEquals(1,socket.closedCount);assertFalse(connection.isConnected)
        } finally {connection.close();executor.shutdownNow()}
    }
    @Test fun networkLossClosesBlockedReadAndLeavesCallerBufferUntouched() = runBlocking {
        val socket=FakeSocket();val executor=pool();val connection=transport(Factory(socket),Clock(),executor)
        socket.input=object:InputStream(){
            override fun read():Int=error("Bulk read expected")
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                socket.entered.countDown();check(socket.released.await(3,TimeUnit.SECONDS));throw SocketException("Synthetic lost network")
            }
        }
        try {
            connection.connect();val buffer=ByteBuffer.allocate(16)
            val pending=async(start=CoroutineStart.UNDISPATCHED){runCatching{connection.read(buffer)}.exceptionOrNull()}
            assertTrue(socket.entered.await(1,TimeUnit.SECONDS));connection.close()
            assertTrue(withTimeout(1000){pending.await()} is IOException);assertEquals(0,buffer.position());assertEquals(1,socket.closedCount)
        } finally {connection.close();executor.shutdownNow()}
    }
}
