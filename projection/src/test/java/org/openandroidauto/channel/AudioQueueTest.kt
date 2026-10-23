package org.openandroidauto.channel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AudioQueueTest {
    private class Sink : VideoChannelCallback {
        val frames=CopyOnWriteArrayList<ByteArray>();val control=CopyOnWriteArrayList<ByteArray>()
        override fun onVideoFrame(channelId: UByte,payload:ByteArray){frames.add(payload)}
        override fun onSendMessage(channelId:UByte,payload:ByteArray){control.add(payload)}
    }
    private fun await(predicate:()->Boolean){val end=System.nanoTime()+2_000_000_000L;while(!predicate()&&System.nanoTime()<end)Thread.sleep(2);assertTrue(predicate())}
    @Test fun `unconfigured output rejects PCM explicitly`() {
        val output=AudioOutputChannel(3u,Sink());assertFalse(output.enqueuePcm(ByteArray(3840)));output.stop()
    }
    @Test fun `audio waits for focus and respects ACK window`() {
        val sink=Sink();val focus=CopyOnWriteArrayList<Boolean>();val output=AudioOutputChannel(3u,sink){focus.add(it)}
        output.onMessage(AVMessageType.SETUP_RESPONSE,byteArrayOf(8,2,16,1,24,0))
        assertTrue(output.enqueuePcm(ByteArray(7680)));Thread.sleep(40);assertTrue(sink.frames.isEmpty());assertEquals(listOf(true),focus)
        output.setFocusGranted(true);await{sink.frames.size==1};Thread.sleep(40);assertEquals(1,sink.frames.size)
        output.onMessage(AVMessageType.AV_MEDIA_ACK,byteArrayOf(8,1,16,1));await{sink.frames.size==2}
        val first=java.nio.ByteBuffer.wrap(sink.frames[0]);assertEquals(0,first.short.toInt());assertEquals(0L,first.long)
        val second=java.nio.ByteBuffer.wrap(sink.frames[1]);second.short;assertEquals(20_000L,second.long)
        output.stop()
    }
    @Test fun `stop clears queued PCM and releases focus once`() {
        val sink=Sink();val focus=CopyOnWriteArrayList<Boolean>();val output=AudioOutputChannel(3u,sink){focus.add(it)}
        output.onMessage(AVMessageType.SETUP_RESPONSE,byteArrayOf(8,2,16,1))
        assertTrue(output.enqueuePcm(ByteArray(7680)));output.stopPlayback();output.stopPlayback()
        output.setFocusGranted(true);Thread.sleep(40);assertTrue(sink.frames.isEmpty());assertEquals(listOf(true,false),focus);output.stop()
    }
    @Test fun `invalid PCM geometry is rejected`() {
        val output=AudioOutputChannel(3u,Sink())
        assertThrows(IllegalArgumentException::class.java){output.enqueuePcm(ByteArray(3))}
        assertThrows(IllegalArgumentException::class.java){output.enqueuePcm(ByteArray(65540))};output.stop()
    }
}
