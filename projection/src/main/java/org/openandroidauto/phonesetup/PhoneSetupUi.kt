// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.phonesetup

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.openandroidauto.ui.CarUi

/** Native phone setup chrome. No polling, observers, permissions or retained data. */
internal class PhoneSetupUi(private val activity: Activity, onHome: () -> Unit) {
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(28))
    }
    private val heading = label("LibreAuto · Car display", 26f).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val back = CarUi.button(activity, "‹ Home", false).apply {
        setOnClickListener { onHome() }
        contentDescription = "Back to car display settings"
    }
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(CarUi.BACKGROUND)
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(20), dp(8))
            addView(back, LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginEnd=dp(12) })
            addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(header)
        addView(ScrollView(activity).apply {
            isFillViewport = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun begin(screen: PhoneSetupState.Screen) {
        content.removeAllViews()
        heading.text = screen.title
        back.visibility = if (screen == PhoneSetupState.Screen.HOME) View.GONE else View.VISIBLE
        (content.parent as? ScrollView)?.scrollTo(0, 0)
    }
    fun label(text: String, size: Float = 16f, muted: Boolean = false) = CarUi.text(
        activity, text, size, if (muted) CarUi.TEXT_MUTED else CarUi.TEXT).apply {
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun paragraph(text: String) {
        content.addView(label(text, muted=true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(16) })
    }
    fun card(title: String, body: String? = null): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = CarUi.rounded(activity, CarUi.SURFACE, 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        if (title.isNotBlank()) card.addView(label(title, 20f).apply {
            typeface=Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (!body.isNullOrBlank()) card.addView(label(body, muted=true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(8) })
        content.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(12) })
        return card
    }
    fun button(parent: LinearLayout, text: String, primary: Boolean = false, action: () -> Unit): Button {
        val view = CarUi.button(activity, text, primary).apply { setOnClickListener { action() } }
        parent.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(10) })
        return view
    }
    fun menu(title: String, summary: String, action: () -> Unit): TextView {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            background=CarUi.rounded(activity, CarUi.SURFACE, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumHeight=dp(76); isClickable=true; isFocusable=true
            setOnClickListener { action() }
            contentDescription=title
        }
        val copy = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        copy.addView(label(title, 19f))
        val detail = label(summary, 14f, true).apply { setPadding(0, dp(4), 0, 0) }
        copy.addView(detail)
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 28f, true).apply { gravity=Gravity.CENTER }, LinearLayout.LayoutParams(dp(28), dp(48)))
        content.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(10) })
        return detail
    }
    fun attach(parent: LinearLayout, view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        parent.addView(view)
    }
    fun dp(value: Int) = CarUi.dp(activity, value.toFloat())
}
