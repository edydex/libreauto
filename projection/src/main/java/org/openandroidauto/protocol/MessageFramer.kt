package org.openandroidauto.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GPL-derived AAP wire framing. Frame flags: fragment(0..1), control(2), encrypted(3). */
data class FrameHeader(val channelId: UByte, val flags: UByte) {
    enum class FrameType(val value: Int) { MIDDLE(0), FIRST(1), LAST(2), SINGLE(3);
        companion object { fun from(v: Int) = entries.first { it.value == v } }
    }
    val frameType get() = FrameType.from(flags.toInt() and 3)
    val isControl get() = flags.toInt() and 4 != 0
    val isEncrypted get() = flags.toInt() and 8 != 0
    companion object {
        fun create(channelId: UByte, frameType: FrameType, control: Boolean = false, encrypted: Boolean = false) =
            FrameHeader(channelId, (frameType.value or (if (control) 4 else 0) or (if (encrypted) 8 else 0)).toUByte())
    }
}
object MessageFramer {
    const val MAX_FRAME_PAYLOAD = 16384
    const val MAX_MESSAGE_PAYLOAD = 4 * 1024 * 1024
    data class Frame(val header: FrameHeader, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Frame && header == other.header && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * header.hashCode() + payload.contentHashCode()
    }
    fun encode(channelId: UByte, payload: ByteArray, control: Boolean = false, encrypted: Boolean = false): List<ByteArray> {
        require(payload.size <= MAX_MESSAGE_PAYLOAD)
        if (payload.size <= MAX_FRAME_PAYLOAD) return listOf(buildFrame(FrameHeader.create(channelId, FrameHeader.FrameType.SINGLE, control, encrypted), payload))
        val count = (payload.size + MAX_FRAME_PAYLOAD - 1) / MAX_FRAME_PAYLOAD
        return (0 until count).map { i ->
            val chunk = payload.copyOfRange(i * MAX_FRAME_PAYLOAD, minOf((i + 1) * MAX_FRAME_PAYLOAD, payload.size))
            val type = when (i) { 0 -> FrameHeader.FrameType.FIRST; count - 1 -> FrameHeader.FrameType.LAST; else -> FrameHeader.FrameType.MIDDLE }
            val header = FrameHeader.create(channelId, type, control, encrypted)
            if (i == 0) buildFirstFrame(header, chunk, payload.size) else buildFrame(header, chunk)
        }
    }
    fun buildFrame(header: FrameHeader, payload: ByteArray): ByteArray {
        require(payload.size <= 65535)
        return ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN).put(header.channelId.toByte()).put(header.flags.toByte()).putShort(payload.size.toShort()).put(payload).array()
    }
    fun buildFirstFrame(header: FrameHeader, payload: ByteArray, totalLength: Int): ByteArray {
        require(payload.size <= 65535 && totalLength in 0..MAX_MESSAGE_PAYLOAD)
        return ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN).put(header.channelId.toByte()).put(header.flags.toByte()).putShort(payload.size.toShort()).putInt(totalLength).put(payload).array()
    }
    /** Transform each TLS record before reassembling plaintext fragments, never after. */
    class Decoder(private val transform: (ByteArray, FrameHeader) -> ByteArray = { bytes, _ -> bytes }) {
        data class Message(val channelId: UByte, val flags: UByte, val payload: ByteArray) {
            override fun equals(other: Any?) = other is Message && channelId == other.channelId && payload.contentEquals(other.payload)
            override fun hashCode() = 31 * channelId.hashCode() + payload.contentHashCode()
        }
        private data class Pending(val declared: Int, val flags: UByte, val bytes: java.io.ByteArrayOutputStream)
        private val pending = mutableMapOf<UByte, Pending>()
        fun decode(data: ByteBuffer): List<Message> {
            val messages = mutableListOf<Message>()
            while (data.remaining() >= 4) {
                val mark = data.position()
                val header = FrameHeader(data.get().toUByte(), data.get().toUByte())
                val size = data.short.toInt() and 65535
                var declared = -1
                if (header.frameType == FrameHeader.FrameType.FIRST) {
                    if (data.remaining() < 4) { data.position(mark); break }
                    declared = data.int
                    require(declared in 0..MAX_MESSAGE_PAYLOAD) { "Excessive message length" }
                }
                if (data.remaining() < size) { data.position(mark); break }
                val wire = ByteArray(size); data.get(wire)
                val plain = transform(wire, header)
                when (header.frameType) {
                    FrameHeader.FrameType.SINGLE -> messages.add(Message(header.channelId, header.flags, plain))
                    FrameHeader.FrameType.FIRST -> {
                        require(!pending.containsKey(header.channelId)) { "Duplicate first fragment" }
                        require(plain.size <= declared)
                        require(pending.size < 16) { "Too many fragmented channels" }
                        pending[header.channelId] = Pending(declared, header.flags, java.io.ByteArrayOutputStream().apply { write(plain) })
                    }
                    FrameHeader.FrameType.MIDDLE, FrameHeader.FrameType.LAST -> {
                        val item = requireNotNull(pending[header.channelId]) { "Orphan fragment" }
                        require((item.flags.toInt() and 12) == (header.flags.toInt() and 12)) { "Fragment flag mismatch" }
                        require(plain.size <= item.declared - item.bytes.size()) { "Message length mismatch" }
                        item.bytes.write(plain)
                        if (header.frameType == FrameHeader.FrameType.LAST) {
                            require(item.bytes.size() == item.declared) { "Incomplete message" }
                            messages.add(Message(header.channelId, header.flags, item.bytes.toByteArray()))
                            pending.remove(header.channelId)
                        }
                    }
                }
            }
            return messages
        }
    }
}
