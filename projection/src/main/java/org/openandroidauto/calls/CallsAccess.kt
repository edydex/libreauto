// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.Closeable

/** The opt-in stores a Boolean only. Contact rows and numbers are never cached on disk. */
object CallsAccess {
    private const val PREFS = "calls-access"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply() }
    fun canRead(context: Context): Boolean = enabled(context) && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canCall(context: Context): Boolean = canRead(context) && context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    fun observe(context: Context, onChanged: () -> Unit): Closeable {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "enabled") onChanged() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
