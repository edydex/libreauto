// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.integration

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.openandroidauto.ProjectionService
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import androidx.annotation.ChecksSdkIntAtLeast

/** Host-owned opt-in. Loading the music player never constructs a projection session. */
object ProjectionIntegration {
    interface Delegate {
        /** Opening setup must not require stopping currently playing music. */
        fun isProjectionSelected(): Boolean = isProjectionAllowed()
        fun isProjectionAllowed(): Boolean
        /** Release the host's player owner asynchronously before the final start gate. */
        fun prepareToStart(context: Context, onReady: (Boolean) -> Unit) { onReady(true) }
        /** Runs before foreground promotion, call monitoring, wake locks or transports. */
        fun onProjectionStarting()
        /** Verified transport selection from the active service; null for USB/TCP. */
        fun onProjectionCarSelected(address: String?) {}
        /** The negotiated car protocol became active, not merely a service start request. */
        fun onProjectionReady() {}
        /** Runs only after every session resource joined and the Service was destroyed. */
        fun onProjectionStopped()
    }

    @Volatile var delegate: Delegate? = null
    private val session = AtomicReference<Session?>(null)

    @JvmStatic @ChecksSdkIntAtLeast(api = ProjectionAvailability.MINIMUM_ANDROID_API)
    fun isProjectionAllowed(): Boolean = isProjectionAllowed(Build.VERSION.SDK_INT)
    internal fun isProjectionAllowed(sdkInt: Int): Boolean =
        ProjectionAvailability.isSupported(sdkInt) && runCatching { delegate?.isProjectionAllowed() == true }.getOrDefault(false)

    @JvmStatic @ChecksSdkIntAtLeast(api = ProjectionAvailability.MINIMUM_ANDROID_API)
    fun isProjectionSelected(): Boolean = isProjectionSelected(Build.VERSION.SDK_INT)
    internal fun isProjectionSelected(sdkInt: Int): Boolean =
        ProjectionAvailability.isSupported(sdkInt) && runCatching { delegate?.isProjectionSelected() == true }.getOrDefault(false)

    @JvmStatic fun prepareToStart(context: Context, onReady: (Boolean) -> Unit) {
        val host = delegate
        if (host == null || !isProjectionSelected()) { onReady(false); return }
        val delivered = AtomicBoolean()
        val callback: (Boolean) -> Unit = { ready ->
            if (delivered.compareAndSet(false, true)) {
                Handler(Looper.getMainLooper()).post {
                    onReady(ready && host === delegate && isProjectionAllowed())
                }
            }
        }
        try { host.prepareToStart(context, callback) } catch (_: Exception) { callback(false) }
    }

    @JvmStatic fun isSessionActive(): Boolean = session.get() != null

    internal fun reportCarSelected(address: String?) { session.get()?.reportCarSelected(address) }
    internal fun reportReady() { session.get()?.reportReady() }

    /** Only our optional USB alias is changed. No package-wide or third-party changes. */
    @JvmStatic fun syncUsbEntry(context: Context) {
        val component = ComponentName(context.packageName, "org.openandroidauto.UsbProjectionEntry")
        val desired = if (isProjectionSelected()) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val manager = context.packageManager
        if (manager.getComponentEnabledSetting(component) != desired)
            manager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
    }

    /** Stops an existing owner; never starts or binds to a service. Completion is asynchronous. */
    @JvmStatic fun requestStop(context: Context): Boolean =
        context.applicationContext.stopService(Intent(context.applicationContext, ProjectionService::class.java))

    /** Called by ProjectionService only after its independent exclusive owner gate acquired. */
    internal fun beginSession(sdkInt: Int = Build.VERSION.SDK_INT): Session? {
        if (!ProjectionAvailability.isSupported(sdkInt)) return null
        val host = delegate ?: return null
        if (!runCatching(host::isProjectionAllowed).getOrDefault(false)) return null
        val lease = Session(host)
        if (!session.compareAndSet(null, lease)) return null
        if (runCatching(host::onProjectionStarting).isFailure) {
            lease.finish()
            return null
        }
        // A host may switch mode while releasing its own audio owner in the callback.
        if (!runCatching(host::isProjectionAllowed).getOrDefault(false)) {
            lease.finish()
            return null
        }
        return lease
    }

    internal class Session(private val host: Delegate) {
        fun reportCarSelected(address: String?) { runCatching { host.onProjectionCarSelected(address) } }
        fun reportReady() { runCatching(host::onProjectionReady) }
        /** Exclusive-owner cleanup calls this once; replacement delegates cannot steal it. */
        fun finish() {
            if (session.compareAndSet(this, null)) runCatching(host::onProjectionStopped)
        }
    }
}

object ProjectionAvailability {
    const val MINIMUM_ANDROID_API = 32
    const val REQUIREMENT_MESSAGE = "The music player works on this phone. Car-screen projection currently needs Android 12L or newer."
    const val MODE_MESSAGE = "Car-screen projection is off. Select LibreAuto in Settings → Car display to use it."
    @JvmStatic @ChecksSdkIntAtLeast(api = MINIMUM_ANDROID_API)
    fun isSupported(): Boolean = isSupported(Build.VERSION.SDK_INT)
    @JvmStatic fun isSupported(sdkInt: Int): Boolean = sdkInt >= MINIMUM_ANDROID_API
    @JvmStatic fun unavailableReason(): String? = when {
        !isSupported() -> REQUIREMENT_MESSAGE
        !ProjectionIntegration.isProjectionSelected() -> MODE_MESSAGE
        else -> null
    }
}
