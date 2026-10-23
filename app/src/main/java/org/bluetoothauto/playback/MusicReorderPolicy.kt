// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

/** ListView includes a controls header; saved ordering contains only the music rows. */
internal object MusicReorderPolicy {
    fun targetIndex(adapterPosition: Int, headerCount: Int, rowCount: Int): Int? {
        if (rowCount <= 0 || headerCount < 0 || adapterPosition < 0) return null
        return (adapterPosition - headerCount).coerceIn(0, rowCount - 1)
    }
}
