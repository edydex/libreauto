package org.openandroidauto.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import org.openandroidauto.integration.ProjectionIntegration

data class MessageCard internal constructor(
    internal val key: String,
    internal val epoch: Long,
    internal val revision: Long,
    internal val postedAt: Long,
    internal val packageName: String,
    internal val secret: Boolean,
    val appName: String,
    val title: String,
    val body: String,
    val canReply: Boolean
)

/** Projection-scoped memory only. Android owns the connection and wakes us for changes. */
object MessagingNotifications {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var active = false
    private var epoch = 0L
    private var revision = 0L
    private var service: MessagingNotificationListener? = null
    private var selected = emptySet<String>()
    private var screenContext: Context? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                synchronized(lock) { cards.entries.removeAll { it.value.secret } }
                publish()
            } else refreshSources(context)
        }
    }
    private val cards = linkedMapOf<String, MessageCard>()
    private val observers = CopyOnWriteArraySet<(List<MessageCard>) -> Unit>()

    /** Safe while the projection owner's shutdown lock is held: never does I/O or calls back here. */
    fun setProjectionActive(context: Context, active: Boolean) {
        if (active && (!ProjectionIntegration.isSessionActive() || !ProjectionIntegration.isProjectionAllowed())) return
        val token = synchronized(lock) {
            if (this.active == active) return
            this.active = active
            epoch++
            cards.clear()
            epoch
        }
        val app = context.applicationContext
        main.post {
            if (!current(token)) return@post
            if (active) {
                if (screenContext == null) {
                    ContextCompat.registerReceiver(app, screenReceiver, IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
                    }, ContextCompat.RECEIVER_NOT_EXPORTED)
                    screenContext = app
                }
                reload(app, token)
            } else {
                screenContext?.let { runCatching { it.unregisterReceiver(screenReceiver) } }
                screenContext = null
                publish()
            }
        }
    }

    fun observeSummary(context: Context, callback: (Int) -> Unit): Closeable =
        observe { callback(it.size) }

    internal fun observe(callback: (List<MessageCard>) -> Unit): Closeable {
        observers.add(callback)
        main.post { if (callback in observers) callback(snapshot()) }
        return Closeable { observers.remove(callback) }
    }

    internal fun refreshSources(context: Context) {
        val app = context.applicationContext
        main.post {
            val token = synchronized(lock) { if (!active) return@post; epoch }
            reload(app, token)
        }
    }

    internal fun attach(listener: MessagingNotificationListener) {
        main.post {
            synchronized(lock) { service = listener }
            val token = synchronized(lock) { if (!active) return@post; epoch }
            reload(listener, token)
        }
    }

    internal fun detach(listener: MessagingNotificationListener) {
        main.post {
            synchronized(lock) {
                if (service !== listener) return@post
                service = null
                cards.clear()
            }
            publish()
        }
    }

    internal fun posted(listener: MessagingNotificationListener, sbn: StatusBarNotification) {
        // Do not even copy message text unless this active projection opted into its source.
        val token = synchronized(lock) {
            if (!active || service !== listener || sbn.packageName !in selected) return
            epoch
        }
        main.post {
            if (!current(token)) return@post
            update(listener, sbn, token)
            publish()
        }
    }

    internal fun removed(listener: MessagingNotificationListener, sbn: StatusBarNotification) {
        val token = synchronized(lock) {
            if (!active || service !== listener || sbn.packageName !in selected) return
            epoch
        }
        main.post {
            synchronized(lock) { if (service !== listener || epoch != token) return@post; cards.remove(sbn.key) }
            publish()
        }
    }

    private fun current(token: Long): Boolean = synchronized(lock) { token == epoch }
    private fun snapshot(): List<MessageCard> {
        val (items, listener) = synchronized(lock) { (if (active) cards.values.toList() else emptyList()) to service }
        val hidden = listener == null || locked(listener)
        return items.filter { !it.secret || !hidden }.sortedByDescending { it.postedAt }
    }
    private fun publish() {
        val snapshot = snapshot()
        for (observer in observers) runCatching { observer(snapshot) }
    }

    private fun reload(context: Context, token: Long) {
        val allowed = NotificationSources.selected(context)
        val listener = synchronized(lock) {
            if (!active || epoch != token) return
            selected = allowed
            cards.clear()
            service
        }
        val notifications = runCatching { listener?.activeNotifications.orEmpty() }.getOrDefault(emptyArray())
        if (listener != null) {
            notifications.asSequence().filter { it.packageName in allowed }
                .sortedByDescending { it.postTime }.take(20).forEach { update(listener, it, token) }
        }
        publish()
    }

    private fun update(listener: MessagingNotificationListener, sbn: StatusBarNotification, token: Long) {
        if (!synchronized(lock) { active && epoch == token && service === listener }) return
        fun discard() { synchronized(lock) { if (active && epoch == token && service === listener) cards.remove(sbn.key) } }
        val n = sbn.notification
        if (sbn.packageName !in synchronized(lock) { selected }) { discard(); return }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            (n.category != Notification.CATEGORY_MESSAGE && !n.extras.containsKey(Notification.EXTRA_MESSAGES))) { discard(); return }
        if (n.visibility == Notification.VISIBILITY_SECRET && locked(listener)) { discard(); return }
        val title = clean(n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE), 128)
        val messages = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages }.getOrNull()
        val last = messages?.lastOrNull()
        val sender = clean(last?.person?.name, 128)
        val body = clean(last?.text ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT), 1700)
        if (body.isBlank()) { discard(); return }
        val label = runCatching { listener.packageManager.getApplicationLabel(
            listener.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault("Messages")
        val canReply = replyAction(sbn) != null
        synchronized(lock) {
            if (!active || epoch != token || service !== listener || sbn.packageName !in selected) return
            cards[sbn.key] = MessageCard(sbn.key, token, ++revision, sbn.postTime, sbn.packageName,
                n.visibility == Notification.VISIBILITY_SECRET,
                label.take(128), title.ifBlank { sender.ifBlank { label } },
                if (sender.isNotBlank() && sender != title) "$sender: $body" else body,
                canReply)
            while (cards.size > 20) cards.remove(cards.values.minBy { it.postedAt }.key)
        }
    }

    internal fun isCurrent(card: MessageCard): Boolean {
        val listener = synchronized(lock) {
            if (!active || epoch != card.epoch || cards[card.key]?.revision != card.revision) return false
            service
        } ?: return false
        return !card.secret || !locked(listener)
    }

    /** Invoked only by the reviewed Send button. Never retries, launches an app, or sends automatically. */
    internal fun send(context: Context, card: MessageCard, text: String): String {
        if (Build.VERSION.SDK_INT < 32 || !ProjectionIntegration.isProjectionAllowed()) return "Car-screen projection is unavailable."
        if (text.isBlank() || text.length > 2000) return "Enter a reply (up to 2,000 characters)."
        val listener = synchronized(lock) {
            if (!active || epoch != card.epoch || cards[card.key]?.revision != card.revision) return "Message changed. Select Reply again."
            service
        } ?: return "Notification access is disconnected."
        if (card.packageName !in NotificationSources.selected(context)) return "This app is no longer selected."
        val live = runCatching { listener.getActiveNotifications(arrayOf(card.key)).singleOrNull() }.getOrNull()
            ?: return "Message is no longer available."
        if (live.packageName != card.packageName || live.postTime != card.postedAt || !isCurrent(card))
            return "Message changed. Select Reply again."
        if (live.notification.visibility == Notification.VISIBILITY_SECRET && locked(context)) return "Unlock your phone to reply."
        val action = replyAction(live) ?: return "This message has no text reply action."
        if (action.isAuthenticationRequired && locked(context)) return "Unlock your phone to reply."
        val inputs = action.remoteInputs.filter { it.allowFreeFormInput }
        val result = android.os.Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, result)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {
            action.actionIntent.send(context, 0, intent)
            synchronized(lock) { if (cards[card.key]?.revision == card.revision) cards.remove(card.key) }
            publish()
            "Reply handed to ${card.appName}."
        } catch (_: PendingIntent.CanceledException) { "Reply action expired. Select the message again." }
        catch (_: SecurityException) { "The messaging app declined this reply." }
    }

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? {
        if (Build.VERSION.SDK_INT < 32) return null
        val candidates = sbn.notification.actions.orEmpty().filter { action ->
            action.actionIntent?.creatorPackage == sbn.packageName && !action.actionIntent.isImmutable &&
                action.remoteInputs?.count { it.allowFreeFormInput } == 1 &&
                action.semanticAction in setOf(Notification.Action.SEMANTIC_ACTION_REPLY, Notification.Action.SEMANTIC_ACTION_NONE)
        }
        val explicit = candidates.filter { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
        return if (explicit.isNotEmpty()) explicit.singleOrNull() else candidates.singleOrNull()
    }

    private fun locked(context: Context) = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    private fun clean(value: CharSequence?, limit: Int): String = value?.toString()?.take(limit)?.replace('\u0000', ' ')?.trim().orEmpty()
}

class MessagingNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { MessagingNotifications.attach(this) }
    override fun onListenerDisconnected() { MessagingNotifications.detach(this) }
    override fun onDestroy() { MessagingNotifications.detach(this); super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn != null) MessagingNotifications.posted(this, sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn != null) MessagingNotifications.removed(this, sbn) }
}
