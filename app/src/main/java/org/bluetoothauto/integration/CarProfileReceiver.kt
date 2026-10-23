// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Android's protected ACL broadcasts; enabled only after the user opts in and grants access. */
class CarProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        if (!CarProfiles.enabled(context) || runCatching { CarProfiles.missingPermissions(context).isNotEmpty() }.getOrDefault(true)) {
            CarProfiles.syncReceiver(context); return
        }
        val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
        val address = try { device?.address } catch (_: SecurityException) { null } ?: return
        val pending = goAsync()
        val finished = AtomicBoolean()
        val event = CarProfileEvent()
        val main = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                try { CarProfiles.cancelEvent(context, event) } finally { pending.finish() }
            }
        }
        main.postDelayed(timeout, 8_000)
        try {
            CarProfiles.handleConnection(context, address, connected, event) {
                if (finished.compareAndSet(false, true)) { main.removeCallbacks(timeout); pending.finish() }
            }
        } catch (_: RuntimeException) {
            if (finished.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                try { CarProfiles.cancelEvent(context, event) } finally { pending.finish() }
            }
        }
    }
}
