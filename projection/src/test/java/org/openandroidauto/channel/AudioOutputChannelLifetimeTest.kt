package org.openandroidauto.channel

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AudioOutputChannelLifetimeTest {
    @Test fun `ACK gating keeps complete PCM packets and ordered timestamps`() {
        val packets = LinkedBlockingQueue<ByteArray>()
        val focusThread = AtomicReference<Thread>(); val packetThread = AtomicReference<Thread>()
        lateinit var channel: AudioOutputChannel
        channel = AudioOutputChannel(5u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) { packetThread.set(Thread.currentThread()); packets.put(payload) }
            override fun onSendMessage(channelId: UByte, payload: ByteArray) {}
        }) { focus -> focusThread.set(Thread.currentThread()); if (focus) channel.setFocusGranted(true) }
        channel.onMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(8,2,16,1))
        val pcm = ByteArray(7680) { (it % 251).toByte() }
        try {
            assertTrue(channel.enqueuePcm(pcm))
            val first = packets.poll(1,TimeUnit.SECONDS) ?: error("No first PCM packet")
            assertEquals(3850, first.size); assertEquals(0L,ByteBuffer.wrap(first,2,8).long)
            assertArrayEquals(pcm.copyOfRange(0,3840),first.copyOfRange(10,first.size))
            assertNull(packets.poll(50,TimeUnit.MILLISECONDS))
            channel.onMessage(AVMessageType.AV_MEDIA_ACK,byteArrayOf(16,1))
            val second = packets.poll(1,TimeUnit.SECONDS) ?: error("No second PCM packet")
            assertEquals(20_000L,ByteBuffer.wrap(second,2,8).long)
            assertArrayEquals(pcm.copyOfRange(3840,7680),second.copyOfRange(10,second.size))
            assertSame(focusThread.get(),packetThread.get())
        } finally { assertTrue(channel.stopAndJoin()) }
        assertFalse(channel.enqueuePcm(ByteArray(3840)))
    }
    @Test fun `stop requests return but join waits for an actual blocked callback to exit`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val joined = CountDownLatch(1)
        val completed = AtomicBoolean()
        lateinit var channel: AudioOutputChannel
        channel = AudioOutputChannel(5u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) { entered.countDown(); await(release) }
            override fun onSendMessage(channelId: UByte, payload: ByteArray) {}
        }) { if (it) channel.setFocusGranted(true) }
        channel.onMessage(AVMessageType.SETUP_RESPONSE,byteArrayOf(8,2,16,1))
        assertTrue(channel.enqueuePcm(ByteArray(3840))); assertTrue(entered.await(1,TimeUnit.SECONDS))
        channel.stop(); assertFalse(channel.enqueuePcm(ByteArray(3840)))
        val waiter = Thread { completed.set(channel.awaitStopped()); joined.countDown() }.apply { start() }
        try { assertFalse(joined.await(50,TimeUnit.MILLISECONDS)) }
        finally { release.countDown() }
        assertTrue(joined.await(1,TimeUnit.SECONDS)); waiter.join(); assertTrue(completed.get())
    }
    @Test fun `ungranted focus preserves bounded explicit backpressure and joined shutdown`() {
        val requested = CountDownLatch(1)
        val channel = AudioOutputChannel(5u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) { fail("No audio without focus") }
            override fun onSendMessage(channelId: UByte, payload: ByteArray) {}
        }) { if (it) requested.countDown() }
        channel.onMessage(AVMessageType.SETUP_RESPONSE,byteArrayOf(8,2))
        try {
            repeat(8) { assertTrue(channel.enqueuePcm(ByteArray(3840))) }
            assertTrue(requested.await(1,TimeUnit.SECONDS)); assertFalse(channel.enqueuePcm(ByteArray(3840)))
        } finally { assertTrue(channel.stopAndJoin()) }
    }
    @Test fun `rapid pause and resume keeps accepted new PCM behind ordered focus release`() {
        val firstFocus = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val focusEvents = LinkedBlockingQueue<Boolean>(); val packets = LinkedBlockingQueue<ByteArray>()
        var first = true
        lateinit var channel: AudioOutputChannel
        channel = AudioOutputChannel(5u, object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte, payload: ByteArray) { packets.put(payload) }
            override fun onSendMessage(channelId: UByte, payload: ByteArray) {}
        }) { active ->
            focusEvents.put(active)
            if (first) { first = false; firstFocus.countDown(); await(releaseFirst) }
            if (active) channel.setFocusGranted(true)
        }
        channel.onMessage(AVMessageType.SETUP_RESPONSE,byteArrayOf(8,2))
        try {
            assertTrue(channel.enqueuePcm(ByteArray(3840) { 1 }))
            assertTrue(firstFocus.await(1,TimeUnit.SECONDS))
            channel.stopPlayback()
            val resumed = ByteArray(3840) { 42 }
            assertTrue(channel.enqueuePcm(resumed)); releaseFirst.countDown()
            assertEquals(true,focusEvents.poll(1,TimeUnit.SECONDS))
            assertEquals(false,focusEvents.poll(1,TimeUnit.SECONDS))
            assertEquals(true,focusEvents.poll(1,TimeUnit.SECONDS))
            val packet = packets.poll(1,TimeUnit.SECONDS) ?: error("No resumed PCM")
            assertArrayEquals(resumed,packet.copyOfRange(10,packet.size))
            assertEquals(0L,ByteBuffer.wrap(packet,2,8).long)
        } finally { releaseFirst.countDown(); assertTrue(channel.stopAndJoin()) }
    }
    @Test fun `focus transitions stay bounded and backpressure instead of losing admitted notifications`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = LinkedBlockingQueue<Boolean>(); var first = true
        val channel = AudioOutputChannel(5u,object : VideoChannelCallback {
            override fun onVideoFrame(channelId: UByte,payload: ByteArray) { fail("Paused PCM must stay stopped") }
            override fun onSendMessage(channelId: UByte,payload: ByteArray) {}
        }) { active -> events.put(active); if (first) { first=false; entered.countDown(); await(release) } }
        channel.onMessage(AVMessageType.SETUP_RESPONSE,byteArrayOf(8,2))
        try {
            assertTrue(channel.enqueuePcm(ByteArray(3840))); assertTrue(entered.await(1,TimeUnit.SECONDS))
            repeat(7) { channel.stopPlayback(); assertTrue(channel.enqueuePcm(ByteArray(3840))) }
            channel.stopPlayback(); assertFalse(channel.enqueuePcm(ByteArray(3840)))
            release.countDown()
            repeat(8) {
                assertEquals(true,events.poll(1,TimeUnit.SECONDS)); assertEquals(false,events.poll(1,TimeUnit.SECONDS))
            }
            assertNull(events.poll(30,TimeUnit.MILLISECONDS))
        } finally { release.countDown(); assertTrue(channel.stopAndJoin()) }
    }
    private fun await(latch: CountDownLatch) {
        var interrupted = false
        while (true) try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
