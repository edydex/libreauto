// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.messaging

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Only call for fresh onNotificationPosted events, never an active-notification refresh.
 * The owner decides whether a session and the user's message-speech setting are enabled.
 * No notification content, history, attachment or reply action is persisted or opened.
 */
class MessageNotificationParser {
    private val policy = MessageCuePolicy()

    @Suppress("DEPRECATION")
    fun parse(sbn: StatusBarNotification, nowMillis: Long = System.currentTimeMillis()): MessageCue? {
        if (!MessageCuePolicy.supported(sbn.packageName)) return null
        return try {
            val notification = sbn.notification
            if (notification.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_SERVICE,
                    Notification.CATEGORY_PROGRESS, Notification.CATEGORY_STATUS)) return null
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.take(1024)?.toString().orEmpty()
            // The extractor is AndroidX's public API; framework MessagingStyle has no
            // public extraction method in the Android SDK. Compat reads standard extras.
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            val latest = style?.messages?.lastOrNull()
            if (style != null && latest == null) return null
            val sender = if (latest != null) {
                val personName = latest.person?.name
                personName?.take(1024)?.toString() ?: latest.sender?.take(1024)?.toString()
            } else title
            // MessagingStyle defines a null sender as the local user, e.g. an inline reply.
            if (latest != null && sender == null) return null
            val body = if (latest != null) latest.text?.take(1024)?.toString()
                else notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.take(1024)?.toString()
            policy.offer(MessageCandidate(
                packageName = sbn.packageName,
                key = sbn.key,
                title = title,
                sender = sender.orEmpty().ifBlank { title },
                text = body.orEmpty(),
                postTime = sbn.postTime,
                messageTime = latest?.timestamp ?: 0L,
                messagingStyle = style != null,
                categoryMessage = notification.category == Notification.CATEGORY_MESSAGE,
                groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                secret = notification.visibility == Notification.VISIBILITY_SECRET
            ), nowMillis)
        } catch (_: RuntimeException) { null }
    }

    fun clear() { policy.clear() }
}
