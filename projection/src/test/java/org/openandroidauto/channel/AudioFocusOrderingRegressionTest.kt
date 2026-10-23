package org.openandroidauto.channel

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real production channel, with a sink barrier AFTER event dequeue and BEFORE
 * the head unit could observe the acquire. No substitute audio/focus implementation. */
class AudioFocusOrderingRegressionTest {
    private class Fixture {
        val acquirePopped = CountDownLatch(1)
        val allowAcquireSend = CountDownLatch(1)
        val releaseSent = CountDownLatch(1)
        val focus = LinkedBlockingQueue<Boolean>()
        val packets = LinkedBlockingQueue<ByteArray>()
        private val firstAcquire = AtomicBoolean(true)
        val channel: AudioOutputChannel
        init {
            channel = AudioOutputChannel(5u, object : VideoChannelCallback {
                override fun onVideoFrame(channelId: UByte, payload: ByteArray) { packets.put(payload) }
                override fun onSendMessage(channelId: UByte, payload: ByteArray) {}
            }, ::sendFocus)
            channel.onMessage(AVMessageType.SETUP_RESPONSE, byteArrayOf(8, 2, 16, 1))
        }
        private fun sendFocus(active: Boolean) {
            if (active && firstAcquire.compareAndSet(true, false)) {
                acquirePopped.countDown()
                awaitRelease(allowAcquireSend)
            }
            focus.put(active)
            channel.setFocusGranted(active)
            if (!active) releaseSent.countDown()
        }
        fun close() { allowAcquireSend.countDown(); assertTrue(channel.stopAndJoin()) }
    }

    @Test(timeout = 5_000) fun pauseAfterPoppedAcquireReturnsPromptlyAndKeepsReleaseAfterIt() {
        val fixture = Fixture()
        val pauseReturned = CountDownLatch(1)
        val pauseError = AtomicReference<Throwable?>()
        val pause = Thread {
            try { fixture.channel.stopPlayback(notifyFocus = true) }
            catch (failure: Throwable) { pauseError.set(failure) }
            finally { pauseReturned.countDown() }
        }.apply { isDaemon = true }
        try {
            assertTrue(fixture.channel.enqueuePcm(ByteArray(3840) { 1 }))
            assertTrue(fixture.acquirePopped.await(1, TimeUnit.SECONDS))
            assertTrue(fixture.focus.isEmpty()) // Acquire is popped but has NOT been sent.
            pause.start()
            assertTrue("Pause waited for the blocked focus sink", pauseReturned.await(1, TimeUnit.SECONDS))
            pause.join(1_000); assertFalse(pause.isAlive); assertNull(pauseError.get())
            assertTrue(fixture.focus.isEmpty()); assertTrue(fixture.packets.isEmpty())
            fixture.allowAcquireSend.countDown()
            assertEquals(true, fixture.focus.poll(1, TimeUnit.SECONDS))
            assertEquals(false, fixture.focus.poll(1, TimeUnit.SECONDS))
            assertTrue(fixture.releaseSent.await(1, TimeUnit.SECONDS))
            assertNull("Paused PCM escaped after stale acquire", fixture.packets.poll(50, TimeUnit.MILLISECONDS))
        } finally {
            fixture.allowAcquireSend.countDown()
            try { pause.join(1_000) } finally { fixture.close() }
        }
    }

    @Test(timeout = 5_000) fun resumeAfterPauseKeepsAcquireReleaseAcquireAndOnlyNewPcm() {
        val fixture = Fixture()
        try {
            assertTrue(fixture.channel.enqueuePcm(ByteArray(3840) { 1 }))
            assertTrue(fixture.acquirePopped.await(1, TimeUnit.SECONDS))
            assertTrue(fixture.focus.isEmpty())
            fixture.channel.stopPlayback(notifyFocus = true)
            val resumed = ByteArray(3840) { 42 }
            assertTrue(fixture.channel.enqueuePcm(resumed))
            fixture.allowAcquireSend.countDown()
            assertEquals(true, fixture.focus.poll(1, TimeUnit.SECONDS))
            assertEquals(false, fixture.focus.poll(1, TimeUnit.SECONDS))
            assertEquals(true, fixture.focus.poll(1, TimeUnit.SECONDS))
            val packet = fixture.packets.poll(1, TimeUnit.SECONDS) ?: error("No resumed PCM")
            assertEquals(3850, packet.size)
            assertEquals(0L, ByteBuffer.wrap(packet, 2, 8).long)
            assertArrayEquals(resumed, packet.copyOfRange(10, packet.size))
            assertNull("Old or duplicate PCM escaped", fixture.packets.poll(50, TimeUnit.MILLISECONDS))
        } finally { fixture.close() }
    }

    companion object {
        private fun awaitRelease(latch: CountDownLatch) {
            var interrupted = false
            while (true) try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
