// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.graphics.Bitmap
import org.bluetoothauto.model.*
import org.bluetoothauto.playback.TrackPresentation

/** Process-local, main-thread state. No route, notification text or GPS history is persisted. */
object AppState {
    var playback = PlaybackState()
    var presentation: TrackPresentation? = null
    var guidance: Guidance? = null
    var position: Position? = null
    var artwork: Bitmap? = null
    var turnGeometry:TurnGeometry?=null
    var artworkStatus = "Start a session to preview stereo artwork"
    var metadataTitle = "LibreAuto"
    var running = false
    var listenerConnected = false
    var demo = false
    var error: String? = null
    val observers = linkedSetOf<() -> Unit>()
    fun changed() { observers.toList().forEach { it() } }
}

object GuidanceHub {
    private var currentKey: String? = null
    var current: Guidance? = null; private set
    val observers = linkedSetOf<(Guidance?) -> Unit>()
    fun publish(key: String, guidance: Guidance) {
        currentKey = key; current = guidance
        if(!AppState.demo)AppState.guidance = guidance
        observers.toList().forEach { it(guidance) }
        AppState.changed()
    }
    fun remove(key: String) { if (key == currentKey) clear() }
    fun clear() {
        currentKey = null; current = null; if(!AppState.demo)AppState.guidance = null
        observers.toList().forEach { it(null) }; AppState.changed()
    }
}

/** Fresh message cues only; no backlog or notification history. */
object MessageSpeechHub {
    val observers=linkedSetOf<(String)->Unit>()
    fun publish(text:String){observers.toList().forEach{it(text)}}
}
