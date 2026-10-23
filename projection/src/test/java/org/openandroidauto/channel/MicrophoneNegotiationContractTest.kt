package org.openandroidauto.channel

import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class MicrophoneNegotiationContractTest {
    private class Sink:VideoChannelCallback {
        val messages=mutableListOf<ByteArray>()
        override fun onVideoFrame(channelId:UByte,payload:ByteArray) { }
        override fun onSendMessage(channelId:UByte,payload:ByteArray) { messages.add(payload) }
    }
    @Test fun modernInputOpensDirectlyOnlyAfterChannelOpenAndActualReadiness() {
        val sink=Sink();val mic=AudioInputChannel(7u,sink)
        mic.requestOpen(true);assertTrue(sink.messages.isEmpty())
        mic.onChannelOpened()
        assertArrayEquals(byteArrayOf(0x80.toByte(),5,8,1,16,0,24,0,32,5),sink.messages.single())
        assertFalse(sink.messages.any{it[1]==0.toByte()})
        mic.onChannelOpened();mic.requestOpen(true);assertEquals(1,sink.messages.size)
    }
    @Test fun channelOpenAloneAndUnknownErrorCannotStartCaptureOrChangeMode() {
        val sink=Sink();val events=mutableListOf<MicrophoneProtocolTrace.Event>()
        val mic=AudioInputChannel(7u,sink,trace=MicrophoneProtocolTrace({true},events::add))
        mic.onChannelOpened();mic.onMessage(255,byteArrayOf())
        assertTrue(sink.messages.isEmpty())
        assertTrue(events.any{it.stage==MicrophoneProtocolTrace.Stage.DIRECT_OPEN_READY && it.inputMode==AudioInputChannel.Mode.DIRECT_OPEN && it.openReady})
        mic.requestOpen(true);assertEquals(5,sink.messages.single()[1].toInt())
    }
    @Test fun pcmCannotForwardOrAckBeforeAValidOpenResponse() {
        val sink=Sink();var count=0;val mic=AudioInputChannel(7u,sink){_,_->count++}
        mic.onChannelOpened();mic.requestOpen(true);sink.messages.clear()
        mic.onMessage(0,ByteArray(648));assertEquals(0,count);assertTrue(sink.messages.isEmpty())
        mic.onMessage(0x8006,byteArrayOf(8,9,16,5));mic.onMessage(0,ByteArray(648))
        assertEquals(1,count);assertArrayEquals(byteArrayOf(0x80.toByte(),4,8,9,16,1),sink.messages.single())
    }
    @Test fun legacySetupIsExplicitAndRequiresSuccessfulResponse() {
        val sink=Sink();val mic=AudioInputChannel(7u,sink,mode=AudioInputChannel.Mode.LEGACY_SETUP_REQUIRED)
        mic.requestOpen(true);mic.onChannelOpened()
        assertArrayEquals(byteArrayOf(0x80.toByte(),0,8,0),sink.messages.single())
        mic.onMessage(255,byteArrayOf());mic.onMessage(0x8003,byteArrayOf(8,1,16,5));assertEquals(1,sink.messages.size)
        mic.onMessage(0x8003,byteArrayOf(8,2,16,5));assertEquals(5,sink.messages.last()[1].toInt())
    }
    @Test fun ackEncodesNegotiatedSessionsZero128AndMaxIntAsVarints() {
        for(encoded in listOf(byteArrayOf(0),byteArrayOf(0x80.toByte(),1),byteArrayOf(0xff.toByte(),0xff.toByte(),0xff.toByte(),0xff.toByte(),7))) {
            val sink=Sink();val mic=AudioInputChannel(7u,sink)
            mic.onChannelOpened();mic.requestOpen(true);mic.onMessage(0x8006,byteArrayOf(8)+encoded+byteArrayOf(16,5));sink.messages.clear()
            mic.onMessage(0,ByteArray(648))
            assertArrayEquals(byteArrayOf(0x80.toByte(),4,8)+encoded+byteArrayOf(16,1),sink.messages.single())
        }
    }
    @Test fun invalidOpenSessionsNeverForwardOrAckPcm() {
        for(response in listOf(byteArrayOf(16,5),byteArrayOf(8,1,8,2),byteArrayOf(8)+ByteArray(9){0xff.toByte()}+byteArrayOf(1),byteArrayOf(8,0x80.toByte(),0x80.toByte(),0x80.toByte(),0x80.toByte(),8),byteArrayOf(8,0x80.toByte()))) {
            val sink=Sink();var forwarded=false;val mic=AudioInputChannel(7u,sink){_,_->forwarded=true}
            mic.onChannelOpened();mic.requestOpen(true);mic.onMessage(0x8006,response);sink.messages.clear();mic.onMessage(0,ByteArray(648))
            assertFalse(forwarded);assertTrue(sink.messages.isEmpty())
        }
    }
    @Test fun terminalStopRefusesLateOpenAndPacketsButPeerStopCanStartNewStream() {
        val sink=Sink();val mic=AudioInputChannel(7u,sink)
        mic.onChannelOpened();mic.requestOpen(true);mic.onMessage(0x8006,byteArrayOf(8,1,16,5))
        mic.onMessage(0x8002,byteArrayOf());sink.messages.clear();mic.requestOpen(true)
        assertEquals(5,sink.messages.single()[1].toInt())
        mic.stop();sink.messages.clear();mic.onChannelOpened();mic.requestOpen(true);mic.onMessage(0,ByteArray(648))
        assertTrue(sink.messages.isEmpty())
    }
    @Test fun closeThenReopenWaitsForEachResponseWithoutAuthorizingTheNewTurnEarly() {
        val sink=Sink();var count=0;val mic=AudioInputChannel(7u,sink){_,_->count++}
        mic.onChannelOpened();mic.requestOpen(false);assertTrue(sink.messages.isEmpty())
        mic.requestOpen(true);mic.onMessage(0x8006,byteArrayOf(8,1))
        mic.requestOpen(false);mic.requestOpen(true)
        assertEquals(2,sink.messages.size) // New open is coalesced behind the outstanding close.
        mic.onMessage(0x8006,byteArrayOf(8,2))
        assertEquals(3,sink.messages.size)
        sink.messages.clear();mic.onMessage(0,ByteArray(648))
        assertEquals(0,count);assertTrue(sink.messages.isEmpty())
        mic.onMessage(0x8006,byteArrayOf(8,3));mic.onMessage(0,ByteArray(648))
        assertEquals(1,count);assertArrayEquals(byteArrayOf(0x80.toByte(),4,8,3,16,1),sink.messages.single())
    }
    @Test fun cancelledPendingOpenCannotLeakPcmAndClosesBeforeNextOpen() {
        val sink=Sink();var count=0;val mic=AudioInputChannel(7u,sink){_,_->count++}
        mic.onChannelOpened();mic.requestOpen(true);mic.requestOpen(false)
        assertEquals(1,sink.messages.size)
        mic.onMessage(0x8006,byteArrayOf(8,1));assertEquals(2,sink.messages.size)
        sink.messages.clear();mic.onMessage(0,ByteArray(648))
        assertEquals(0,count);assertTrue(sink.messages.isEmpty())
        mic.onMessage(0x8006,byteArrayOf(8,2));mic.requestOpen(true)
        assertEquals(1,sink.messages.size)
    }
    @Test fun replacementDuringPendingOpenRequiresAClosedBarrierBeforeFreshOpen() {
        val sink=Sink();var count=0;val mic=AudioInputChannel(7u,sink){_,_->count++}
        mic.onChannelOpened();mic.requestOpen(true);mic.requestOpen(false);mic.requestOpen(true)
        assertEquals(1,sink.messages.size)
        mic.onMessage(0x8006,byteArrayOf(8,1))
        assertArrayEquals(byteArrayOf(0x80.toByte(),5,8,0,16,0,24,0,32,5),sink.messages.last())
        sink.messages.clear();mic.onMessage(0,ByteArray(648));assertEquals(0,count);assertTrue(sink.messages.isEmpty())
        mic.onMessage(0x8006,byteArrayOf(8,2))
        assertArrayEquals(byteArrayOf(0x80.toByte(),5,8,1,16,0,24,0,32,5),sink.messages.single())
        sink.messages.clear();mic.onMessage(0,ByteArray(648));assertEquals(0,count)
        mic.onMessage(0x8006,byteArrayOf(8,3));mic.onMessage(0,ByteArray(648));assertEquals(1,count)
    }
    @Test fun peerStopCannotRepurposeAnOutstandingOpenResponseForANewTurn() {
        val sink=Sink();var count=0;val mic=AudioInputChannel(7u,sink){_,_->count++}
        mic.onChannelOpened();mic.requestOpen(true);mic.onMessage(0x8002,byteArrayOf());mic.requestOpen(true)
        assertEquals(1,sink.messages.size)
        mic.onMessage(0x8006,byteArrayOf(8,1))
        assertEquals(0,sink.messages.last()[3].toInt())
        sink.messages.clear();mic.onMessage(0,ByteArray(648));assertEquals(0,count);assertTrue(sink.messages.isEmpty())
        mic.onMessage(0x8006,byteArrayOf(8,2));assertEquals(1,sink.messages.single()[3].toInt())
        sink.messages.clear();mic.onMessage(0,ByteArray(648));assertEquals(0,count)
        mic.onMessage(0x8006,byteArrayOf(8,3));mic.onMessage(0,ByteArray(648));assertEquals(1,count)
    }
    @Test fun traceRemainsDisabledOrBoundedTo32AcrossConcurrentEvents() {
        val disabled=mutableListOf<MicrophoneProtocolTrace.Event>()
        MicrophoneProtocolTrace({false},disabled::add).record(MicrophoneProtocolTrace.Stage.DISCOVERED,7);assertTrue(disabled.isEmpty())
        val events=Collections.synchronizedList(mutableListOf<MicrophoneProtocolTrace.Event>())
        val trace=MicrophoneProtocolTrace({true},events::add);val go=CountDownLatch(1)
        val workers=List(8){Thread{go.await();repeat(20){trace.record(MicrophoneProtocolTrace.Stage.MESSAGE_RECEIVED,7,0x8006,4)}}}
        workers.forEach{it.start()};go.countDown();workers.forEach{it.join(2_000);assertFalse(it.isAlive)};assertEquals(32,events.size)
    }
}
