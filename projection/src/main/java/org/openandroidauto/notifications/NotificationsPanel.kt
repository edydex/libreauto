package org.openandroidauto.notifications

import android.content.Context
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.openandroidauto.ui.CarUi
import org.openandroidauto.sms.SmsPanel
import org.openandroidauto.ServiceState
import java.io.Closeable

/** Native, event-driven inbox. Drafts live only in this visible projection session. */
class NotificationsPanel(context: Context, private val listener: Listener) : LinearLayout(context), Closeable {
    interface Listener {
        fun onStatus(message: String)
        fun onReadAloud(text: String)
        fun onVoiceReplyRequested(token: Long): Boolean
        fun onVoiceReplyCancelled(token: Long)
        fun onComposerOpened() {}
        fun onFlowChanged() {}
    }
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroller = ScrollView(context).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false
        // The last card can scroll completely above the dashboard's floating microphone.
        setPadding(0, 0, 0, dp(76))
    }
    private val heading = label("Messages", 24f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val count = label("", 13f).apply { setTextColor(CarUi.TEXT_MUTED); gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val composer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val recipient = label("", 20f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val replySource = label("", 13f).apply {
        setTextColor(CarUi.ACCENT); maxLines = 1; maxWidth = dp(130); ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), dp(4), dp(8), dp(4)); background = CarUi.rounded(context, CarUi.SURFACE_RAISED, 8)
    }
    private val replyPreview = label("", 15f).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(CarUi.TEXT_MUTED)
    }
    private val draft = EditText(context).apply {
        hint = "Choose a reply below or tap Dictate"
        setTextColor(CarUi.TEXT); setHintTextColor(CarUi.TEXT_MUTED)
        textSize = 20f; maxLines = 3; minLines = 2; gravity = Gravity.TOP or Gravity.START
        filters = arrayOf(InputFilter.LengthFilter(2000))
        background = CarUi.rounded(context, CarUi.SURFACE_RAISED, 16)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    private var observer: Closeable? = null
    private var target: MessageCard? = null
    private var replyStatus: String? = null
    private var generation = 0L
    private val replyTurns = ReplyTurnGate()
    private var closed = false
    private var projection = false
    private var smsVisible = false
    private var commandQuery: String? = null
    private var commandReply = false
    private var cards: List<MessageCard> = emptyList()
    private val sms = SmsPanel(context, object : SmsPanel.Listener {
        override fun onStatus(text: String) { listener.onStatus(text) }
        override fun onDictate(token: Long) = listener.onVoiceReplyRequested(token)
        override fun onCancelDictation(token: Long) { listener.onVoiceReplyCancelled(token) }
        override fun onClose() { showInbox() }
        override fun onComposerOpened() { listener.onComposerOpened() }
        override fun onFlowChanged() { listener.onFlowChanged() }
    }).apply { visibility = GONE }

    init {
        orientation = VERTICAL
        setBackgroundColor(CarUi.BACKGROUND)
        setPadding(dp(16), dp(12), dp(16), dp(10))
        header.addView(heading, LayoutParams(0, dp(48), 1f))
        header.addView(count, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        header.addView(button("New SMS") { showSms("") }, LayoutParams(dp(104), dp(48)).apply { marginStart = dp(8) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })
        scroller.addView(list)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val recipientCard = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = CarUi.rounded(context, CarUi.SURFACE, 16)
        }
        val identity = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        identity.addView(recipient, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        identity.addView(replySource, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        recipientCard.addView(identity)
        recipientCard.addView(replyPreview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        composer.addView(recipientCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        composer.addView(draft, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        draft.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(text: Editable?) { cancelVoiceReply() }
        })
        val quick = LinearLayout(context)
        for ((index, text) in listOf("On my way", "I'll reply later", "OK").withIndex()) {
            quick.addView(button(text) { draft.setText(text); cancelVoiceReply() }, LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(6) })
        }
        composer.addView(quick)
        val actions = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            // Only this bottom action row shares the floating microphone's height.
            setPadding(dp(68), 0, 0, 0)
        }
        actions.addView(button("Dictate") {
            val token = prepareVoiceReply()
            if (token != null && !listener.onVoiceReplyRequested(token)) {
                cancelVoiceReply(token)
                listener.onStatus("Install and set up the optional offline dictation add-on on your phone.")
            }
        }, LayoutParams(0, dp(52), 1f))
        actions.addView(button("Cancel") {
            clearComposer(); listener.onStatus("Reply cancelled; nothing sent.")
        }, LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        actions.addView(button("Send", primary = true) {
            val card = target ?: return@button
            val text = draft.text.toString().trim()
            if (text.isBlank()) { listener.onStatus("Choose or dictate a reply first."); return@button }
            val result = MessagingNotifications.send(context, card, text)
            if (result.startsWith("Reply handed to ")) clearComposer()
            listener.onStatus(result)
        }, LayoutParams(0, dp(52), 1f))
        composer.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        addView(composer, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(sms, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        composer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val compact = bottom - top < dp(300)
            replyPreview.visibility = if (compact && replyStatus == null) GONE else VISIBLE
            recipient.maxLines = if (compact) 1 else 2
        }
        showCards(emptyList())
    }

    fun start(activeProjection: Boolean = false) {
        projection = activeProjection
        if (closed || observer != null) return
        observer = MessagingNotifications.observe { cards ->
            this.cards = cards
            val selected = target
            if (selected != null && cards.none { it.key == selected.key && it.revision == selected.revision }) {
                clearComposer()
                listener.onStatus("Message changed. Select Reply again.")
            }
            showCards(cards)
        }
    }

    fun stop() {
        observer?.close(); observer = null
        clearComposer()
        sms.stop(); smsVisible = false; sms.visibility = GONE
        commandQuery = null; commandReply = false; cards = emptyList()
        showCards(emptyList())
    }

    override fun close() { if (!closed) { stop(); closed = true; sms.close() } }
    fun hasReplyTarget(): Boolean = if (smsVisible) sms.hasReplyTarget() else !closed && observer != null && target?.let(MessagingNotifications::isCurrent) == true
    fun hasPendingFlow(): Boolean = !closed && observer != null && (if (smsVisible) sms.hasPendingFlow() else target != null || commandQuery != null)
    fun cancelPendingFlow() { if (smsVisible) sms.cancelPending() else { clearComposer(); commandQuery = null; commandReply = false; showCards(cards) }; listener.onFlowChanged() }
    fun handleBackNavigation(): Boolean {
        if (closed || observer == null) return false
        if (smsVisible) { if (!sms.handleBackNavigation()) showInbox(); return true }
        if (hasPendingFlow()) { cancelPendingFlow(); return true }
        return false
    }

    /** Names only filter current app-owned notification choices. A choice still binds a fresh reply. */
    fun beginCommandMessage(name: String, replyOnly: Boolean): Boolean {
        if (closed || observer == null || name.isBlank() || name.length > 120) return false
        showInbox(); commandQuery = name; commandReply = true
        heading.text = if (replyOnly) "Reply to…" else "Message…"
        showCards(cards); listener.onFlowChanged()
        return true
    }

    private fun showSms(query: String) {
        if (closed || observer == null) return
        clearComposer(); commandQuery = null; commandReply = false; smsVisible = true
        header.visibility = GONE; scroller.visibility = GONE; sms.visibility = VISIBLE
        listener.onComposerOpened(); sms.start(projection && ServiceState.projectionActive, query)
        listener.onFlowChanged()
    }
    private fun showInbox() {
        sms.stop(); smsVisible = false; sms.visibility = GONE
        clearComposer(); commandQuery = null; commandReply = false; showCards(cards)
        listener.onFlowChanged()
    }

    /** Keep voice/error feedback below the reviewed recipient, never floating over its identity. */
    fun showReplyStatus(message: String): Boolean {
        if (smsVisible) return sms.showReplyStatus(message)
        if (closed || target == null || composer.visibility != VISIBLE) return false
        replyStatus = message; replyPreview.text = message
        replyPreview.contentDescription = message; replyPreview.visibility = VISIBLE
        return true
    }

    /** Both the car's voice button and Dictate bind one turn to the current reviewed recipient. */
    fun prepareVoiceReply(): Long? {
        if (smsVisible) return sms.prepareVoiceReply()
        if (!hasReplyTarget()) {
            listener.onStatus("Open Messages and choose Reply on a message first.")
            return null
        }
        return replyTurns.prepare(generation)
    }

    fun cancelVoiceReply(token: Long? = null) {
        sms.cancelVoiceReply(token)
        val owned = replyTurns.current(generation)
        replyTurns.cancel(token)
        if (owned != null && (token == null || token == owned)) listener.onVoiceReplyCancelled(owned)
    }

    /** A transcript is a draft; selecting a different recipient invalidates an in-flight turn. */
    fun acceptTranscript(token: Long, text: String): Boolean {
        if (smsVisible) return sms.acceptTranscript(token, text)
        val valid = replyTurns.consume(token, generation) && hasReplyTarget()
        if (!valid || text.isBlank() || text.length > 2000) {
            listener.onStatus("Dictation target changed or no text was heard. Choose Reply and try again.")
            return false
        }
        draft.setText(text)
        listener.onStatus("Review your reply, then press Send.")
        return true
    }

    private fun clearComposer() {
        cancelVoiceReply(); generation++; target = null; replyStatus = null
        draft.setText(""); recipient.text = ""; replySource.text = ""; replyPreview.text = ""
        composer.visibility = GONE; scroller.visibility = VISIBLE; header.visibility = VISIBLE; heading.text = "Messages"
        listener.onFlowChanged()
    }

    private fun showCards(cards: List<MessageCard>) {
        list.removeAllViews()
        val search = commandQuery
        val visibleCards = if (search == null) cards else cards.filter { it.canReply && it.title.contains(search, ignoreCase = true) }
        if (search != null) {
            list.addView(button("New carrier SMS to contact…") { showSms(search) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })
            list.addView(button("All notifications") { commandQuery = null; commandReply = false; heading.text = "Messages"; showCards(cards); listener.onFlowChanged() })
        }
        count.text = if (cards.isEmpty()) "" else "${cards.size} notification${if (cards.size == 1) "" else "s"}"
        if (visibleCards.isEmpty()) {
            val empty = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), dp(24), dp(22), dp(24)); background = CarUi.rounded(context, CarUi.SURFACE, 20)
            }
            empty.addView(label(if (search == null) "No messages here yet" else "No matching reply notification", 23f))
            empty.addView(label(if (search == null) "Notifications from your selected apps appear here while connected. Choose messaging apps in Lite's phone setup." else "Choose New carrier SMS to search your contacts, or return to all notifications.", 16f).apply {
                setTextColor(CarUi.TEXT_MUTED); maxLines = 4
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            list.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }
        for (card in visibleCards) {
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = CarUi.rounded(context, CarUi.SURFACE, 18)
            }
            row.addView(label(card.appName, 12f).apply { setTextColor(CarUi.ACCENT); letterSpacing = .08f; maxLines = 1 })
            row.addView(label(card.title, 21f).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
            row.addView(label(card.body, 17f).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setTextColor(CarUi.TEXT_MUTED) },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7); bottomMargin = dp(12) })
            val controls = LinearLayout(context)
            controls.addView(button("Read") {
                if (MessagingNotifications.isCurrent(card)) listener.onReadAloud("${card.title}. ${card.body}")
                else listener.onStatus("Message is no longer available.")
            }, LayoutParams(0, dp(48), 1f))
            controls.addView(button("Reply", primary = true) {
                if (!MessagingNotifications.isCurrent(card)) { listener.onStatus("Message changed. Choose it again."); return@button }
                clearComposer(); target = card
                recipient.text = "To ${card.title}"
                replySource.text = card.appName; replyPreview.text = card.body
                heading.text = "Reply"; header.visibility = GONE; scroller.visibility = GONE; composer.visibility = VISIBLE
                draft.clearFocus()
                listener.onComposerOpened(); listener.onFlowChanged()
                if (commandReply) {
                    val token = prepareVoiceReply()
                    if (token != null && !listener.onVoiceReplyRequested(token)) cancelVoiceReply(token)
                }
            }.apply { isEnabled = card.canReply }, LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            row.addView(controls)
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun label(value: String, size: Float) = CarUi.text(context, value, size, CarUi.TEXT)
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = CarUi.button(context, value, primary).apply {
        setOnClickListener { action() }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
