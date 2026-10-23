package org.openandroidauto.media

/** One accepted PCM producer at a time; ownership transitions flush the old transport queue. */
class AudioOwnerGate(private val output: (ByteArray) -> Boolean, private val focus: (Boolean) -> Unit) {
    enum class Source { NONE, MEDIA, NAVIGATION, ASSISTANT }
    private var selected = Source.NONE
    @Synchronized fun owner(): Source = selected
    @Synchronized fun select(source: Source) {
        if (source == selected) return
        // Old producer writes complete before this lock; new writes cannot precede the flush.
        selected = Source.NONE
        focus(false)
        selected = source
        if (source != Source.NONE) focus(true)
    }
    @Synchronized fun write(source: Source, pcm: ByteArray): Boolean = source != Source.NONE && source == selected && output(pcm)
}
