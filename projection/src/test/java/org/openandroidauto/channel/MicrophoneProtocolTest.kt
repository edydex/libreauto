package org.openandroidauto.channel
import org.junit.Assert.*
import org.junit.Test
class MicrophoneProtocolTest {
    private class Sink:VideoChannelCallback {
        val messages=mutableListOf<ByteArray>()
        override fun onVideoFrame(channelId:UByte,payload:ByteArray){}
        override fun onSendMessage(channelId:UByte,payload:ByteArray){messages.add(payload)}
    }
    @Test fun `phone requests microphone directly after channel open and readiness`() {
        val sink=Sink();val channel=AudioInputChannel(5u,sink)
        channel.requestOpen(true);assertTrue(sink.messages.isEmpty());channel.onChannelOpened()
        assertArrayEquals(byteArrayOf(0x80.toByte(),5,8,1,16,0,24,0,32,5),sink.messages.single())
    }
    @Test fun `car PCM is delivered only with negotiated session ACK`() {
        val sink=Sink();val audio=mutableListOf<ByteArray>();val channel=AudioInputChannel(5u,sink){pcm,rate->assertEquals(16000,rate);audio.add(pcm)}
        channel.onChannelOpened();channel.requestOpen(true);channel.onMessage(0x8006,byteArrayOf(8,7,16,5));sink.messages.clear()
        val pcm=byteArrayOf(1,2,3,4);channel.onMessage(0,ByteArray(8)+pcm)
        assertArrayEquals(pcm,audio.single());assertArrayEquals(byteArrayOf(0x80.toByte(),4,8,7,16,1),sink.messages.single())
    }
    @Test fun `closed microphone does not leak PCM to provider`() {
        val sink=Sink();var delivered=false;val channel=AudioInputChannel(5u,sink){_,_->delivered=true}
        channel.onChannelOpened();channel.requestOpen(true);channel.onMessage(0x8006,byteArrayOf(8,1,16,5));channel.requestOpen(false)
        channel.onMessage(0,ByteArray(10));assertFalse(delivered)
    }
}
