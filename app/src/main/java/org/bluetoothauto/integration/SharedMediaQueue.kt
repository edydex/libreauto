// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.bluetoothauto.model.Track

/** A queue is a selection, never the owner of persisted URI grants or saved playlists. */
object SharedMediaQueue {
    /** A URI may deliberately occur more than once; each occurrence keeps its queue position. */
    fun originalIndices(original: List<Track>, ordered: List<Track>): List<Int> {
        val remaining = original.withIndex().groupBy({ it.value }, { it.index })
            .mapValues { ArrayDeque(it.value) }
        return ordered.map { track -> remaining[track]?.removeFirstOrNull() ?: -1 }
    }

    fun replacement(selected: List<String>, previous: List<Track>, saved: List<Track>): List<Track> {
        require(selected.size <= 512) { "Choose at most 512 audio files" }
        require(selected.all { it.startsWith("content://") || it.startsWith("file://") }) { "Choose local audio files" }
        val metadata = (saved + previous).associateBy { it.uri }
        return selected.map { uri -> metadata[uri] ?: Track(uri, uri.substringAfterLast('/').ifBlank { "Music" }) }
    }

    fun hostSpeedUnits(value: String) = when (value) { "mph" -> "IMPERIAL"; "kmh" -> "METRIC"; else -> "AUTO" }
    fun projectionSpeedUnits(value: String) = when (value) { "IMPERIAL" -> "mph"; "METRIC" -> "kmh"; else -> "auto" }
}
