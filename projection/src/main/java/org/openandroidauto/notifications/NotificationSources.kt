package org.openandroidauto.notifications

import android.content.Context

/** A small explicit allow-list: other apps' notifications are never retained. */
object NotificationSources {
    data class Source(val packageName: String, val fallbackName: String)
    val supported = listOf(
        Source("com.google.android.apps.messaging", "Google Messages"),
        Source("com.android.messaging", "Messages"),
        Source("com.samsung.android.messaging", "Samsung Messages"),
        Source("org.telegram.messenger", "Telegram"),
        Source("org.telegram.messenger.web", "Telegram (direct download)"),
        Source("org.thoughtcrime.securesms", "Signal")
    )
    private const val PREFS = "messaging_sources"
    fun selected(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return supported.filter { prefs.getBoolean(it.packageName, false) }.map { it.packageName }.toSet()
    }
    fun select(context: Context, packageName: String, selected: Boolean) {
        if (supported.none { it.packageName == packageName }) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(packageName, selected).apply()
        MessagingNotifications.refreshSources(context)
    }
}
