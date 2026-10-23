// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import org.bluetoothauto.model.Guidance
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** The owning listener must filter Google Maps' package and track removal by notification key. */
class GoogleMapsNotificationParser(private val context: Context) {
    fun isNavigation(notification: Notification): Boolean = text(notification) != null

    fun parse(notification: Notification): Guidance? {
        val parsed = text(notification) ?: return null
        return Guidance(parsed.instruction, parsed.distanceMeters, parsed.distanceLabel,
            if (parsed.status) null else artwork(notification))
    }

    private fun text(notification: Notification): NotificationTextParser.Parsed? {
        val extras = notification.extras ?: return null
        val fields = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
            .mapNotNull { key -> runCatching { extras.getCharSequence(key)?.toString()?.take(2048) }.getOrNull() }
        val locale: Locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        val parsed = NotificationTextParser.parse(fields, notification.category == Notification.CATEGORY_NAVIGATION,
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0, locale) ?: return null
        val critical = if (Build.VERSION.SDK_INT >= 36) runCatching { notification.shortCriticalText }.getOrNull() else null
        return NotificationTextParser.withCriticalDistance(parsed, critical, locale)
    }

    private fun artwork(notification: Notification): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT < 28) return@runCatching null
        val icon = notification.getLargeIcon() ?: return@runCatching null
        // Do not resolve URI/data icons: they can invoke providers or decode unbounded data.
        if (icon.type !in setOf(Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP, Icon.TYPE_RESOURCE)) return@runCatching null
        val drawable = icon.loadDrawable(context) ?: return@runCatching null
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap ?: return@runCatching null
            if (bitmap.isRecycled || bitmap.width !in 1..1024 || bitmap.height !in 1..1024) return@runCatching null
            val scale = 256.0 / max(bitmap.width, bitmap.height)
            if (scale >= 1) bitmap else Bitmap.createScaledBitmap(bitmap,
                max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
        } else {
            val width = drawable.intrinsicWidth; val height = drawable.intrinsicHeight
            if (width !in 1..1024 || height !in 1..1024) return@runCatching null
            val scale = minOf(1.0, 256.0 / max(width, height))
            Bitmap.createBitmap(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()), Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, it.width, it.height); drawable.draw(Canvas(it))
            }
        }
    }.getOrNull()
}
