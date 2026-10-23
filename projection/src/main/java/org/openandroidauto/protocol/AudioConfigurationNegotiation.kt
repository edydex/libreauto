// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.protocol

/** Advertised protocol metadata only. No PCM conversion, audio buffers or guessing. */
data class PcmAudioConfiguration(val sampleRate: Int, val bitDepth: Int, val channelCount: Int)

internal object AudioConfigurationNegotiation {
    const val MAX_CONFIGURATIONS = 256
    private val mediaPcm = PcmAudioConfiguration(48_000,16,2)
    private val microphonePcm = PcmAudioConfiguration(16_000,16,1)
    fun mediaConfigurationIndex(configurations: List<PcmAudioConfiguration>): Int {
        require(configurations.size <= MAX_CONFIGURATIONS)
        return configurations.indexOfFirst { it == mediaPcm }
    }
    fun supportsMicrophone(streamType: Int, configuration: PcmAudioConfiguration): Boolean =
        streamType == 1 && configuration == microphonePcm

    /** Keep unsupported placeholders in their original position: dropping a malformed
     * entry would shift a later valid configuration to the wrong advertised index. */
    fun parseConfiguration(fields: List<ProtoFields.Field>) = PcmAudioConfiguration(
        singleUnsigned(fields,1),singleUnsigned(fields,2),singleUnsigned(fields,3))
    fun singleUnsigned(fields: List<ProtoFields.Field>, number: Int): Int {
        val field = fields.filter { it.number == number }.singleOrNull() ?: return 0
        val value = field.integer ?: return 0
        return if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else 0
    }
}
