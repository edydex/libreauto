// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.bluetoothauto.navigation.GoogleMapsNotificationParser
import org.bluetoothauto.messaging.MessageNotificationParser

/** Maps guidance plus opt-in messages; notification history is never stored or uploaded. */
class MapsNotificationListener : NotificationListenerService() {
    private val parser by lazy { GoogleMapsNotificationParser(this) }
    private val messages = MessageNotificationParser()
    override fun onListenerConnected() {
        instance=this
        AppState.listenerConnected = true
        refreshActive()
        AppState.changed()
    }
    private fun refreshActive() {
        if(Library.navigator(this)!="GOOGLE")return
        try { activeNotifications?.sortedBy { it.postTime }?.forEach { handle(it,false) } }
        catch (_: SecurityException) { GuidanceHub.clear() }
    }
    override fun onListenerDisconnected() {
        AppState.listenerConnected = false
        if(instance===this)instance=null
        if(Library.navigator(this)=="GOOGLE")GuidanceHub.clear()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn,true)
    }
    private fun handle(sbn:StatusBarNotification, fresh:Boolean) {
        if(fresh && AppState.running && Library.speakMessages(this)) {
            messages.parse(sbn)?.let{MessageSpeechHub.publish(it.spokenText)}
        }
        if (sbn.packageName != MAPS_PACKAGE || Library.navigator(this)!="GOOGLE") return
        val result = try { parser.parse(sbn.notification) } catch (_: RuntimeException) { null }
        if (result != null) GuidanceHub.publish(sbn.key, result) else GuidanceHub.remove(sbn.key)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS_PACKAGE && Library.navigator(this)=="GOOGLE") GuidanceHub.remove(sbn.key)
    }
    override fun onDestroy(){if(instance===this)instance=null;super.onDestroy()}
    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        private var instance:MapsNotificationListener?=null
        fun refresh(context:Context){
            val live=instance
            if(live!=null)live.refreshActive()
            else requestRebind(ComponentName(context,MapsNotificationListener::class.java))
        }
    }
}
