// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.identity

import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.openandroidauto.ServiceState

/** Explicit, default-OFF choice. Checks read installed software only; no permission,
 * network, car connection, identity files or auto-run on construction/resume. */
class InstalledAaIdentitySettings(private val context: Context) : AutoCloseable {
    companion object {
        private const val PREFERENCES = "open_auto_identity"
        private const val ENABLED = "installed_aa_enabled"
        fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
    }
    val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closed = AtomicBoolean()
    private var checkStop: ProjectionStopSignal? = null
    private var checking = false
    private var refreshing = false
    private var lastStatus: IdentityStatus? = null
    private val choice = CheckBox(context).apply {
        text = "Use installed Android Auto compatibility"
        setOnCheckedChangeListener { _, enabled ->
            if (!refreshing) {
                if (ServiceState.projectionActive) {
                    status.text = "Stop the current connection before changing compatibility mode."
                    refresh()
                } else {
                    checkStop?.stop()
                    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
                    lastStatus = null
                    refresh()
                }
            }
        }
    }
    private val status = TextView(context)
    private val checkButton = Button(context).apply {
        text = "Check installed Android Auto"
        setOnClickListener { runCheck() }
    }
    init {
        view.addView(TextView(context).apply { text = "Car compatibility"; textSize = 20f; setPadding(0, 20, 0, 8) })
        view.addView(TextView(context).apply {
            text = "Requires a supported Android Auto installation. At each connection, LibreAuto checks the installed app and prepares its compatibility identity in memory. It saves no credential files. Car support still needs testing."
        })
        view.addView(choice); view.addView(checkButton); view.addView(status)
        refresh()
    }
    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) return
        val active = ServiceState.projectionActive
        refreshing = true
        choice.isChecked = isEnabled(context)
        choice.isEnabled = !active && !checking
        refreshing = false
        checkButton.isEnabled = !active && !checking
        status.text = when {
            checking -> IdentityStatus.PREPARING.message
            active -> "A connection is active. Stop it before checking or changing compatibility."
            lastStatus != null -> lastStatus!!.message
            isEnabled(context) -> "Enabled. Compatibility is verified again at each connection."
            else -> "Off. Enable this option to use installed Android Auto compatibility."
        }
    }
    private fun runCheck() {
        if (closed.get() || checking || ServiceState.projectionActive) return
        val stop = ProjectionStopSignal()
        checkStop = stop; checking = true; refresh()
        scope.launch {
            // Cancellation never drops a prepared material result during a dispatcher
            // handoff: check() closes it entirely on the worker before returning status.
            val result = withContext(NonCancellable + Dispatchers.IO) {
                InstalledAaIdentityReader.check(context.applicationContext, stop)
            }
            if (!closed.get() && checkStop === stop) {
                checking = false; checkStop = null; lastStatus = result; refresh()
            }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        checkStop?.stop(); checkStop = null
        scope.cancel()
    }
}
