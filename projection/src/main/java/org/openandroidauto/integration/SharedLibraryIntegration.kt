// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.integration

import android.app.Activity
import android.content.Context
import android.net.Uri
import org.openandroidauto.media.MediaEngine
import org.openandroidauto.navigation.DestinationStore
import java.io.Closeable

/** Host-owned library/settings, with no service, decoder, map or timer of its own. */
object SharedLibraryIntegration {
    interface Delegate {
        fun mediaQueue(context: Context): List<Uri>
        fun replaceMediaQueue(context: Context, selected: List<Uri>)
        fun openMusicLibrary(activity: Activity)
        fun destinationBackend(context: Context): DestinationStore.Backend
        fun speedUnits(context: Context): String
        fun setSpeedUnits(context: Context, value: String)
        fun playbackChanged(snapshot: MediaEngine.Snapshot?, queue: List<Uri>) {}
    }

    interface PlaybackController {
        fun play(queue: List<Uri>, startIndex: Int)
        fun mediaKey(keyCode: Int)
        fun seekTo(positionMs: Long)
        fun clear()
    }

    @Volatile var delegate: Delegate? = null
    @Volatile private var playback: PlaybackController? = null

    /** Only a started, real projected dashboard registers. Phone previews cannot own audio. */
    @Synchronized fun registerPlayback(controller: PlaybackController): Closeable {
        if (playback !== controller) playback?.clear()
        playback = controller
        return Closeable {
            synchronized(this) {
                if (playback === controller) {
                    playback = null
                    delegate?.playbackChanged(null, emptyList())
                }
            }
        }
    }

    fun play(queue: List<Uri>, startIndex: Int): Boolean {
        if (queue.isEmpty() || queue.size > 512 || startIndex !in queue.indices) return false
        val owner = playback ?: return false
        owner.play(queue.toList(), startIndex)
        return true
    }
    fun mediaKey(keyCode: Int): Boolean = playback?.let { it.mediaKey(keyCode); true } ?: false
    fun seekTo(positionMs: Long): Boolean = playback?.let { it.seekTo(positionMs.coerceAtLeast(0)); true } ?: false
    fun clearPlayback(): Boolean = playback?.let { it.clear(); true } ?: false
    fun playbackReady(): Boolean = playback != null

    fun publishPlayback(owner: PlaybackController, snapshot: MediaEngine.Snapshot, queue: List<Uri>) {
        if (playback === owner) delegate?.playbackChanged(snapshot, queue)
    }

    @JvmStatic fun destinationBackend(context: Context): DestinationStore.Backend? = delegate?.destinationBackend(context)
    @JvmStatic fun speedUnits(context: Context): String? = delegate?.speedUnits(context)
    @JvmStatic fun setSpeedUnits(context: Context, value: String): Boolean {
        val host = delegate ?: return false
        host.setSpeedUnits(context, value)
        return true
    }
}
