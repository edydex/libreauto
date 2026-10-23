// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.TextUtils
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.openandroidauto.ServiceState
import org.openandroidauto.ui.CarTextKeyboard
import org.openandroidauto.ui.PhoneActivityLauncher
import org.openandroidauto.ui.CarUi
import java.io.Closeable

/** Contact search and an explicit one-number confirmation. No dial action exists in the dictation path. */
class CallsPanel(context: Context, private val listener: Listener) : LinearLayout(context), Closeable {
    interface Listener {
        fun onStatus(message: String)
        fun onDictateSearchRequested(token: Long): Boolean
        fun onDictationCancelled(token: Long)
        fun onCallStarting()
        /** Main-thread callback only; default refuses without a live receiver binding owner. */
        fun onCallHandoffReady(ready: (Boolean) -> Unit) { ready(false) }
        fun isCallHandoffCurrent(): Boolean = false
        fun onFlowChanged() {}
    }
    private val repository = ContactsRepository(context)
    private val searches = CallSearchState()
    private val confirmations = CallConfirmationGate()
    private val handoffs = CallConfirmationGate()
    private val query = EditText(context)
    private val status = TextView(context)
    private val content = LinearLayout(context)
    private val scroller = ScrollView(context).apply {
        clipToPadding = false
        // Scroll the final contact/confirmation fully clear of the floating microphone.
        setPadding(0, 0, 0, dp(76))
    }
    private var keyboard: CarTextKeyboard? = null
    private var active = false
    private var projection = false
    private var closed = false
    private var confirmation: CallConfirmationGate.Ticket? = null
    private var commandFlow = false
    private var phoneCallActive = false
    private var accessObserver: Closeable? = null

