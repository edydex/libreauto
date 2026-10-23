// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.bluetoothauto.model.Track
import kotlin.random.Random

/** A finite cycle: natural completion stops after every selected entry has played once. */
internal class PlayerQueue(private val random: Random = Random.Default) {
    private var tracks: List<Track> = emptyList()
    private var order: List<Int> = emptyList()
    private var cursor = -1
    private var shuffled = false
    val index: Int get() = order.getOrNull(cursor) ?: -1
    val size: Int get() = tracks.size
    val current: Track? get() = tracks.getOrNull(index)

    fun replace(value: List<Track>, startIndex: Int) {
        require(value.size <= MAX_SIZE)
        tracks = value.toList()
        if (tracks.isEmpty()) { order = emptyList(); cursor = -1; return }
        val selected = startIndex.coerceIn(tracks.indices)
        order = if (shuffled) listOf(selected) + tracks.indices.filter { it != selected }.shuffled(random) else tracks.indices.toList()
        cursor = if (shuffled) 0 else selected
    }

    /** Return true if the same current entry survived and native playback may continue. */
    fun update(value: List<Track>): Boolean {
        require(value.size <= MAX_SIZE)
        val selected = index
        val uri = current?.uri
        if (value.size >= tracks.size && tracks.indices.all { tracks[it] == value[it] }) {
            val extra = (tracks.size until value.size).toList()
            tracks = value.toList()
            if (cursor < 0 && tracks.isNotEmpty()) { replace(tracks, 0); return false }
            order = order + if (shuffled) extra.shuffled(random) else extra
            return uri != null
        }
        val replacement = if (selected in value.indices && value[selected].uri == uri) selected else value.indexOfFirst { it.uri == uri }
        replace(value, replacement.coerceAtLeast(0))
        return uri != null && replacement >= 0
    }

    fun setShuffle(enabled: Boolean) {
        if (shuffled == enabled) return
        val selected = index
        shuffled = enabled
        if (selected < 0) return
        order = if (shuffled) listOf(selected) + tracks.indices.filter { it != selected }.shuffled(random) else tracks.indices.toList()
        cursor = if (shuffled) 0 else selected
    }

    fun moveNext(wrap: Boolean): Boolean {
        if (tracks.isEmpty()) return false
        if (cursor < order.lastIndex) { cursor++; return true }
        if (!wrap) return false
        val previous = index
        order = if (shuffled) tracks.indices.shuffled(random).toMutableList().apply {
            // A deliberate new cycle should not immediately repeat the just-finished song.
            if (size > 1 && first() == previous) { val swap = get(1); set(1, first()); set(0, swap) }
        } else tracks.indices.toList()
        cursor = 0
        return true
    }

    /** Goes back through the current cycle's actual listening order; never underflows. */
    fun movePrevious() { if (tracks.isNotEmpty()) cursor = (cursor - 1).coerceAtLeast(0) }

    companion object { const val MAX_SIZE = 512 }
}
