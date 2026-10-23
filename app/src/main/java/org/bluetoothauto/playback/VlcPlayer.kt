// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.bluetoothauto.model.PlaybackState
import org.bluetoothauto.model.Track
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.Closeable
import kotlin.math.abs

/**
 * Audio-only LibVLC playback. The owner supplies audio focus and a MediaSession.
 * Commands may arrive on any thread; state notifications always run on the main looper.
 * Content URI read grants must be obtained by the document picker before setQueue().
 */
class VlcPlayer(context: Context, private val onState: (PlaybackState) -> Unit) : Closeable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val libVlc = LibVLC(appContext, arrayListOf("--no-video", "--no-video-title-show"))
    private var player: MediaPlayer? = null
    private var descriptor: AssetFileDescriptor? = null
    private val queue = PlayerQueue()
    private var generation = 0L
    @Volatile private var wantsPlayback = false
    private var ended = false
    private var volume = 100
    private var lastNotifiedPosition = 0L
    @Volatile private var closed = false
    @Volatile private var currentState = PlaybackState()

    val state: PlaybackState get() = currentState
    /** Includes native opening, when playback has been requested but audio has not started yet. */
    val playRequested: Boolean get() = wantsPlayback

    /** Changes the future play order without reopening or seeking the current audio. */
    fun setShuffle(enabled: Boolean) = onMain { queue.setShuffle(enabled) }

    /** Imports can extend the library without interrupting the currently playing URI. */
    fun updateQueue(tracks: List<Track>) {
        val copy = tracks.take(PlayerQueue.MAX_SIZE + 1).toList()
        onMain {
            if (copy.size > PlayerQueue.MAX_SIZE || copy.any { localUri(it.uri) == null }) {
                publish(currentState.copy(error = "Choose up to ${PlayerQueue.MAX_SIZE} local audio files."))
                return@onMain
            }
            val old = queue.current
            val retained = queue.update(copy)
            when {
                queue.current == null -> {
                    releasePlayer(); wantsPlayback = false; ended = false; publish(PlaybackState())
                }
                retained && old != null -> publish(currentState.copy(track = queue.current, queueIndex = queue.index, queueSize = queue.size))
                else -> loadCurrent(false)
            }
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, play: Boolean = true) {
        // Copy before dispatch: callers can safely reuse their mutable picker list.
        val suppliedSize = tracks.size
        val copied = tracks.take(PlayerQueue.MAX_SIZE + 1).toList()
        onMain {
            if (suppliedSize > PlayerQueue.MAX_SIZE || copied.size > PlayerQueue.MAX_SIZE) {
                publish(currentState.copy(error = "Choose at most ${PlayerQueue.MAX_SIZE} audio files."))
                return@onMain
            }
            if (copied.any { localUri(it.uri) == null }) {
                publish(currentState.copy(error = "Choose local audio files using the file picker."))
                return@onMain
            }
            queue.replace(copied, startIndex)
            if (queue.current == null) {
                releasePlayer()
                wantsPlayback = false
                ended = false
                publish(PlaybackState())
            } else loadCurrent(play)
        }
    }

    fun play() = onMain {
        if (queue.current == null) return@onMain
        if (ended || player == null) loadCurrent(true)
        else {
            wantsPlayback = true
            publish(currentState.copy(error = null))
            runPlayerCommand { it.play() }
        }
    }

    fun pause() = onMain {
        wantsPlayback = false
        runPlayerCommand { it.pause() }
        publish(currentState.copy(playing = false))
    }

    fun toggle() = onMain { if (wantsPlayback || currentState.playing) pause() else play() }

    /** A deliberate next press wraps; reaching the end of the queue naturally does not. */
    fun next() = onMain { if (queue.moveNext(wrap = true)) loadCurrent(wantsPlayback || currentState.playing) }

    /** Restart a track after three seconds; otherwise select the preceding track. */
    fun previous() = onMain {
        if (queue.current == null) return@onMain
        if (currentState.positionMs > 3_000L) seekTo(0L)
        else {
            queue.movePrevious()
            loadCurrent(wantsPlayback || currentState.playing)
        }
    }

    fun seekTo(ms: Long) = onMain {
        val upper = currentState.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = ms.coerceIn(0L, upper)
        val active = player ?: return@onMain
        try {
            if (!active.isSeekable) return@onMain
            if (active.setTime(target) < 0) {
                publish(currentState.copy(error = "Seeking is unavailable for this audio file."))
                return@onMain
            }
            ended = false
            publish(currentState.copy(positionMs = target, error = null))
        } catch (exception: Exception) {
            wantsPlayback = false
            releasePlayer()
            publish(currentState.copy(playing = false, error = readableError(exception)))
        }
    }

    fun setVolume(percent: Int) = onMain {
        volume = percent.coerceIn(0, 100)
        runPlayerCommand { it.setVolume(volume) }
    }

    override fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) closeOnMain()
        else main.post { closeOnMain() }
    }

    private fun closeOnMain() {
        if (closed) return
        closed = true
        wantsPlayback = false
        releasePlayer()
        libVlc.release()
        currentState = currentState.copy(playing = false)
        onState(currentState)
    }

    private fun loadCurrent(autoplay: Boolean) {
        val track = queue.current ?: return
        releasePlayer()
        val thisGeneration = generation
        wantsPlayback = autoplay
        ended = false
        publish(PlaybackState(track = track, queueIndex = queue.index, queueSize = queue.size))
        try {
            val uri = localUri(track.uri) ?: error("Unsupported audio URI")
            val created = MediaPlayer(libVlc)
            player = created
            val media = if (uri.scheme.equals("content", ignoreCase = true)) {
                // Keep the provider FD alive until this player's native media is released.
                descriptor = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: error("The selected audio file is unavailable")
                Media(libVlc, requireNotNull(descriptor))
            } else Media(libVlc, uri)
            try {
                created.setEventListener { event ->
                    onMain {
                        if (generation == thisGeneration && player === created) handleEvent(created, event)
                    }
                }
                media.addOption(":no-video")
                media.addOption(":no-spu")
                created.media = media
            } finally {
                // MediaPlayer retains its own media reference after setMedia().
                media.release()
            }
            created.setVolume(volume)
            if (autoplay) created.play()
        } catch (exception: Exception) {
            wantsPlayback = false
            releasePlayer()
            publish(currentState.copy(playing = false, error = readableError(exception)))
        }
    }

    private fun handleEvent(active: MediaPlayer, event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                if (!wantsPlayback) active.pause()
                else publish(currentState.copy(playing = true, durationMs = active.length.coerceAtLeast(0), error = null))
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> publish(currentState.copy(playing = false))
            MediaPlayer.Event.TimeChanged -> {
                val updated = currentState.copy(positionMs = event.timeChanged.coerceAtLeast(0))
                // Native callbacks update state directly; no timer wakes an idle player.
                if (abs(updated.positionMs - lastNotifiedPosition) >= 900L) publish(updated)
                else currentState = updated
            }
            MediaPlayer.Event.LengthChanged -> publish(currentState.copy(durationMs = event.lengthChanged.coerceAtLeast(0)))
            MediaPlayer.Event.EndReached -> {
                if (wantsPlayback && queue.moveNext(wrap = false)) loadCurrent(true)
                else {
                    wantsPlayback = false
                    ended = true
                    publish(currentState.copy(playing = false, positionMs = currentState.durationMs))
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                wantsPlayback = false
                releasePlayer()
                publish(currentState.copy(playing = false, error = "VLC could not play this file. Choose another audio file or grant file access again."))
            }
        }
    }

    private fun releasePlayer() {
        ++generation
        val previous = player
        player = null
        runCatching { previous?.setEventListener(null) }
        runCatching { previous?.stop() }
        runCatching { previous?.release() }
        runCatching { descriptor?.close() }
        descriptor = null
    }

    private fun runPlayerCommand(command: (MediaPlayer) -> Unit) {
        try { player?.let(command) }
        catch (exception: Exception) {
            wantsPlayback = false
            releasePlayer()
            publish(currentState.copy(playing = false, error = readableError(exception)))
        }
    }

    private fun publish(updated: PlaybackState) {
        val changed = currentState != updated
        currentState = updated
        lastNotifiedPosition = updated.positionMs
        if (changed) onState(updated)
    }

    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) action()
        } else main.post { if (!closed) action() }
    }

    private fun localUri(value: String): Uri? {
        if (value.length !in 1..8192) return null
        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase()) {
            "content" -> uri.takeIf { !it.authority.isNullOrBlank() }
            "file" -> uri.takeIf {
                (it.authority.isNullOrBlank() || it.authority == "localhost") && it.path?.startsWith('/') == true
            }
            else -> null
        }
    }

    private fun readableError(exception: Exception): String = when (exception) {
        is SecurityException -> "File access was lost. Choose the audio file again."
        is java.io.FileNotFoundException -> "The selected audio file is unavailable. Choose it again."
        else -> "VLC could not open the selected audio file."
    }
}

