package org.openandroidauto.notifications

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

/** Phone-only setup. No observer, timer, background job, or retained activity. */
class NotificationSettingsView(context: Context) : LinearLayout(context) {
    init { orientation = VERTICAL; refresh() }
    fun refresh() {
        removeAllViews()
        addView(TextView(context).apply {
            text = "Messaging notifications"; textSize = 19f; setTextColor(Color.WHITE)
        })
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        addView(TextView(context).apply {
            text = if (enabled) "Access enabled. Choose which apps may appear in your car."
                else "Enable notification access, then choose apps. Messages stay in memory only while connected."
            setTextColor(Color.LTGRAY)
        })
        addView(Button(context).apply {
            text = if (enabled) "Manage notification access" else "Enable notification access"
            setOnClickListener { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        val selected = NotificationSources.selected(context)
        for (source in NotificationSources.supported) {
            val info = runCatching { context.packageManager.getApplicationInfo(source.packageName, 0) }.getOrNull()
            if (info == null) continue
            addView(CheckBox(context).apply {
                text = context.packageManager.getApplicationLabel(info)
                setTextColor(Color.WHITE)
                isChecked = source.packageName in selected
                setOnCheckedChangeListener { _, checked -> NotificationSources.select(context, source.packageName, checked) }
            })
        }
        addView(TextView(context).apply {
            text = "Replies use the messaging app's own Reply action. Review every reply before Send. No notification history is saved."
            setTextColor(Color.LTGRAY)
        })
    }
}
