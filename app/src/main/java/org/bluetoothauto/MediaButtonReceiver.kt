// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

class MediaButtonReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_MEDIA_BUTTON)return
        @Suppress("DEPRECATION") val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if(key.action!=KeyEvent.ACTION_DOWN || key.repeatCount!=0)return
        val action = when(key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> BluetoothAutoService.PLAY
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_HEADSETHOOK -> BluetoothAutoService.TOGGLE
            KeyEvent.KEYCODE_MEDIA_PAUSE -> BluetoothAutoService.PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT -> BluetoothAutoService.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> BluetoothAutoService.PREVIOUS
            KeyEvent.KEYCODE_MEDIA_STOP -> BluetoothAutoService.STOP
            else -> return
        }
        BluetoothAutoService.command(context,action)
    }
}
