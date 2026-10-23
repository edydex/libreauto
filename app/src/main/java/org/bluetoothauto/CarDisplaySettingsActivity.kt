// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import org.bluetoothauto.integration.CarDisplayController
import org.bluetoothauto.integration.CarDisplayMode
import org.bluetoothauto.integration.CarProfiles
import org.bluetoothauto.integration.OfficialAutoController
import org.openandroidauto.integration.ProjectionAvailability
import org.openandroidauto.integration.ProjectionIntegration

/** Phone-only settings. Selecting a mode never starts a connection or a decoder. */
class CarDisplaySettingsActivity : Activity() {
    private var feedback = ""
    private val observer: () -> Unit = { if (!isFinishing && !isDestroyed) build() }
    private fun dp(value: Int) = PlayerUi.dp(this, value)
    override fun onCreate(state: Bundle?) { super.onCreate(state); feedback=state?.getString("feedback").orEmpty(); build() }
    override fun onResume() { super.onResume(); CarDisplayController.addListener(observer); build() }
    override fun onPause() { CarDisplayController.removeListener(observer); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("feedback",feedback); super.onSaveInstanceState(out) }
    private fun build() {
        val busy=CarDisplayController.isSwitching()
        val mode=CarDisplayController.mode(this)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(PlayerUi.background); setPadding(dp(16),0,dp(16),0)
        }
        setContentView(root)
        val toolbar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        toolbar.addView(PlayerUi.icon(this,"back","Back to settings"){finish()},LinearLayout.LayoutParams(dp(48),dp(56)))
        toolbar.addView(PlayerUi.label(this,"Car display",23,true));root.addView(toolbar)
        val page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(12),dp(4),dp(24)) }
        root.addView(ScrollView(this).apply { addView(page) })
        fun hint(text: String) { page.addView(PlayerUi.label(this,text,14,false,PlayerUi.muted).apply { setPadding(0,dp(6),0,dp(14)) }) }
        fun heading(text: String) { page.addView(PlayerUi.label(this,text,15,true,PlayerUi.accent).apply {setPadding(0,dp(16),0,dp(6))}) }
        fun button(text: String, primary: Boolean=false, enabled: Boolean=true, action: () -> Unit) {
            page.addView(PlayerUi.button(this,text,primary,action).apply {isEnabled=enabled && !busy},
                LinearLayout.LayoutParams(-1,dp(52)).apply {bottomMargin=dp(8)})
        }
        hint("Prefer a car-screen dashboard when available. With a Bluetooth-only stereo, the music player and navigation artwork remain available.")
        val choices=RadioGroup(this)
        for (choice in CarDisplayMode.entries) {
            choices.addView(RadioButton(this).apply {
                text=if(choice==CarDisplayMode.OFFICIAL_AA)"Android Auto when available · default" else choice.label
                setTextColor(PlayerUi.ink);textSize=17f;buttonTintList=ColorStateList.valueOf(PlayerUi.accent)
                minHeight=dp(54);isChecked=choice==mode;isEnabled=!busy && (choice!=CarDisplayMode.LIBREAUTO || ProjectionAvailability.isSupported())
                contentDescription="Car display: ${choice.name}"
                setOnClickListener { applyMode(choice, recordPreference = true) }
            })
        }
        page.addView(choices)
        hint(when(mode) {
            CarDisplayMode.BLUETOOTH -> "Car-screen connections stay off. Use Bluetooth playback and navigation artwork."
            CarDisplayMode.LIBREAUTO -> "LibreAuto projection is selected. Open its setup to connect; selecting this mode alone starts no background connection."
            CarDisplayMode.OFFICIAL_AA -> "Official Android Auto connects to compatible cars normally. On other stereos, use the player over Bluetooth. LibreAuto projection stays off."
        })
        if(!ProjectionAvailability.isSupported()) hint(ProjectionAvailability.REQUIREMENT_MESSAGE)
        if(busy) hint("Stopping the previous session and applying your choice…")
        if(feedback.isNotBlank()) hint(feedback)
        button("Remember a mode for each car") { startActivity(Intent(this,CarProfilesActivity::class.java)) }
        if(mode==CarDisplayMode.LIBREAUTO) {
            button("OpenAuto setup",true,ProjectionAvailability.isSupported()) {
                startActivity(Intent(this,org.openandroidauto.MainActivity::class.java))
            }
            button("Stop LibreAuto connection",enabled=ProjectionIntegration.isSessionActive()) {
                CarProfiles.onManualModeSelected(applicationContext)
                LibreAutoRuntime.stopProjection { stopped ->
                    feedback=if(stopped)"Connection stopped." else "Connection is still shutting down."; if(!isDestroyed)build()
                }
            }
        }
        button("Official Android Auto settings") { OfficialAutoController.openSettings(this) }
        hint("Change modes while parked, then reconnect to the car. Keep official Android Auto installed for the current LibreAuto compatibility setup.")
        heading("Automatic Android Auto switch · optional")
        val helper=OfficialAutoController.status(this)
        val toggle=Switch(this).apply {
            text="Pause official Android Auto in LibreAuto mode";textSize=16f;setTextColor(PlayerUi.ink)
            isChecked=helper.automatic;isEnabled=!busy;minHeight=dp(60)
            setOnCheckedChangeListener { _, checked ->
                if(!OfficialAutoController.setAutomaticSwitchingEnabled(this@CarDisplaySettingsActivity,checked)) {
                    feedback="The switching preference could not be saved.";build()
                } else {
                    if(checked && helper.availability==OfficialAutoController.Availability.PERMISSION_REQUIRED)
                        OfficialAutoController.requestPermission(this@CarDisplaySettingsActivity)
                    applyMode(CarDisplayController.mode(this@CarDisplaySettingsActivity))
                }
            }
        }
        page.addView(toggle)
        hint("Uses Shizuku only when you change modes. LibreAuto saves Android Auto’s previous enabled state and restores its own change when you leave this mode or turn this option off.")
        hint(helper.message)
        if(helper.availability==OfficialAutoController.Availability.PERMISSION_REQUIRED) button("Allow switching helper") {
            OfficialAutoController.requestPermission(this);feedback="After allowing access, tap Apply selected mode.";build()
        }
        if(helper.availability!=OfficialAutoController.Availability.AVAILABLE) button("Open Shizuku") {
            if(!OfficialAutoController.openShizuku(this)) {
                feedback="Install Shizuku from shizuku.rikka.app, start it, and return here. The music player works without it.";build()
            }
        }
        button("Apply selected mode") { applyMode(CarDisplayController.mode(this)) }
        hint("Without the helper, LibreAuto still saves your choice and controls its own projector. You may need to change official Android Auto’s connection settings manually.")
    }
    private fun applyMode(mode: CarDisplayMode, recordPreference: Boolean = false) {
        if(recordPreference && mode!=CarDisplayMode.BLUETOOTH && !CarDisplayController.recordPreferredProjector(this,mode)) {
            feedback="The dashboard preference could not be saved.";build();return
        }
        CarProfiles.onManualModeSelected(applicationContext)
        feedback=""
        CarDisplayController.selectMode(this,mode,LibreAutoRuntime) { result ->
            ProjectionIntegration.syncUsbEntry(applicationContext)
            feedback=result.message
            if(!isFinishing && !isDestroyed)build()
        }
    }
}
