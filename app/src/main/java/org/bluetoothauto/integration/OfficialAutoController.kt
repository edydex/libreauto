// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import rikka.shizuku.Shizuku

/** Optional explicit mode changes only; status()/app launch never changes Android Auto. */
object OfficialAutoController {
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST = 816
    private const val PREFS = "libreauto_official_auto"
    private const val AUTOMATIC = "automatic_switching"
    private const val ORIGINAL = "original_enabled_state"
    private const val APPLIED = "applied_enabled_state"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var busy = false
    private var operation = 0L

    enum class Availability { NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED, UNSUPPORTED, AVAILABLE }
    enum class Kind { READY, MANUAL, FAILED }
    data class Result(val kind: Kind, val message: String)
    data class Status(val availability: Availability, val automatic: Boolean, val restorePending: Boolean,
                      val androidAutoInstalled: Boolean, val androidAutoEnabled: Boolean, val message: String)
    private data class PackageState(val state: Int, val enabled: Boolean, val changed: Boolean = false)

    fun isAutomaticSwitchingEnabled(context: Context) = preferences(context).getBoolean(AUTOMATIC, false)
    /** Persist only. Caller follows this explicit UI action with applyForMode() to apply or restore. */
    fun setAutomaticSwitchingEnabled(context: Context, enabled: Boolean): Boolean =
        preferences(context).edit().putBoolean(AUTOMATIC, enabled).commit()

    fun status(context: Context): Status {
        val app = context.applicationContext
        val installed = try { app.packageManager.getApplicationInfo(ANDROID_AUTO_PACKAGE, 0); true }
            catch (_: Exception) { false }
        val enabled = installed && try { app.packageManager.getApplicationInfo(ANDROID_AUTO_PACKAGE, 0).enabled }
            catch (_: Exception) { false }
        val availability = availability(app)
        val pending = hasJournal(app)
        val message = when (availability) {
            Availability.NOT_INSTALLED -> "Optional: install Shizuku for switching Android Auto automatically."
            Availability.NOT_RUNNING -> "Start Shizuku using wireless debugging or a computer. It needs restarting after a phone reboot."
            Availability.PERMISSION_REQUIRED -> "Allow LibreAuto access in Shizuku to enable automatic switching."
            Availability.UNSUPPORTED -> "This helper requires Shizuku API 13 in ADB-shell mode."
            Availability.AVAILABLE -> "Shizuku is ready. Only explicit car-display changes can alter Android Auto."
        } + if (pending) " Android Auto has a saved state to restore when leaving LibreAuto mode." else ""
        return Status(availability, isAutomaticSwitchingEnabled(app), pending, installed, enabled, message)
    }

    fun requestPermission(context: Context): Status {
        if (availability(context) == Availability.PERMISSION_REQUIRED) {
            try { Shizuku.requestPermission(PERMISSION_REQUEST) } catch (_: Exception) { }
        }
        return status(context)
    }

