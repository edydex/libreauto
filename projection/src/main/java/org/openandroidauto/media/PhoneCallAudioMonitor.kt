package org.openandroidauto.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import java.io.Closeable

/** API 31+ event-driven audio-mode observation; no phone-state or microphone permission. */
internal class PhoneCallAudioMonitor(context: Context, private val onMode: (Int) -> Unit) : Closeable {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var closed = false
    private var registered = false
    private val listener = AudioManager.OnModeChangedListener { mode -> if (!closed) onMode(mode) }

    init {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio?.addOnModeChangedListener(context.mainExecutor, listener)
                registered = audio != null
                // Register first, then sample, so a call already underway also mutes the new session.
                audio?.mode?.let(onMode)
            }
        } catch (_: SecurityException) {
            close()
        } catch (_: IllegalStateException) {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered && Build.VERSION.SDK_INT >= 31) {
            try { audio?.removeOnModeChangedListener(listener) }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
            registered = false
        }
    }
}
