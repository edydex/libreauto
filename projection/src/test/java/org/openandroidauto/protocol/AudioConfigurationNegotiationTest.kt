package org.openandroidauto.protocol

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Owned metadata fixtures from declared protobuf fields; no private payload captures. */
class AudioConfigurationNegotiationTest {
    @Test fun mediaSelectsItsAdvertisedIndexRatherThanAConstant() {
        val pcm441 = PcmAudioConfiguration(44_100,16,2)
        val pcm480 = PcmAudioConfiguration(48_000,16,2)
        assertEquals(1, AudioConfigurationNegotiation.mediaConfigurationIndex(listOf(pcm441,pcm480)))
        assertEquals(0, AudioConfigurationNegotiation.mediaConfigurationIndex(listOf(pcm480)))
        assertEquals(-1, AudioConfigurationNegotiation.mediaConfigurationIndex(listOf(pcm441)))
        assertEquals(-1, AudioConfigurationNegotiation.mediaConfigurationIndex(emptyList()))
    }
    @Test fun microphoneRequiresExactSupportedStreamAndFormat() {
        val good = PcmAudioConfiguration(16_000,16,1)
        assertTrue(AudioConfigurationNegotiation.supportsMicrophone(1,good))
        for (wrong in listOf(PcmAudioConfiguration(48_000,16,1),PcmAudioConfiguration(16_000,24,1),
            PcmAudioConfiguration(16_000,16,2),PcmAudioConfiguration(0,16,1)))
            assertFalse(AudioConfigurationNegotiation.supportsMicrophone(1,wrong))
        assertFalse(AudioConfigurationNegotiation.supportsMicrophone(2,good))
    }
    @Test fun discoveryParsesActualSingularMicAndRepeatedMediaShapes() {
        val channels=parse(descriptor(7,5,v(1,1)+b(2,config(16_000,16,1))),
            descriptor(5,3,v(1,1)+v(2,3)+b(3,config(48_000,16,2))))
        val mic=channels.first();val media=channels.last()
        assertTrue(mic.hasAvInput);assertTrue(mic.supportsMicrophonePcm)
        assertEquals(16_000,mic.avInputSampleRate);assertEquals(16,mic.avInputBitDepth);assertEquals(1,mic.avInputChannelCount)
        assertEquals(3,media.audioType);assertEquals(0,media.audioConfigurationIndex)
        assertEquals(listOf(PcmAudioConfiguration(48_000,16,2)),media.audioConfigurations)
    }
    @Test fun discoveryPreserves48kConfigurationAfter44kFirstEntry() {
        val media=parse(descriptor(5,3,v(1,1)+v(2,3)+b(3,config(44_100,16,2))+b(3,config(48_000,16,2)))).single()
        assertEquals(1,media.audioConfigurationIndex)
    }
    @Test fun malformedEntryRemainsUnsupportedWithoutShiftingLaterIndex() {
        val duplicateRate=config(48_000,16,2)+v(1,48_000)
        val media=parse(descriptor(5,3,v(1,1)+v(2,3)+b(3,duplicateRate)+b(3,config(48_000,16,2)))).single()
        assertEquals(0,media.audioConfigurations.first().sampleRate)
        assertEquals(1,media.audioConfigurationIndex)
    }
    @Test fun missingOrDuplicateSingularMicrophoneConfigurationIsRefused() {
        val good=config(16_000,16,1)
        val missing=parse(descriptor(7,5,v(1,1))).single()
        val duplicate=parse(descriptor(7,5,v(1,1)+b(2,good)+b(2,good))).single()
        val unknownStream=parse(descriptor(7,5,v(1,2)+b(2,good))).single()
        assertFalse(missing.supportsMicrophonePcm);assertFalse(duplicate.supportsMicrophonePcm)
        assertFalse(unknownStream.supportsMicrophonePcm)
    }
    @Test fun overflowingAndWrongWireConfigurationFieldsCannotMasqueradeAsSupported() {
        val wrapped=vLong(1,(1L shl 32)+16_000)+v(2,16)+v(3,1)
        val bytesForRate=b(1,byteArrayOf(1))+v(2,16)+v(3,1)
        for (bad in listOf(wrapped,bytesForRate))
            assertFalse(parse(descriptor(7,5,v(1,1)+b(2,bad))).single().supportsMicrophonePcm)
    }
    @Test fun wrongWireOuterConfigurationNeverShiftsIntoAValidIndex() {
        val media=parse(descriptor(5,3,v(1,1)+v(2,3)+v(3,1)+b(3,config(48_000,16,2)))).single()
        assertEquals(2,media.audioConfigurations.size)
        assertEquals(-1,media.audioConfigurationIndex)
        val mic=parse(descriptor(7,5,v(1,1)+v(2,1)+b(2,config(16_000,16,1)))).single()
        assertFalse(mic.supportsMicrophonePcm)
    }
    @Test fun overflowingOrAmbiguousStreamTypeIsUnsupported() {
        val good=config(16_000,16,1)
        for (stream in listOf(vLong(1,(1L shl 32)+1), v(1,1)+v(1,1), b(1,byteArrayOf(1))))
            assertFalse(parse(descriptor(7,5,stream+b(2,good))).single().supportsMicrophonePcm)
    }
    @Test fun configurationCountIsBoundedAndHighIndexIsPreserved() {
        val other=PcmAudioConfiguration(44_100,16,2)
        val configs=List(255){other}+PcmAudioConfiguration(48_000,16,2)
        assertEquals(255,AudioConfigurationNegotiation.mediaConfigurationIndex(configs))
        assertThrows(IllegalArgumentException::class.java) {
            AudioConfigurationNegotiation.mediaConfigurationIndex(configs+other)
        }
        val body=v(1,1)+v(2,3)+(List(256){b(3,config(44_100,16,2))}+b(3,config(48_000,16,2))).fold(byteArrayOf()){a,c->a+c}
        assertThrows(IllegalArgumentException::class.java) { parse(descriptor(5,3,body)) }
    }
    private fun parse(vararg descriptors:ByteArray):List<ChannelDescriptor> {
        val engine=ProtocolEngine(object:ProtocolCallback {
            override fun onSendFrame(channelId:UByte,payload:ByteArray,control:Boolean){}
            override fun onTlsData(data:ByteArray){};override fun onTlsComplete(){}
            override fun onServiceDiscoveryRequest(deviceName:String,deviceBrand:String){}
            override fun onServiceDiscoveryResponse(channels:List<ChannelDescriptor>){}
            override fun onChannelOpenRequest(channelId:Int,priority:Int){};override fun onChannelOpened(channelId:Int){}
            override fun onActive(){};override fun onShutdown(){}
            override fun onAudioFocusRequest(focusType:Int){};override fun onAudioFocusResponse(focusState:Int){}
            override fun onNavigationFocusRequest(type:Int){};override fun onVoiceSessionRequest(type:Int){}
        })
        val bytes=descriptors.fold(byteArrayOf()){a,c->a+b(1,c)}
        engine.onMessage(ControlMessageType.SERVICE_DISCOVERY_RESPONSE,bytes)
        return engine.discoveredChannels
    }
    private fun descriptor(id:Int,kind:Int,body:ByteArray)=v(1,id)+b(kind,body)
    private fun config(rate:Int,bits:Int,channels:Int)=v(1,rate)+v(2,bits)+v(3,channels)
    private fun b(field:Int,value:ByteArray)=vRaw((field shl 3) or 2)+vRaw(value.size)+value
    private fun v(field:Int,value:Int)=vLong(field,value.toLong())
    private fun vLong(field:Int,value:Long)=vRaw(field shl 3)+vRawLong(value)
    private fun vRaw(value:Int)=vRawLong(value.toLong())
    private fun vRawLong(value:Long):ByteArray {
        val out=ByteArrayOutputStream();var n=value
        while(n>127){out.write(((n and 127) or 128).toInt());n=n ushr 7};out.write(n.toInt());return out.toByteArray()
    }
}
