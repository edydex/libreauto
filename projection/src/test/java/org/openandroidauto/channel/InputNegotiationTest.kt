package org.openandroidauto.channel

import org.junit.Assert.*
import org.junit.Test
import org.openandroidauto.protocol.ProtoFields

/** Captured official-DHU wire boundaries; no Android view or live device dependency. */
class InputNegotiationTest {
    private class Callback : InputChannelCallback {
        val sent = mutableListOf<ByteArray>()
        val status = mutableListOf<Int>()
        val touches = mutableListOf<InputEvent>()
        override fun onTouchEvent(event: InputEvent) { touches.add(event) }
        override fun onKeyEvent(event: KeyEvent) {}
        override fun onSendMessage(channelId: UByte, payload: ByteArray) { sent.add(payload) }
        override fun onInputBindingStatus(status: Int) { this.status.add(status) }
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val capturedDescriptor = hex("0a1303040506545557587e7f818004828004848004120a08a00610e003180220002800")
    private val touch = hex("08011a0d0a07086410c801180010001801")

    @Test fun `captured supported keys exclude unsupported soft-left key`() {
        val keys = ProtoFields.repeatedUnsignedIntegers(ProtoFields.read(capturedDescriptor), 1)
        assertEquals(listOf(3, 4, 5, 6, 84, 85, 87, 88, 126, 127, 65537, 65538, 65540), keys)
        assertFalse(1 in keys)
    }
    @Test fun `binding requests intersect desired keys with captured capabilities`() {
        val cb = Callback()
        val keys = ProtoFields.repeatedUnsignedIntegers(ProtoFields.read(capturedDescriptor), 1)
        val channel = InputChannel(3u, cb, supportedKeyCodes = keys)
        channel.sendBindingRequest(listOf(1, 4, 85, 84, 85, 999))
        assertEquals(InputChannel.BindingState.REQUESTED, channel.bindingState)
        val request = cb.sent.single()
        assertArrayEquals(byteArrayOf(0x80.toByte(), 2), request.copyOfRange(0, 2))
        assertEquals(listOf(4, 85, 84), ProtoFields.repeatedUnsignedIntegers(ProtoFields.read(request.copyOfRange(2, request.size)), 1))
    }
    @Test fun `touch-only receivers receive an empty key binding request`() {
        val cb = Callback(); val channel = InputChannel(2u, cb)
        channel.sendBindingRequest()
        assertArrayEquals(byteArrayOf(0x80.toByte(), 2), cb.sent.single())
    }
    @Test fun `default binding requests call picker but preserves head unit hangup`() {
        val cb = Callback()
        val channel = InputChannel(3u, cb, supportedKeyCodes = listOf(5, 6))
        channel.sendBindingRequest()
        val request = cb.sent.single().drop(2).toByteArray()
        assertEquals(listOf(5), ProtoFields.repeatedUnsignedIntegers(ProtoFields.read(request), 1))
    }
    private fun requestedKeys(cb: Callback): List<Int> = cb.sent.last().drop(2).toByteArray().let {
        ProtoFields.repeatedUnsignedIntegers(ProtoFields.read(it), 1)
    }
    @Test fun `pending app flow borrows end call and returns it after cancellation`() {
        val cb = Callback(); val channel = InputChannel(3u, cb, supportedKeyCodes = listOf(5, 6))
        channel.sendBindingRequest(); channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0))
        channel.setCommandCancelEnabled(true)
        assertEquals(listOf(5, 6), requestedKeys(cb))
        assertFalse(channel.isCommandCancelReleased())
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0))
        channel.setCommandCancelEnabled(false)
        assertEquals(listOf(5), requestedKeys(cb))
        assertFalse(channel.isCommandCancelReleased())
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8,0))
        assertTrue(channel.isCommandCancelReleased())
    }
    @Test fun `cancel binding changes wait for the outstanding acknowledgement`() {
        val cb = Callback(); val channel = InputChannel(3u, cb, supportedKeyCodes = listOf(5, 6))
        channel.sendBindingRequest(); channel.setCommandCancelEnabled(true)
        assertEquals(1, cb.sent.size)
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0))
        assertEquals(2, cb.sent.size); assertEquals(listOf(5, 6), requestedKeys(cb))
        channel.setCommandCancelEnabled(false)
        assertEquals(2, cb.sent.size)
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0))
        assertEquals(3, cb.sent.size); assertEquals(listOf(5), requestedKeys(cb))
    }
    @Test fun `unsupported end call does not disrupt a bound touch receiver`() {
        val cb = Callback(); val channel = InputChannel(3u, cb, supportedKeyCodes = listOf(5))
        channel.sendBindingRequest(); channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0))
        channel.setCommandCancelEnabled(true)
        assertEquals(1, cb.sent.size); assertEquals(InputChannel.BindingState.BOUND, channel.bindingState)
    }
    @Test fun `failed rebinding is not automatically retried by pending flow changes`() {
        val cb = Callback(); val channel = InputChannel(3u, cb, supportedKeyCodes = listOf(5, 6))
        channel.sendBindingRequest(); channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0))
        channel.setCommandCancelEnabled(true)
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 1))
        channel.setCommandCancelEnabled(false)
        assertEquals(2, cb.sent.size); assertEquals(InputChannel.BindingState.FAILED, channel.bindingState)
    }
    @Test fun `captured signed failure disables input and reports exact status`() {
        val cb = Callback(); val channel = InputChannel(3u, cb)
        channel.sendBindingRequest()
        channel.onMessage(InputMessageType.BINDING_RESPONSE, hex("08eeffffffffffffffff01"))
        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, touch)
        assertEquals(InputChannel.BindingState.FAILED, channel.bindingState)
        assertEquals(-18, channel.bindingStatus)
        assertEquals(listOf(-18), cb.status)
        assertTrue(cb.touches.isEmpty())
    }
    @Test fun `input is accepted only after successful binding and stops after rejection`() {
        val cb = Callback(); val channel = InputChannel(3u, cb)
        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, touch)
        assertTrue(cb.touches.isEmpty())
        channel.sendBindingRequest()
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0))
        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, touch)
        assertEquals(1, cb.touches.size)
        assertEquals(100, cb.touches.single().touchPoints.single().x)
        channel.onMessage(InputMessageType.BINDING_RESPONSE, hex("08eeffffffffffffffff01"))
        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, touch)
        assertEquals(1, cb.touches.size)
    }
    @Test fun `malformed binding response fails closed`() {
        val cb = Callback(); val channel = InputChannel(3u, cb)
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf(8, 0x80.toByte()))
        assertEquals(InputChannel.BindingState.FAILED, channel.bindingState)
        assertEquals(-1, channel.bindingStatus)
        channel.onMessage(InputMessageType.BINDING_RESPONSE, byteArrayOf())
        assertEquals(InputChannel.BindingState.FAILED, channel.bindingState)
    }
    @Test fun `packed and unpacked advertised keys both parse`() {
        val fields = ProtoFields.read(hex("08040a03545557"))
        assertEquals(listOf(4, 84, 85, 87), ProtoFields.repeatedUnsignedIntegers(fields, 1))
    }
    @Test(expected = IllegalArgumentException::class) fun `truncated packed key is rejected`() {
        ProtoFields.repeatedUnsignedIntegers(ProtoFields.read(hex("0a0180")), 1)
    }
    @Test(expected = IllegalArgumentException::class) fun `overflowing packed key is rejected`() {
        ProtoFields.repeatedUnsignedIntegers(ProtoFields.read(hex("0a05ffffffff0f")), 1)
    }
}
