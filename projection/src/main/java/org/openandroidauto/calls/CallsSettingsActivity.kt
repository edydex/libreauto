// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*

/** Phone-only opt-in. Neither Main nor projected UI requests Contacts/Phone permissions automatically. */
class CallsSettingsActivity : Activity() {
    private lateinit var status: TextView
    private var permissionRequestPending = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24) }
        fun text(value: String, size: Float = 17f) = TextView(this).also { it.text = value; it.textSize = size; it.setPadding(0, 10, 0, 10); column.addView(it) }
        fun button(value: String, action: () -> Unit) { column.addView(Button(this).apply { text = value; setOnClickListener { action() } }) }
        text("Calls setup", 27f)
        text("Optional contact search and starred favorites on the car screen. This app reads contact names and phone numbers only while Calls is open or confirming your selected number. It does not keep a contact database, read call logs, change contacts or upload them.")
        text("Dictation only searches for a contact. You review a single name and number, then press Call. Android handles the real call, its audio and normal call controls. Calling is disabled in preview or outside an active car connection.")
        status = text("")
        button("Enable Calls and allow access") {
            if (permissionRequestPending) return@button
            val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) { CallsAccess.setEnabled(this, true); refresh() }
            else { permissionRequestPending = true; requestPermissions(missing.toTypedArray(), REQUEST) }
        }
        button("Disable Calls") { CallsAccess.setEnabled(this, false); refresh() }
        text("Disabling stops this app using Calls. To remove Android's Contacts or Phone permission too, open this app's system settings.")
        button("Open app permission settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        button("Done") { finish() }
        setContentView(ScrollView(this).apply { addView(column) })
        refresh()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) {
            permissionRequestPending = false
            CallsAccess.setEnabled(this, required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
            refresh()
        }
    }
    private fun refresh() {
        status.text = when {
            !CallsAccess.enabled(this) -> "Calls are off"
            !CallsAccess.canRead(this) -> "Calls are off until Contacts access is allowed"
            !CallsAccess.canCall(this) -> "Contacts are allowed; Phone access is needed before placing calls"
            else -> "Calls are enabled. Favorites come from stars in your phone's Contacts app."
        }
    }
    private companion object {
        const val REQUEST = 61
        val required = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
    }
}
