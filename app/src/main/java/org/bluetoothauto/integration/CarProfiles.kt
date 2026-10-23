// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.bluetoothauto.CarDisplaySettingsActivity
import org.bluetoothauto.LibreAutoRuntime
import org.bluetoothauto.R
import org.openandroidauto.ProjectionService
import org.openandroidauto.integration.ProjectionAvailability
import org.openandroidauto.integration.ProjectionIntegration

/** Opt-in ACL events only. No scanning, Wi-Fi inference, radio changes or persistent worker. */
object CarProfiles {
    private const val PREFS = "libreauto_car_profiles"
    private const val ENABLED = "enabled"
    private const val PROFILES = "profiles_v1"
    private const val STATE = "connection_state_v1"
    private const val BOOT = "connection_boot"
    private const val CHANNEL = "car-profile-action"
    private const val NOTICE = 63
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val policy = CarProfilesPolicy()
    private var loaded = false
    private var generation = 0L
    private val attempt = CarProfileAttempt()
    private var appContext: Context? = null
    /** Optional trusted provider query, supplied by the host; never derive this from Wi-Fi. */
    var officialProjectionActive: ((Context) -> Boolean)? = null

    fun profiles(context: Context): List<CarProfile> = CarProfilesCodec.decode(prefs(context).getString(PROFILES, null))
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun missingPermissions(context: Context, cars: List<CarProfile> = profiles(context)): List<String> {
        val required = CarProfilesPermissions.required(Build.VERSION.SDK_INT, cars.any { it.mode == CarDisplayMode.LIBREAUTO })
        return required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }
    fun saveProfiles(context: Context, cars: List<CarProfile>) {
        val encoded = CarProfilesCodec.encode(cars)
        val edit = prefs(context).edit().putString(PROFILES, encoded)
        if (missingPermissions(context, cars).isNotEmpty()) edit.putBoolean(ENABLED, false)
        check(edit.commit()) { "Car profiles could not be saved" }
        loaded = false; initialize(context)
    }
    fun setEnabled(context: Context, value: Boolean): Boolean {
        if (value && (runCatching { missingPermissions(context).isEmpty() }.getOrDefault(false).not())) return false
        if (!prefs(context).edit().putBoolean(ENABLED, value).commit()) return false
        if (!value) {
            generation++; policy.clear(); attempt.clear()
            loaded = false
            saveState(context)
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE)
        }
        syncReceiver(context)
        return true
    }
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!enabled(context)) { loaded = false; syncReceiver(context); return }
        if (!loaded) {
            loaded = true
            val p = prefs(context)
            val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
            val cars = runCatching { profiles(context) }.getOrDefault(emptyList())
            policy.restore(p.getString(STATE, null).takeIf { boot >= 0 && p.getInt(BOOT, -2) == boot }, cars.map { it.address }.toSet())
            p.edit().putInt(BOOT, boot).apply()
        }
        syncReceiver(context)
    }
    fun syncReceiver(context: Context) {
        val valid = enabled(context) && runCatching { missingPermissions(context).isEmpty() }.getOrDefault(false)
        val component = ComponentName(context.packageName, CarProfileReceiver::class.java.name)
        val state = if (valid) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (context.packageManager.getComponentEnabledSetting(component) != state)
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
    fun onManualModeSelected(context: Context) {
        if (!enabled(context)) return
        initialize(context)
        generation++; attempt.clear(); policy.manualSelection(); saveState(context)
    }
    fun onProjectionCarSelected(address: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onProjectionCarSelected(address) }; return }
        val actual = CarProfilesCodec.address(address)
        attempt.carSelected(actual, actual != null && policy.canApply(actual))
    }
    fun onProjectionReady() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onProjectionReady() }; return }
        attempt.becameReady()
    }
    fun onProjectionStopped() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onProjectionStopped() }; return }
        val failed = attempt.stopped() ?: return
        val app = appContext ?: return
        if (enabled(app) && policy.canApply(failed) && !CarDisplayController.isSwitching())
            notice(app, "The automatic car connection did not finish. Open LibreAuto to connect.", true)
    }

    fun cancelEvent(context: Context, event: CarProfileEvent) {
        if (!event.cancel()) return
        if (event.ticket == generation) {
            generation++; policy.manualSelection(); attempt.clear(); saveState(context)
            notice(context, "Car setup took longer than Android allows in the background. Open LibreAuto to finish.", true)
        }
    }
    fun handleConnection(context: Context, address: String, connected: Boolean,
                         event: CarProfileEvent = CarProfileEvent(), onDone: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { handleConnection(context, address, connected, event, onDone) }; return }
        if (event.isCancelled) { onDone(); return }
        val app = context.applicationContext
        initialize(app)
        if (!enabled(app) || missingPermissions(app).isNotEmpty()) { syncReceiver(app); onDone(); return }
        val pin = CarProfilesCodec.address(address) ?: run { onDone(); return }
        val cars = profiles(app)
        val chosen = cars.firstOrNull { it.address == pin } ?: run { onDone(); return }
        val decision = if (connected) policy.connect(pin, cars.map { it.address }.toSet()) else policy.disconnect(pin)
        saveState(app)
        when (decision.action) {
            CarProfilesPolicy.Action.IGNORE -> { onDone(); return }
            CarProfilesPolicy.Action.AMBIGUOUS -> {
                generation++; attempt.clear()
                notice(app, "More than one saved car is connected. Open car settings to choose a mode.")
                onDone(); return
            }
            CarProfilesPolicy.Action.DISCONNECTED -> {
                generation++
                if (attempt.disconnected(pin)) {
                    LibreAutoRuntime.stopProjection { onDone() }
                } else onDone()
                return
            }
            CarProfilesPolicy.Action.APPLY -> Unit
        }
        val ticket = ++generation
        event.ticket = ticket
        fun current() = event.canContinue(generation) && enabled(app) && policy.canApply(pin)
        if (chosen.mode == CarDisplayMode.LIBREAUTO && !ProjectionAvailability.isSupported()) {
            notice(app, ProjectionAvailability.REQUIREMENT_MESSAGE); onDone(); return
        }
        if (CarDisplayController.isSwitching() || ProjectionIntegration.isSessionActive() ||
            runCatching { officialProjectionActive?.invoke(app) == true }.getOrDefault(true)) {
            policy.manualSelection(); saveState(app)
            notice(app, "A car display is already active or changing. Your current connection was kept.")
            onDone(); return
        }
        // Avoid stopping music just because its already-selected stereo reconnects.
        val helper = OfficialAutoController.status(app)
        if (CarDisplayController.mode(app) == chosen.mode && chosen.mode != CarDisplayMode.LIBREAUTO && !helper.restorePending) {
            onDone(); return
        }
        val runtime = object : CarDisplayController.Runtime {
            override fun stopProjection(onStopped: (Boolean) -> Unit) =
                LibreAutoRuntime.stopProjection { onStopped(it && current()) }
            override fun stopBluetoothPlayback(onStopped: (Boolean) -> Unit) =
                LibreAutoRuntime.stopBluetoothPlayback { onStopped(it && current()) }
        }
        CarDisplayController.selectMode(app, chosen.mode, runtime) { result ->
            if (!current()) { onDone(); return@selectMode }
            if (result.status != CarDisplayController.Status.READY) {
                notice(app, result.message, chosen.mode == CarDisplayMode.LIBREAUTO)
                onDone(); return@selectMode
            }
            if (chosen.mode != CarDisplayMode.LIBREAUTO) { onDone(); return@selectMode }
            if (!ProjectionAvailability.isSupported()) {
                notice(app, ProjectionAvailability.REQUIREMENT_MESSAGE)
                onDone(); return@selectMode
            }
            ProjectionIntegration.prepareToStart(app) { ready ->
                if (!current()) { onDone(); return@prepareToStart }
                if (!ready) { notice(app, "Open LibreAuto to finish connecting to the car.", true); onDone(); return@prepareToStart }
                attempt.request(pin)
                try {
                    app.startForegroundService(Intent(app, ProjectionService::class.java)
                        .putExtra(ProjectionService.EXTRA_TRANSPORT, "wireless-car")
                        .putExtra(ProjectionService.EXTRA_BLUETOOTH_ADDRESS, pin))
                    // Android may still reject promotion asynchronously. This is only a request,
                    // never a claim that the car accepted projection or that GPS is available.
                    main.postDelayed({
                        if (attempt.pending == pin && current()) {
                            attempt.clear()
                            notice(app, "Open LibreAuto to finish connecting to the car.", true)
                        }
                    }, 8_000)
                } catch (_: RuntimeException) {
                    attempt.clear()
                    notice(app, "Android needs you to open LibreAuto to connect to this car.", true)
                }
                onDone()
            }
        }
    }

    private fun notice(context: Context, message: String, connection: Boolean = false) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Car connection actions", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = if (connection && ProjectionIntegration.isProjectionSelected())
                Intent(context, org.openandroidauto.MainActivity::class.java).putExtra(org.openandroidauto.MainActivity.EXTRA_SETUP_SCREEN, "connection")
            else Intent(context, CarDisplaySettingsActivity::class.java)
            val action = PendingIntent.getActivity(context, NOTICE, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTICE, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("LibreAuto · Car connection").setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(action)
                .setAutoCancel(true).setTimeoutAfter(60_000).build())
        } catch (_: RuntimeException) {
            // Permission/channel/provider availability can change after the explicit opt-in.
            // A missing notification must not crash the receiver or imply a successful connection.
        }
    }
    private fun saveState(context: Context) { prefs(context).edit().putString(STATE, policy.save()).apply() }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
