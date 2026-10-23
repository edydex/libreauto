// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.bluetoothauto.AppState
import org.bluetoothauto.BluetoothAutoService
import org.bluetoothauto.Library
import org.bluetoothauto.MusicLibraryActivity
import org.bluetoothauto.model.PlaybackState
import org.bluetoothauto.model.Track
import org.bluetoothauto.playback.MusicLibraryStore
import org.openandroidauto.integration.ProjectionIntegration
import org.openandroidauto.integration.SharedLibraryIntegration
import org.openandroidauto.media.MediaEngine
import org.openandroidauto.media.MediaLibraryStore
import org.openandroidauto.navigation.DestinationStore

/** Installed once by the host Application. Installation starts no foreground service or worker. */
object SharedLibraryBridge {
    private var app: Context? = null
    private var selectionSource = emptyList<Track>()
    private var selectionShuffle = false
    private var selection = emptyList<Track>()
    private var selectionOriginalIndices = emptyList<Int>()

    fun install(context: Context) {
        app = context.applicationContext
        SharedLibraryIntegration.delegate = object : SharedLibraryIntegration.Delegate {
            override fun mediaQueue(context: Context): List<Uri> = selectedQueue(context).map { Uri.parse(it.uri) }
            override fun replaceMediaQueue(context: Context, selected: List<Uri>) {
                val saved = MusicLibraryStore.load(context).playlists.flatMap { it.tracks }
                Library.save(context, SharedMediaQueue.replacement(selected.map(Uri::toString), Library.tracks(context), saved))
                // Saving a queue never edits playlists, roots, or musicGrants and never releases a URI permission.
                context.sendBroadcast(Intent(MediaLibraryStore.ACTION_CHANGED).setPackage(context.packageName))
            }
            override fun openMusicLibrary(activity: Activity) {
                activity.startActivity(Intent(activity, MusicLibraryActivity::class.java))
            }
            override fun destinationBackend(context: Context): DestinationStore.Backend = SharedDestinationsBackend(context)
            override fun speedUnits(context: Context): String = SharedMediaQueue.projectionSpeedUnits(Library.units(context))
            override fun setSpeedUnits(context: Context, value: String) = Library.units(context, SharedMediaQueue.hostSpeedUnits(value))
            override fun playbackChanged(snapshot: MediaEngine.Snapshot?, queue: List<Uri>) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    Handler(Looper.getMainLooper()).post { publishPlayback(snapshot, queue) }
                } else publishPlayback(snapshot, queue)
            }
        }
    }

    /** Called after queue persistence by the same playlist/folder UI used in Bluetooth mode. */
    fun playOnProjectionIfActive(context: Context, tracks: List<Track>, shuffled: Boolean, index: Int): Boolean {
        if (!ProjectionIntegration.isSessionActive()) return false
        val ordered = rememberSelection(tracks, shuffled)
        if (ordered.isNotEmpty()) {
            val start = if (shuffled) 0 else index.coerceIn(ordered.indices)
            if (!SharedLibraryIntegration.play(ordered.map { Uri.parse(it.uri) }, start))
                Toast.makeText(context, "Car audio is connecting. Press Play when it is ready.", Toast.LENGTH_SHORT).show()
        }
        // Never fall back to a phone/Bluetooth decoder while projection owns the audio session.
        return true
    }

    /** Central host command interception; a selected mode alone never consumes a command. */
    fun routeCommand(context: Context, action: String, index: Int = 0, positionMs: Long = 0): Boolean {
        if (!ProjectionIntegration.isSessionActive()) return false
        when (action) {
            BluetoothAutoService.PLAY_INDEX -> playOnProjectionIfActive(context, Library.tracks(context), false, index)
            BluetoothAutoService.TOGGLE -> SharedLibraryIntegration.mediaKey(85)
            BluetoothAutoService.PLAY -> SharedLibraryIntegration.mediaKey(126)
            BluetoothAutoService.PAUSE -> SharedLibraryIntegration.mediaKey(127)
            BluetoothAutoService.NEXT -> SharedLibraryIntegration.mediaKey(87)
            BluetoothAutoService.PREVIOUS -> SharedLibraryIntegration.mediaKey(88)
            BluetoothAutoService.STOP -> SharedLibraryIntegration.mediaKey(86)
            BluetoothAutoService.SEEK -> SharedLibraryIntegration.seekTo(positionMs)
            BluetoothAutoService.QUEUE_CHANGED -> {
                SharedLibraryIntegration.clearPlayback()
                context.sendBroadcast(Intent(MediaLibraryStore.ACTION_CHANGED).setPackage(context.packageName))
            }
            BluetoothAutoService.SHUFFLE -> {
                val wasPlaying = AppState.playback.playing
                val queue = selectedQueue(context)
                SharedLibraryIntegration.clearPlayback()
                if (wasPlaying && queue.isNotEmpty()) SharedLibraryIntegration.play(queue.map { Uri.parse(it.uri) }, 0)
            }
            // Location, artwork and notification-session commands belong to Bluetooth mode.
            // They must not instantiate its service during a projected session.
        }
        return true
    }

    @Synchronized private fun rememberSelection(tracks: List<Track>, shuffled: Boolean): List<Track> {
        selectionSource = tracks.toList()
        selectionShuffle = shuffled
        selection = if (shuffled) tracks.shuffled() else tracks.toList()
        selectionOriginalIndices = SharedMediaQueue.originalIndices(selectionSource, selection)
        return selection
    }

    @Synchronized private fun selectedQueue(context: Context): List<Track> {
        val tracks = Library.tracks(context)
        val shuffled = Library.shuffle(context)
        if (tracks != selectionSource || shuffled != selectionShuffle) {
            rememberSelection(tracks, shuffled)
        }
        return selection
    }

    private fun publishPlayback(snapshot: MediaEngine.Snapshot?, queue: List<Uri>) {
        if (snapshot == null) {
            AppState.playback = AppState.playback.copy(playing = false)
            AppState.changed()
            return
        }
        val context = app ?: return
        if (!ProjectionIntegration.isSessionActive()) return
        val original = Library.tracks(context)
        val uri = queue.getOrNull(snapshot.queueIndex)?.toString()
        val originalIndex = synchronized(this) {
            if (selectionSource == original && selection.size == queue.size && selection.indices.all { selection[it].uri == queue[it].toString() })
                selectionOriginalIndices.getOrNull(snapshot.queueIndex) ?: -1
            else if (uri == null) -1 else original.indexOfFirst { it.uri == uri }
        }
        val known = original.getOrNull(originalIndex)
        val track = uri?.let { known ?: Track(it, snapshot.title.ifBlank { "Music" }, snapshot.artist) }
        AppState.playback = PlaybackState(track, snapshot.status == MediaEngine.Status.PLAYING,
            snapshot.positionMs, snapshot.durationMs, originalIndex,
            snapshot.queueSize, snapshot.error)
        AppState.presentation = null
        AppState.error = snapshot.error
        AppState.changed()
    }
}
