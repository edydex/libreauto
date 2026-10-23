package org.openandroidauto.channel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
class MediaOutputConfigurationContractTest {
    @Test fun modernSetupSelectsPcmCodecIndependentlyOfFormatIndex() {
        for(index in listOf(0,1,129)) {
            val messages=mutableListOf<ByteArray>()
            val channel=AudioOutputChannel(5u,object:VideoChannelCallback {
                override fun onVideoFrame(channelId:UByte,payload:ByteArray){}
                override fun onSendMessage(channelId:UByte,payload:ByteArray){messages.add(payload)}
            },configurationIndex=index)
            channel.sendSetup();assertArrayEquals(byteArrayOf(0x80.toByte(),0,8,1),messages.single())
            channel.stop();assertTrue(channel.awaitStopped())
        }
    }
    @Test fun startCarriesNegotiatedFormatIndexAndSessionAsVarints() {
        for((index,encoded)in listOf(0 to byteArrayOf(0),1 to byteArrayOf(1),129 to byteArrayOf(0x81.toByte(),1))) {
            val messages=LinkedBlockingQueue<ByteArray>();lateinit var channel:AudioOutputChannel
            channel=AudioOutputChannel(5u,object:VideoChannelCallback {
                override fun onVideoFrame(channelId:UByte,payload:ByteArray){}
                override fun onSendMessage(channelId:UByte,payload:ByteArray){messages.put(payload)}
            },configurationIndex=index){active->if(active)channel.setFocusGranted(true)}
            channel.onMessage(0x8003,byteArrayOf(8,2,16,5))
            channel.onMessage(0x8001,byteArrayOf(8,0x80.toByte(),1,16,0))
            try {
                assertTrue(channel.enqueuePcm(ByteArray(3840)))
                assertArrayEquals(byteArrayOf(0x80.toByte(),1,8,0x80.toByte(),1,16)+encoded,messages.poll(2,TimeUnit.SECONDS))
            }finally{channel.stop();assertTrue(channel.awaitStopped())}
        }
    }
    @Test fun unsupportedMissingOrUnboundedIndicesCannotConstructAnOutput() {
        for(index in listOf(-1,65536))try{AudioOutputChannel(5u,object:VideoChannelCallback {
            override fun onVideoFrame(channelId:UByte,payload:ByteArray){}
            override fun onSendMessage(channelId:UByte,payload:ByteArray){}
        },configurationIndex=index);fail()}catch(_:IllegalArgumentException){}
    }
}
