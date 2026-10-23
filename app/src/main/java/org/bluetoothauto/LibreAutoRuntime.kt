// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.bluetoothauto.integration.CarDisplayController
import org.openandroidauto.integration.ProjectionIntegration

/** Shutdown waits exist only for an explicit transition; Bluetooth mode has no projection polling. */
object LibreAutoRuntime : CarDisplayController.Runtime {
    private lateinit var app: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    fun initialize(context: Context) { app = context.applicationContext }
    override fun stopProjection(onStopped: (Boolean) -> Unit) {
        ProjectionIntegration.requestStop(app)
        waitFor({ !ProjectionIntegration.isSessionActive() }, onStopped)
    }
    override fun stopBluetoothPlayback(onStopped: (Boolean) -> Unit) {
        app.stopService(Intent(app, BluetoothAutoService::class.java))
        waitFor({ !AppState.running }, onStopped)
    }
    private fun waitFor(done: () -> Boolean, callback: (Boolean) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        val check = object : Runnable {
            override fun run() {
                if (done()) callback(true)
                else if (SystemClock.elapsedRealtime() >= deadline) callback(false)
                else main.postDelayed(this, 40)
            }
        }
        main.post(check)
    }
}
