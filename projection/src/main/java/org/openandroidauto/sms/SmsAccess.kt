// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.os.Build
import java.io.Closeable

/** Explicit opt-in only. No inbox access, contacts or draft payloads are stored. */
object SmsAccess {
    private const val PREFS = "carrier-sms-access"
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply() }
    fun canRead(context: Context) = enabled(context) && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canSend(context: Context) = Build.VERSION.SDK_INT >= 32 && canRead(context) && context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
        context.packageManager.hasSystemFeature(if(Build.VERSION.SDK_INT>=33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY)
    /** Never use SmsManager's implicit fallback or slot zero. Require the OS's chosen SMS default. */
    fun defaultSubscription(): Int = if(Build.VERSION.SDK_INT<32) -1 else try { SubscriptionManager.getDefaultSmsSubscriptionId().takeIf {
        SubscriptionManager.isValidSubscriptionId(it) && it != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
    } ?: -1 } catch (_: RuntimeException) { -1 }
    fun observe(context: Context, changed: () -> Unit): Closeable {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val observer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "enabled") changed() }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(observer) }
    }
}
