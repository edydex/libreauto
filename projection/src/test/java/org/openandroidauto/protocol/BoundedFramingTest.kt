package org.openandroidauto.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BoundedFramingTest {
    @Test fun `decrypt each fragment before plaintext reassembly`() {
        val chunks = listOf("hello ".toByteArray(), "world".toByteArray())
        val first = MessageFramer.buildFirstFrame(FrameHeader.create(1u, FrameHeader.FrameType.FIRST, encrypted=true), byteArrayOf(99)+chunks[0], 11)
        val last = MessageFramer.buildFrame(FrameHeader.create(1u,FrameHeader.FrameType.LAST,encrypted=true),byteArrayOf(99)+chunks[1])
        var calls=0
        val decoder=MessageFramer.Decoder { bytes, header -> assertTrue(header.isEncrypted);calls++;bytes.copyOfRange(1,bytes.size) }
        val messages=decoder.decode(ByteBuffer.wrap(first+last))
        assertEquals(2,calls);assertArrayEquals("hello world".toByteArray(),messages.single().payload)
    }
    @Test fun `interleaved channel fragments retain independent state`() {
        val a=ByteArray(17000){1};val b=ByteArray(18000){2}
        val af=MessageFramer.encode(1u,a);val bf=MessageFramer.encode(2u,b)
        val result=MessageFramer.Decoder().decode(ByteBuffer.wrap(af[0]+bf[0]+af[1]+bf[1]))
        assertEquals(2,result.size);assertArrayEquals(a,result[0].payload);assertArrayEquals(b,result[1].payload)
    }
    @Test fun `orphan fragment rejected`() {
        val frame=MessageFramer.buildFrame(FrameHeader.create(1u,FrameHeader.FrameType.LAST),byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java){MessageFramer.Decoder().decode(ByteBuffer.wrap(frame))}
    }
    @Test fun `oversized declared message rejected before allocation`() {
        val frame=ByteBuffer.allocate(8).put(1).put(1).putShort(0).putInt(Int.MAX_VALUE).array()
        assertThrows(IllegalArgumentException::class.java){MessageFramer.Decoder().decode(ByteBuffer.wrap(frame))}
    }
    @Test fun `partial first header rewinds without consuming payload`() {
        val frame=MessageFramer.encode(1u,ByteArray(17000))[0]
        for(size in 4..7) { val data=ByteBuffer.wrap(frame.copyOf(size));assertTrue(MessageFramer.Decoder().decode(data).isEmpty());assertEquals(0,data.position()) }
    }
}
