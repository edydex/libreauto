// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import org.bluetoothauto.integration.CarProfile
import org.bluetoothauto.integration.CarProfiles
import org.bluetoothauto.integration.CarProfilesCodec
import org.bluetoothauto.integration.CarDisplayMode
import org.bluetoothauto.integration.CarDisplayController
import org.bluetoothauto.integration.CarProfileDefaults
import org.openandroidauto.integration.ProjectionAvailability

/** Phone-only, explicit paired-device picker. Opening this screen never scans or changes mode. */
class CarProfilesActivity : Activity() {
    private var feedback = ""
    private var pendingEnable = false
    private var pendingPicker = false
    private fun dp(value: Int) = PlayerUi.dp(this, value)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingEnable = state?.getBoolean("enable") ?: false
        pendingPicker = state?.getBoolean("picker") ?: false
        feedback = state?.getString("feedback").orEmpty()
        CarProfiles.initialize(this); build()
    }
    override fun onResume() { super.onResume(); CarProfiles.syncReceiver(this); build() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("enable", pendingEnable); out.putBoolean("picker", pendingPicker)
        out.putString("feedback", feedback); super.onSaveInstanceState(out)
    }
    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(PlayerUi.background); setPadding(dp(16), 0, dp(16), 0)
        }
        setContentView(root)
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(PlayerUi.icon(this, "back", "Back to car settings") { finish() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        toolbar.addView(PlayerUi.label(this, "Your cars", 23, true)); root.addView(toolbar)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(24)) }
        root.addView(ScrollView(this).apply { addView(page) })
        fun hint(text: String) { page.addView(PlayerUi.label(this, text, 14, false, PlayerUi.muted).apply { setPadding(0, dp(6), 0, dp(14)) }) }
        val result = runCatching { CarProfiles.profiles(this) }
        val cars = result.getOrDefault(emptyList())
        hint("Android Auto is preferred for compatible car screens, and the music player remains available for Bluetooth stereos. Car preferences are optional: add one for a different projector or a Bluetooth-only override.")
        val enabled = CarProfiles.enabled(this)
        page.addView(Switch(this).apply {
            text = "Apply saved car preferences"; textSize = 17f; setTextColor(PlayerUi.ink)
            thumbTintList = ColorStateList.valueOf(PlayerUi.accent); minHeight = dp(58)
            isEnabled = result.isSuccess
            isChecked = enabled && CarProfiles.missingPermissions(this@CarProfilesActivity, cars).isEmpty()
            setOnCheckedChangeListener { _, checked ->
                if (!checked) { CarProfiles.setEnabled(this@CarProfilesActivity, false); feedback = "Automatic selection is off. Your current connection stays as it is."; build() }
                else enable()
            }
        })
        hint("Nearby devices identifies your saved car. Notifications provide a connection button when Android needs your attention. No Bluetooth scanning, Wi-Fi guessing or always-running monitor.")
        if (result.isFailure) hint("Saved car profiles could not be read. Your music and current display choice have been kept. Add a car to replace the unreadable profile list.")
        if (feedback.isNotBlank()) hint(feedback)
        if (cars.isEmpty()) hint("No overrides needed? You can leave this empty. Official Android Auto handles compatible cars without a profile. To add a preference, pair the car in Android’s Bluetooth settings first.")
        cars.forEach { car ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                background = PlayerUi.shape(this@CarProfilesActivity, PlayerUi.card, 16)
            }
            row.addView(PlayerUi.label(this, car.name, 18, true))
            row.addView(PlayerUi.label(this, car.mode.label, 14, false, PlayerUi.muted))
            val actions = LinearLayout(this)
            actions.addView(PlayerUi.button(this, "Change mode", false) { chooseMode(car) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(PlayerUi.button(this, "Remove", false) {
                AlertDialog.Builder(this).setTitle("Remove ${car.name}?").setMessage("This removes only its LibreAuto profile; the phone stays paired.")
                    .setPositiveButton("Remove") { _, _ ->
                        CarProfiles.saveProfiles(this, CarProfiles.profiles(this).filterNot { it.address == car.address }); build()
                    }.setNegativeButton("Cancel", null).show()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(actions); page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        page.addView(PlayerUi.button(this, "Add paired car", true) { openPairedCars() }, LinearLayout.LayoutParams(-1, dp(52)))
        hint("A manual mode choice lasts for the current car connection. Automatic selection resumes on the next new connection. If two saved cars connect together, choose the mode yourself.")
        hint("Adding a car keeps your preferred projector; Change mode is optional. LibreAuto tries wireless projection when switching and permissions are ready. Switching projectors may need the optional helper or manual setup. Android may require opening the connection screen. Music never starts playing automatically.")
    }
    private fun enable() {
        val missing = CarProfiles.missingPermissions(this)
        if (missing.isNotEmpty()) { pendingEnable = true; requestAccess(missing); return }
        feedback = if (CarProfiles.setEnabled(this, true)) "Automatic selection is on. Your next saved-car Bluetooth connection will select its mode."
            else "Access is missing. Review app permissions and try again."
        build()
    }
    private fun requestAccess(permissions: List<String>) {
        if (Build.VERSION.SDK_INT < 31) { feedback = "Bluetooth access is unavailable on this phone."; build(); return }
        val requested = if (Manifest.permission.ACCESS_FINE_LOCATION in permissions)
            (permissions + Manifest.permission.ACCESS_COARSE_LOCATION).distinct() else permissions
        requestPermissions(requested.toTypedArray(), 74)
    }
    private fun openPairedCars() {
        val missing = if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        if (missing.isNotEmpty()) { pendingPicker = true; requestAccess(missing); return }
        val cars = try {
            val preferred = CarProfileDefaults.preferredProjection(CarDisplayController.preferredProjector(this), Build.VERSION.SDK_INT)
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().mapNotNull { device ->
                CarProfilesCodec.address(device.address)?.let { CarProfile(it, device.name?.trim()?.take(80)?.takeIf(String::isNotBlank) ?: "Paired car", preferred) }
            }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        } catch (_: SecurityException) { feedback = "Allow Nearby devices, then add the paired car again."; build(); return }
          catch (_: RuntimeException) { feedback = "Paired devices are unavailable. Check Bluetooth settings, then try again."; build(); return }
        if (cars.isEmpty()) { feedback = "No paired devices were found. Pair your car in Android Bluetooth settings first."; build(); return }
        AlertDialog.Builder(this).setTitle("Choose your paired car").setItems(cars.map { it.name }.toTypedArray()) { _, index ->
            val selected = cars[index]
            if (runCatching { CarProfiles.profiles(this) }.getOrDefault(emptyList()).any { it.address == selected.address }) {
                feedback = "That car is already saved. Use Change mode below if you want a different preference."; build()
            } else saveProfile(selected)
        }.setNegativeButton("Cancel", null).show()
    }
    private fun chooseMode(car: CarProfile) {
        val modes = CarDisplayMode.entries.filter { it != CarDisplayMode.LIBREAUTO || ProjectionAvailability.isSupported() }
        AlertDialog.Builder(this).setTitle("${car.name} uses…").setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(car.mode)) { dialog, index ->
            dialog.dismiss(); saveProfile(car.copy(mode = modes[index]))
        }.setNegativeButton("Cancel", null).show()
    }
    private fun saveProfile(car: CarProfile) {
        val wasEnabled = CarProfiles.enabled(this)
        val profiles = runCatching { CarProfiles.profiles(this) }.getOrDefault(emptyList())
        if (profiles.size >= CarProfilesCodec.MAX_PROFILES && profiles.none { it.address == car.address }) {
            feedback = "Remove an unused car profile before adding another."; build(); return
        }
        try { CarProfiles.saveProfiles(this, profiles.filterNot { it.address == car.address } + car) }
        catch (_: RuntimeException) { feedback = "The car preference could not be saved. Your other settings have been kept."; build(); return }
        feedback = "Saved. ${car.mode.label} applies on the next Bluetooth connection. Use Change mode for an override."
        if (wasEnabled && !CarProfiles.enabled(this)) { pendingEnable = true; requestAccess(CarProfiles.missingPermissions(this)) }
        else build()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 74) return
        val enable = pendingEnable; val picker = pendingPicker
        pendingEnable = false; pendingPicker = false
        if (enable) feedback = if (CarProfiles.setEnabled(this, true)) "Automatic selection is on."
            else "Automatic selection stays off until its permissions are allowed."
        build()
        if (picker && (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) openPairedCars()
    }
}
