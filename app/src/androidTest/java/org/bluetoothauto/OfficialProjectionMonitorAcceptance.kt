// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import org.bluetoothauto.integration.OfficialProjectionMonitor
import org.bluetoothauto.integration.OfficialProjectionProtocol
import java.io.Closeable

/** Read-only provider check. Run on a parked test phone without official AA projection. */
object OfficialProjectionMonitorAcceptance {
    fun run(test: Instrumentation): Bundle {
        var lease: Closeable? = null
        val initiallyObserved = OfficialProjectionMonitor.status() != OfficialProjectionMonitor.Status.UNOBSERVED
        fun awaitQuery() {
            val until = SystemClock.elapsedRealtime() + 4_000
            while (SystemClock.elapsedRealtime() < until) {
                if (OfficialProjectionMonitor.status() !in setOf(OfficialProjectionMonitor.Status.UNOBSERVED, OfficialProjectionMonitor.Status.QUERYING)) return
                SystemClock.sleep(25)
            }
            error("Official projection query did not settle")
        }
        var initialStatus = ""
        var known = false
        try {
            test.runOnMainSync { lease = OfficialProjectionMonitor.start(test.targetContext) {} }
            awaitQuery()
            initialStatus = OfficialProjectionMonitor.status().name
            known = OfficialProjectionMonitor.cacheKnown()
            check(!OfficialProjectionMonitor.cacheActive()) { "An actual official Android Auto projection is active; use an idle test phone" }
            val beforeHint = OfficialProjectionMonitor.queryCount()
            // The hint is app-targeted and unprotected. Fake state extras must never become state.
            test.targetContext.sendBroadcast(Intent(OfficialProjectionProtocol.ACTION_UPDATED)
                .setPackage(test.targetContext.packageName).putExtra(OfficialProjectionProtocol.STATE_COLUMN, 2))
            val until = SystemClock.elapsedRealtime() + 4_000
            while (OfficialProjectionMonitor.queryCount() == beforeHint && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
            check(OfficialProjectionMonitor.queryCount() > beforeHint) { "The update broadcast did not trigger a provider query" }
            awaitQuery()
            check(!OfficialProjectionMonitor.cacheActive()) { "Broadcast extras spoofed official projection" }
        } finally {
            test.runOnMainSync { lease?.close() }
        }
        if (!initiallyObserved) {
            check(OfficialProjectionMonitor.status() == OfficialProjectionMonitor.Status.UNOBSERVED) { "Last observer did not release the monitor" }
            check(!OfficialProjectionMonitor.cacheKnown() && !OfficialProjectionMonitor.cacheActive()) { "Closed monitor retained a live cache" }
        }
        return Bundle().apply {
            putString("official_projection_provider", initialStatus)
            putBoolean("official_projection_known", known)
            putBoolean("official_projection_active", false)
            putBoolean("official_projection_broadcast_extras_ignored", true)
            putBoolean("official_projection_closed_last_owner", !initiallyObserved)
        }
    }
}
