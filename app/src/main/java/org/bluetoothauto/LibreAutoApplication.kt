// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Application
import android.content.Context
import org.bluetoothauto.integration.CarDisplayController
import org.bluetoothauto.integration.CarProfiles
import org.bluetoothauto.integration.OfficialProjectionMonitor
import org.bluetoothauto.integration.SharedLibraryBridge
import org.openandroidauto.integration.ProjectionIntegration

/** Registers lazy adapters only. No player, map, transport, codec or location subscription starts here. */
class LibreAutoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LibreAutoRuntime.initialize(this)
        SharedLibraryBridge.install(this)
        ProjectionIntegration.delegate = object : ProjectionIntegration.Delegate {
            override fun isProjectionSelected() = CarDisplayController.isProjectionAllowed(this@LibreAutoApplication)
            override fun isProjectionAllowed() = isProjectionSelected() && !AppState.running
            override fun prepareToStart(context: Context, onReady: (Boolean) -> Unit) {
                if (!isProjectionSelected()) { onReady(false); return }
                LibreAutoRuntime.stopBluetoothPlayback { stopped ->
                    onReady(stopped && isProjectionAllowed())
                }
            }
            override fun onProjectionStarting() { AppState.changed() }
            override fun onProjectionCarSelected(address: String?) { CarProfiles.onProjectionCarSelected(address) }
            override fun onProjectionReady() { CarProfiles.onProjectionReady() }
            override fun onProjectionStopped() { CarProfiles.onProjectionStopped(); AppState.changed() }
        }
        // This only updates our disabled-by-default USB entry. The saved choice starts no service.
        ProjectionIntegration.syncUsbEntry(this)
        CarDisplayController.addListener { ProjectionIntegration.syncUsbEntry(this) }
        CarProfiles.officialProjectionActive = { OfficialProjectionMonitor.cacheActive() }
        CarProfiles.initialize(this)
    }
}
