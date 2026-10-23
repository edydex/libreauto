package org.openandroidauto.channel

/** Bounded AV varint fields. In the modern sink protocol, setup field1 is a
 * codec; the advertised format index is START field2. encode() remains solely
 * for an explicitly selected old setup-required microphone compatibility mode. */
internal object AvSetupConfiguration {
    fun encode(index: Int): ByteArray {
        require(index in 0..65535) { "Configuration index out of bounds" }
        return unsignedField(1,index)
    }
    fun unsignedField(field: Int, value: Int): ByteArray {
        require(field in 1..15 && value >= 0) { "Invalid AV integer" }
        val bytes=java.io.ByteArrayOutputStream(4)
        bytes.write(field shl 3)
        var remaining=value
        while(remaining>127) { bytes.write((remaining and 127) or 128);remaining=remaining ushr 7 }
        bytes.write(remaining)
        return bytes.toByteArray()
    }
}