    init {
        orientation = VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)); setBackgroundColor(CarUi.BACKGROUND)
        val controls = LinearLayout(context)
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.background = CarUi.rounded(context, CarUi.SURFACE, 18)
        controls.setPadding(dp(6), 0, dp(2), 0)
        query.apply {
            setSingleLine(true); setShowSoftInputOnFocus(false); setTextColor(CarUi.TEXT); setHintTextColor(CarUi.TEXT_MUTED)
            hint = "Search contacts"; textSize = 18f; filters = arrayOf(InputFilter.LengthFilter(CallPolicy.MAX_QUERY))
            setPadding(dp(10), 0, dp(4), 0); setBackgroundColor(Color.TRANSPARENT); minWidth = 0; minimumWidth = 0
            setOnClickListener { showKeyboard() }
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP && event.x >= 0 && event.x < view.width && event.y >= 0 && event.y < view.height) view.performClick()
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    invalidateTurn(); showInfo("Search contacts", "Enter a name, then choose Find."); message("Press Find to search this name")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        controls.addView(query, LayoutParams(0, dp(48), 1f))
        controls.addView(button("Find", true) { load(false) }, LayoutParams(dp(56), dp(48)))
        controls.addView(button("Favorites") { load(true) }, LayoutParams(dp(90), dp(48)))
        controls.addView(button("Dictate") {
            prepareDictationSearch()?.let { token ->
                if (!listener.onDictateSearchRequested(token)) { cancelDictationSearch(token); message("Optional voice input is not ready") }
            }
        }, LayoutParams(dp(80), dp(48)))
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        status.apply { setTextColor(CarUi.TEXT_MUTED); textSize = 14f; setPadding(dp(6), dp(5), dp(6), dp(3)); visibility = GONE }; addView(status)
        content.orientation = VERTICAL; content.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        content.setPadding(0, dp(8), 0, 0)
        scroller.addView(content); addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun start(activeProjection: Boolean) {
        if (closed) return
        projection = activeProjection
        if (active) return
        active = true
        accessObserver = CallsAccess.observe(context) { if (active && !closed) load(true) }
        load(true)
    }

    fun stop() {
        active = false; projection = false; accessObserver?.close(); accessObserver = null
        invalidateTurn(); repository.cancel(); hideKeyboard(); content.removeAllViews()
    }
    override fun close() { if (closed) return; stop(); closed = true; repository.close() }

    fun prepareDictationSearch(): Long? {
        if (!active || closed || !CallsAccess.canRead(context)) { message("Enable Calls access in phone setup first"); return null }
        cancelDictationSearch(); confirmations.invalidate(); handoffs.invalidate(); confirmation = null; hideKeyboard()
        return searches.prepareDictation()
    }

    fun acceptTranscript(token: Long, text: String): Boolean {
        if (!active || closed || !CallsAccess.canRead(context) || !searches.consumeDictation(token)) return false
        val safe = CallPolicy.searchQuery(text)?.takeIf { it.isNotBlank() } ?: run { message("No contact name was recognized"); return false }
        query.setText(safe); query.setSelection(query.length()); load(false)
        return true
    }

    fun cancelDictationSearch(token: Long? = null) {
        val previous = searches.pendingDictation()
        searches.cancelDictation(token)
        if (previous != 0L && searches.pendingDictation() != previous) listener.onDictationCancelled(previous)
    }

    /** Spoken commands search candidates; they never select or call a number. */
    fun beginCommandSearch(text: String): Boolean {
        if (!active || closed || !CallsAccess.canRead(context)) { message("Enable Calls access on the phone first"); return false }
        val safe = CallPolicy.searchQuery(text)?.takeIf { it.isNotBlank() } ?: return false
        query.setText(safe); query.setSelection(query.length()); load(false)
        commandFlow = true; listener.onFlowChanged()
        return true
    }
    fun hasCancelableFlow(): Boolean = active && !closed && (commandFlow || confirmation?.let(confirmations::isCurrent) == true)
    fun cancelPendingHandoff() { handoffs.invalidate() }
    fun setPhoneCallActive(value: Boolean) {
        phoneCallActive = value
        if (value) { invalidateTurn(); message("Use Android's normal controls for the active phone call") }
    }

    /** CALL confirms only the exact already-visible, still-current one-number ticket. */
    fun confirmPending(): Boolean {
        val ticket = confirmation ?: return false
        if (!active || closed || !confirmations.isCurrent(ticket)) return false
        confirm(ticket)
        return true
    }

    fun cancelCommandFlow() {
        if (closed) return
        invalidateTurn(); hideKeyboard(); content.removeAllViews()
        if (active) { showInfo("Cancelled", "No call was requested."); message("Pending call or contact search cancelled") }
    }
    fun handleBackNavigation(): Boolean {
        if (!active || closed) return false
        if (keyboard?.visibility == VISIBLE) { hideKeyboard(); query.clearFocus(); return true }
        if (hasCancelableFlow()) { cancelCommandFlow(); query.clearFocus(); return true }
        return false
    }

    private fun invalidateTurn() {
        cancelDictationSearch(); searches.changed(); confirmations.invalidate(); confirmation = null
        handoffs.invalidate()
        commandFlow = false; listener.onFlowChanged()
        repository.cancel()
    }

    private fun load(favorites: Boolean) {
        if (!active || closed) return
        if (favorites && query.length() != 0) query.text.clear()
        invalidateTurn(); hideKeyboard(); content.removeAllViews(); scroller.scrollTo(0, 0)
        if (!CallsAccess.canRead(context)) {
            message("Calls are off. Enable contact and calling access on the phone.")
            showInfo("Calls are off", "Enable Contacts and Phone access in Calls setup on your phone.")
            content.addView(button("Calls setup on phone") {
                try { PhoneActivityLauncher.open(context, Intent(context, CallsSettingsActivity::class.java)) }
                catch (_: RuntimeException) { message("Open Calls setup from the phone's main screen") }
            }); return
        }
        val safe = if (favorites) "" else CallPolicy.searchQuery(query.text.toString()) ?: return
        val generation = searches.generation()
        message(if (favorites) "Loading Android starred contacts…" else "Searching contacts…")
        repository.query(safe, favorites) { result ->
            if (!active || closed || !searches.current(generation)) return@query
            content.removeAllViews()
            if (!CallsAccess.canRead(context)) { message("Contact access ended. Review Calls setup on the phone."); showInfo("Contact access ended", "Review Calls setup on your phone."); return@query }
            if (result.failure != null) { message(failureMessage(result.failure)); showInfo("Contacts unavailable", failureMessage(result.failure)); return@query }
            message(when {
                result.rows.isEmpty() && favorites -> "No starred contacts with supported phone numbers. Add favorites in your phone's Contacts app."
                result.rows.isEmpty() -> "No matching contacts"
                result.truncated -> "Showing the first contacts. Type a more specific name."
                !projection -> "Preview · select a number to view it; calling requires an active car connection"
                else -> "Choose a phone number, then confirm Call"
            })
            if (result.rows.isEmpty()) showInfo(if (favorites) "No favorites yet" else "No matching contacts",
                if (favorites) "Star a contact in your phone's Contacts app, or search for a name here." else "Try a more specific name or phone number.")
            result.rows.forEach { row -> content.addView(contactRow(row) { if (searches.current(generation)) select(row) }) }
        }
    }

    private fun select(row: CallContact) {
        if (!active || closed || !CallsAccess.canRead(context)) return
        invalidateTurn(); hideKeyboard()
        val ticket = confirmations.select(row); confirmation = ticket
        listener.onFlowChanged()
        content.removeAllViews(); scroller.scrollTo(0, 0)
        val card = card()
        card.addView(CarUi.text(context, row.name, 25f, CarUi.TEXT).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        card.addView(CarUi.text(context, row.label, 14f, CarUi.TEXT_MUTED).apply { setPadding(0, dp(3), 0, dp(3)) })
        card.addView(CarUi.text(context, row.number, 27f, CarUi.ACCENT).apply { setPadding(0, 0, 0, dp(8)) })
        card.addView(CarUi.text(context, if (projection && ServiceState.projectionActive) "Call this number using your phone?" else "Preview · calling is unavailable here", 15f, CarUi.TEXT_MUTED))
        val actions = LinearLayout(context)
        actions.addView(button("Cancel") { load(false) }, LayoutParams(0, dp(52), 1f))
        actions.addView(button("Call", true) { confirm(ticket) }.apply { isEnabled = projection && ServiceState.projectionActive && CallsAccess.canCall(context) }, LayoutParams(0, dp(52), 1f))
        actions.setPadding(0, dp(8), 0, 0); card.addView(actions)
        content.addView(card, cardParams())
        message(if (!projection || !ServiceState.projectionActive) "Calling is disabled in preview and outside an active car connection" else "Review this name and number. Call uses Android's normal phone service.")
    }

    private fun confirm(ticket: CallConfirmationGate.Ticket) {
        if (!active || closed || !confirmations.isCurrent(ticket)) return
        if (!canPlaceCall()) return
        message("Checking the selected contact…")
        repository.revalidate(ticket.contact) { result ->
            if (!active || closed || !confirmations.isCurrent(ticket)) return@revalidate
            if (result.failure != null) { confirmations.invalidate(); showCallOutcome("Could not verify contact", failureMessage(result.failure)); return@revalidate }
            val current = result.rows.singleOrNull()
            if (!canPlaceCall()) return@revalidate
            if (!confirmations.consume(ticket, current, projection && ServiceState.projectionActive, CallsAccess.enabled(context), CallsAccess.canCall(context))) {
                showCallOutcome("Contact changed", "Find and select the phone number again."); return@revalidate
            }
            confirmation = null; commandFlow = false; listener.onFlowChanged()
            val handoff = handoffs.select(ticket.contact)
            listener.onCallStarting() // Stop PCM and release our END_CALL binding before real call audio.
            message("Returning the call controls to your car…")
            var answered = false
            listener.onCallHandoffReady { ready ->
                if (answered) return@onCallHandoffReady
                answered = true
                if (!active || closed || !handoffs.isCurrent(handoff)) return@onCallHandoffReady
                if (!ready || !canPlaceCall()) {
                    handoffs.invalidate(); showCallOutcome("Call not requested", "Call controls were not ready. Select the number again."); return@onCallHandoffReady
                }
                // ACK may take time: re-read exact contact and all authority after it, not before it only.
                repository.revalidate(handoff.contact) fresh@ { checked ->
                    if (!active || closed || !handoffs.isCurrent(handoff)) return@fresh
                    if (checked.failure != null || !canPlaceCall() || !handoffs.consume(handoff, checked.rows.singleOrNull(),
                            projection && ServiceState.projectionActive, CallsAccess.enabled(context), CallsAccess.canCall(context))) {
                        handoffs.invalidate(); showCallOutcome("Call not requested", "Contact or calling access changed. Select the number again."); return@fresh
                    }
                    try {
                        val telecom = context.getSystemService(TelecomManager::class.java)
                        if (telecom == null) { showCallOutcome("Phone calling unavailable", "Check the phone's normal calling app."); return@fresh }
                        if (!active || closed || phoneCallActive || !projection || !ServiceState.projectionActive || !CallsAccess.canCall(context)) return@fresh
                        if (!listener.isCallHandoffCurrent()) {
                            showCallOutcome("Call not requested", "Call controls changed. Select the number again."); return@fresh
                        }
                        telecom.placeCall(Uri.fromParts("tel", handoff.contact.dialNumber, null), Bundle())
                        showCallOutcome("Call requested", "Check your phone or the car's normal call controls. Android handles the call and SIM selection.")
                    } catch (_: SecurityException) { showCallOutcome("Call not requested", "Calling permission is unavailable. Review Calls setup on the phone.") }
                    catch (_: RuntimeException) { showCallOutcome("Call could not start", "Check your phone's normal calling app and SIM.") }
                }
            }
        }
    }

    private fun canPlaceCall(): Boolean {
        val reason = when {
            phoneCallActive -> "Use Android's normal controls for the active phone call"
            !projection || !ServiceState.projectionActive -> "Calling needs an active car connection"
            !CallsAccess.canCall(context) -> "Enable Contacts and Phone access in Calls setup"
            else -> try {
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                val phone = context.getSystemService(TelephonyManager::class.java)
                when {
                    keyguard == null || keyguard.isDeviceLocked || keyguard.isKeyguardLocked -> "Unlock the phone, then confirm Call again"
                    phone == null || !phone.isVoiceCapable -> "This device does not support phone calls"
                    else -> null
                }
            } catch (_: RuntimeException) { "Android phone calling is unavailable" }
        }
        if (reason != null) { message(reason); return false }
        return true
    }

    private fun showKeyboard() {
        if (!active || closed) return
        invalidateTurn(); showInfo("Search contacts", "Enter a name, then choose Find."); scroller.visibility = GONE; status.visibility = GONE
        if (keyboard == null) keyboard = CarTextKeyboard(context, query, object : CarTextKeyboard.Listener {
            override fun onFind() { load(false) }
            override fun onClose() { hideKeyboard() }
        }).also {
            it.setFloatingControlClearance(76, 80)
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        keyboard?.visibility = VISIBLE; query.requestFocus()
    }
    private fun hideKeyboard() { keyboard?.visibility = GONE; scroller.visibility = VISIBLE }
    private fun failureMessage(failure: ContactsRepository.Failure) = when (failure) {
        ContactsRepository.Failure.ACCESS_REQUIRED -> "Contact access ended. Review Calls setup on the phone."
        ContactsRepository.Failure.TIMED_OUT -> "Contacts did not respond in time. Try again."
        ContactsRepository.Failure.BUSY -> "Contacts are still finishing the previous request. Try again."
        ContactsRepository.Failure.UNAVAILABLE -> "Contacts are unavailable. Check the phone and try again."
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val available = minOf((width - paddingLeft - paddingRight).coerceAtLeast(0), dp(720))
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child.tag == "calls-card") child.layoutParams = child.layoutParams.apply { this.width = available }
        }
    }
    private fun card() = LinearLayout(context).apply {
        tag = "calls-card"; orientation = VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
        background = CarUi.rounded(context, CarUi.SURFACE, 18)
    }
    private fun cardParams(height: Int = LayoutParams.WRAP_CONTENT) = LayoutParams(
        if (width > paddingLeft + paddingRight) minOf(width - paddingLeft - paddingRight, dp(720)) else LayoutParams.MATCH_PARENT,
        height).apply { bottomMargin = dp(6) }
    private fun showInfo(title: String, body: String) {
        content.removeAllViews(); scroller.scrollTo(0, 0)
        val card = card()
        card.addView(CarUi.text(context, title, 21f, CarUi.TEXT).apply { setTypeface(typeface, Typeface.BOLD) })
        card.addView(CarUi.text(context, body, 16f, CarUi.TEXT_MUTED).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(card, cardParams()); status.visibility = GONE
    }
    private fun showCallOutcome(title: String, body: String) {
        showInfo(title, body)
        val actions = LinearLayout(context).apply { setPadding(0, dp(12), 0, 0) }
        actions.addView(button("Back to search") { load(false) }, LayoutParams(0, dp(48), 1f))
        actions.addView(button("Favorites", true) { load(true) }, LayoutParams(0, dp(48), 1f))
        (content.getChildAt(0) as LinearLayout).addView(actions)
        listener.onStatus("$title. $body")
    }
    private fun contactRow(row: CallContact, action: () -> Unit) = LinearLayout(context).apply {
        tag = "calls-card"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(12), dp(6))
        background = CarUi.rounded(context, CarUi.SURFACE, 14)
        foreground = RippleDrawable(ColorStateList.valueOf(0x226be1c0), null, CarUi.rounded(context, Color.WHITE, 14))
        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        labels.addView(CarUi.text(context, row.name, 20f, CarUi.TEXT).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        labels.addView(CarUi.text(context, "${row.label} · ${row.number}", 15f, CarUi.TEXT_MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(CarUi.text(context, "›", 27f, CarUi.ACCENT).apply { gravity = Gravity.CENTER }, LayoutParams(dp(32), dp(48)))
        minimumHeight = dp(64); isClickable = true; isFocusable = true; contentDescription = "${row.name}, ${row.label}, ${row.number}"
        setOnClickListener { action() }; layoutParams = cardParams(dp(64))
    }
    private fun message(value: String) {
        status.text = value
        val quiet = value.startsWith("Choose a phone") || value.startsWith("Preview ·") || value.startsWith("Review this")
            || value.startsWith("Press Find") || value.startsWith("Calling is disabled")
        status.visibility = if (quiet || keyboard?.visibility == VISIBLE) GONE else VISIBLE
        listener.onStatus(value)
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = CarUi.button(context, value, primary).apply {
        textSize = 15f; setPadding(dp(6), 0, dp(6), 0); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