    /** Opens the ordinary Android settings page. Opening a page is never reported as a successful switch. */
    fun openSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("com.google.android.projection.gearhead.settings" ).setPackage(ANDROID_AUTO_PACKAGE),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO_PACKAGE")))
        return intents.any { intent -> try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: RuntimeException) { false } }
    }

    fun openShizuku(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: RuntimeException) { false }
    }

    /** Does not start projection. A durable journal precedes disabling; restore uses compare-and-set. */
    fun applyForMode(context: Context, mode: CarDisplayMode, onResult: (Result) -> Unit) {
        onMain {
            val app = context.applicationContext
            val disable = mode == CarDisplayMode.LIBREAUTO && isAutomaticSwitchingEnabled(app)
            val pending = hasJournal(app)
            // Check even for the no-journal fast path: an older timed-out worker may not have
            // written its journal yet, and must settle before any new mode can claim readiness.
            if (busy) { onResult(Result(Kind.FAILED, "An Android Auto change is already in progress.")); return@onMain }
            if (!disable && !pending) {
                onResult(localResult(app, mode))
                return@onMain
            }
            val available = status(app)
            if (!available.androidAutoInstalled) {
                onResult(Result(Kind.MANUAL, "Official Android Auto must remain installed for the current projection compatibility mode."))
                return@onMain
            }
            if (available.availability != Availability.AVAILABLE) {
                onResult(Result(Kind.MANUAL, available.message + if (pending) " Restore is pending; Android Auto may remain paused." else " Automatic switching has not been applied."))
                return@onMain
            }
            busy = true
            val operationToken = ++operation
            val args = Shizuku.UserServiceArgs(ComponentName(app, OfficialAutoShellService::class.java))
                .tag("libreauto-official-aa-state").processNameSuffix("aa_switch")
                .version(1).daemon(false).debuggable(false)
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            var done = false
            var connection: ServiceConnection? = null
            var connected = false
            var worker: Thread? = null
            var timeout: Runnable? = null
            fun finish(result: Result) {
                if (done) return
                done = true
                cancelled.set(true)
                timeout?.let { main.removeCallbacks(it) }
                worker?.interrupt()
                try { connection?.let { Shizuku.unbindUserService(args, it, true) } } catch (_: Exception) { }
                // Binder calls are not necessarily interrupted by Thread.interrupt(). Keep serialization
                // until the old worker actually returns so a timed-out pause cannot race a new restore.
                busy = worker?.isAlive == true
                onResult(result)
            }
            timeout = Runnable { finish(Result(Kind.FAILED, "Android Auto switching timed out. No successful switch was assumed; retry to recover its saved state.")) }
            main.postDelayed(requireNotNull(timeout), 12_000)
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (done || connected) return
                    connected = true
                    worker = Thread({
                        val result = try { applyWithBinder(app, mode, disable, service) { !cancelled.get() } }
                        catch (_: Exception) { Result(Kind.FAILED, "Android Auto could not be changed. Any saved restore state is retained; reconnect Shizuku and retry.") }
                        onMain {
                            if (done) {
                                if (operation == operationToken) busy = false
                            }
                            else {
                                // All package-state transactions and journal writes have now finished.
                                worker = null
                                finish(result)
                            }
                        }
                    }, "LibreAuto-AA-switch").apply { isDaemon = true; start() }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    onMain { finish(Result(Kind.FAILED, "The switching helper stopped. Retry to check Android Auto and recover its saved state.")) }
                }
            }
            try { Shizuku.bindUserService(args, requireNotNull(connection)) }
            catch (_: Exception) { finish(Result(Kind.FAILED, "The Android Auto switching helper could not start.")) }
        }
    }

    private fun applyWithBinder(context: Context, mode: CarDisplayMode, disable: Boolean, binder: IBinder, active: () -> Boolean): Result {
        check(active())
        var current = transact(binder)
        check(active())
        val prefs = preferences(context)
        if (disable) {
            if (hasJournal(context)) {
                val applied = prefs.getInt(APPLIED, -1)
                val original = prefs.getInt(ORIGINAL, -1)
                if (!OfficialAutoStatePolicy.valid(original) || !OfficialAutoStatePolicy.valid(applied))
                    return Result(Kind.FAILED, "Android Auto's saved restore state is invalid; it has not been changed.")
                if (current.state == applied) return Result(Kind.READY, "LibreAuto selected. Official Android Auto is paused. Reconnect the car to use LibreAuto.")
                // A user/system changed the setting since our last operation. Honor that state and acquire anew only on this explicit action.
                check(active())
                if (!clearJournal(context)) return Result(Kind.FAILED, "The previous Android Auto restore state could not be cleared.")
            }
            if (OfficialAutoStatePolicy.shouldDisable(current.state, current.enabled)) {
                check(active())
                if (!prefs.edit().putInt(ORIGINAL, current.state).putInt(APPLIED, OfficialAutoStatePolicy.DISABLED_USER).commit())
                    return Result(Kind.FAILED, "Android Auto's original state could not be saved, so it has not been changed.")
                check(active())
                current = transact(binder, current.state, OfficialAutoStatePolicy.DISABLED_USER)
                if (!OfficialAutoStatePolicy.ownsConfirmedPause(current.changed, current.state)) {
                    // A compare failure means we never owned the change. In particular, a user
                    // independently disabling AA to the same value must not later be undone.
                    check(active())
                    if (!clearJournal(context)) return Result(Kind.FAILED, "Android Auto changed during switching and its restore record could not be cleared.")
                    return Result(Kind.MANUAL, "Android Auto's setting changed during switching. Its newer setting has been preserved; check it before reconnecting.")
                }
                if (current.enabled) return Result(Kind.FAILED, "Android Auto's paused state could not be confirmed. Its saved restore state is retained.")
            }
            return Result(Kind.READY, "LibreAuto selected. Official Android Auto is paused. Reconnect the car to use LibreAuto.")
        }
        if (hasJournal(context)) {
            val original = prefs.getInt(ORIGINAL, -1)
            val applied = prefs.getInt(APPLIED, -1)
            if (!OfficialAutoStatePolicy.valid(original) || !OfficialAutoStatePolicy.valid(applied))
                return Result(Kind.FAILED, "Android Auto's saved restore state is invalid; it has not been changed.")
            val restore = OfficialAutoStatePolicy.restoreTarget(current.state, original, applied)
            if (restore != null) {
                check(active())
                current = transact(binder, current.state, restore)
                if (current.changed && current.state != restore) {
                    check(active())
                    if (!clearJournal(context)) return Result(Kind.FAILED, "Android Auto changed during restoration and its restore record could not be cleared.")
                    return Result(Kind.MANUAL, "Android Auto's setting changed during restoration. Its newer setting has been preserved; check it before reconnecting.")
                }
                if (!current.changed && current.state == applied)
                    return Result(Kind.FAILED, "Android Auto could not be restored. Its saved state is retained for retry.")
            }
            check(active())
            if (!clearJournal(context)) return Result(Kind.FAILED, "Android Auto was checked, but its restore record could not be cleared.")
        }
        if (mode == CarDisplayMode.OFFICIAL_AA && !current.enabled)
            return Result(Kind.MANUAL, "Android Auto was already disabled outside LibreAuto. Enable it in Android settings, then reconnect the car.")
        return Result(Kind.READY, when (mode) {
            CarDisplayMode.OFFICIAL_AA -> "Official Android Auto selected. Reconnect the car to switch displays."
            CarDisplayMode.BLUETOOTH -> "Bluetooth stereo selected. LibreAuto projection is off."
            CarDisplayMode.LIBREAUTO -> "Automatic Android Auto switching is off. LibreAuto projection remains available for a manual connection."
        })
    }

    private fun transact(binder: IBinder, expected: Int? = null, target: Int? = null): PackageState {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(OfficialAutoShellService.DESCRIPTOR)
            if (expected != null) { data.writeInt(expected); data.writeInt(requireNotNull(target)) }
            if (!binder.transact(if (expected == null) OfficialAutoShellService.READ else OfficialAutoShellService.COMPARE_SET, data, reply, 0))
                throw IllegalStateException("Unsupported switching service")
            reply.readException()
            val state = reply.readInt()
            val enabled = reply.readInt() != 0
            val changed = reply.readInt() != 0
            check(OfficialAutoStatePolicy.valid(state))
            return PackageState(state, enabled, changed)
        } finally { data.recycle(); reply.recycle() }
    }
    private fun localResult(context: Context, mode: CarDisplayMode): Result {
        val state = status(context)
        return when (mode) {
            CarDisplayMode.BLUETOOTH -> Result(Kind.READY, "Bluetooth stereo selected. LibreAuto projection is off.")
            CarDisplayMode.LIBREAUTO -> Result(Kind.MANUAL, "LibreAuto selected. Automatic Android Auto switching is off; configure the head unit or Android Auto manually if it connects first.")
            CarDisplayMode.OFFICIAL_AA -> when {
                !state.androidAutoInstalled -> Result(Kind.MANUAL, "Install official Android Auto before connecting the car.")
                !state.androidAutoEnabled -> Result(Kind.MANUAL, "Android Auto is disabled. Enable it in Android settings, then reconnect the car.")
                else -> Result(Kind.READY, "Official Android Auto selected. Reconnect the car to switch displays.")
            }
        }
    }
    private fun availability(context: Context): Availability {
        val installed = try { context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0).enabled } catch (_: Exception) { false }
        if (!installed) return Availability.NOT_INSTALLED
        return try {
            when {
                !Shizuku.pingBinder() -> Availability.NOT_RUNNING
                Shizuku.getVersion() < 13 || Shizuku.getUid() != 2000 -> Availability.UNSUPPORTED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Availability.PERMISSION_REQUIRED
                else -> Availability.AVAILABLE
            }
        } catch (_: Exception) { Availability.NOT_RUNNING }
    }
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hasJournal(context: Context) = preferences(context).contains(ORIGINAL) || preferences(context).contains(APPLIED)
    private fun clearJournal(context: Context) = preferences(context).edit().remove(ORIGINAL).remove(APPLIED).commit()
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
