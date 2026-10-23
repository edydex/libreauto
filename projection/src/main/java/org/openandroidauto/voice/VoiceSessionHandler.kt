// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.voice

/** Optional, explicitly installed per-dashboard voice input. Lite supplies no recognizer.
 *
 * Readiness is true only while the handler can consume car PCM. The service keeps
 * the car microphone closed before that signal and after stop/close. Callbacks
 * must be delivered on the Android main thread. acceptPcm consumes the array
 * synchronously; callers wipe it on return, so an asynchronous handler must copy
 * into its own bounded queue and wipe that owned copy after use.
 */
interface VoiceSessionHandler : AutoCloseable {
    val requiresCarMicrophone: Boolean
    val preparationTimeoutMs: Long get() = 15_000L
    fun setReadinessListener(listener: (Boolean) -> Unit)
    fun setEndedListener(listener: () -> Unit)
    /** Bind results to this caller-owned routing token (message reply or contact-name search).
     * Never substitute a later purpose, target or selection.
     * False means no turn started; it must leave readiness false. */
    fun request(replyToken: Long): Boolean
    fun acceptPcm(pcm: ByteArray, sampleRate: Int): Boolean
    fun stop()
    override fun close()
}
