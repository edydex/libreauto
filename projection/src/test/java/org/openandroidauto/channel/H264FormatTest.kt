package org.openandroidauto.channel
import org.junit.Assert.*
import org.junit.Test
class H264FormatTest {
    @Test fun `AnnexB remains byte exact`() {val bytes=byteArrayOf(0,0,0,1,0x67,1,2);assertArrayEquals(bytes,VideoChannel.annexB(bytes))}
    @Test fun `AVCC NAL lengths become AnnexB start codes`() {assertArrayEquals(byteArrayOf(0,0,0,1,0x67,1,0,0,0,1,0x68),VideoChannel.annexB(byteArrayOf(0,0,0,2,0x67,1,0,0,0,1,0x68)))}
    @Test fun `incomplete NAL is rejected`() {assertThrows(IllegalArgumentException::class.java){VideoChannel.annexB(byteArrayOf(0,0,0,9,0x67))}}
}
