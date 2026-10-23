package org.openandroidauto.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.os.Build
import org.openandroidauto.ProjectionStartRejection

/** Per-service foreground-start handshake only. It owns no session, worker, wake lock or media. */
class RejectedForegroundStart {
    private val sequence = ProjectionStartRejection()
    val rejected: Boolean get() = sequence.rejected
    fun shouldReject(modeAllowed: Boolean): Boolean = sequence.shouldReject(modeAllowed)

    /** Types must be declared for the calling service. Supply connectedDevice for projection or
     * mediaPlayback for the player; neither requests while-in-use location. The notification is
     * removed immediately, with display deferral requested where available.
     */
    @JvmOverloads
    fun stop(service: Service, notificationId: Int, foregroundType: Int, label: String, smallIcon: Int,
             channelId: String = "libreauto-rejected-start", startId: Int? = null): Boolean =
        sequence.rejectStart(startId, {
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "Car connection", NotificationManager.IMPORTANCE_LOW))
            val builder = Notification.Builder(service, channelId)
                .setSmallIcon(smallIcon).setContentTitle("LibreAuto").setContentText(label)
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
            if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            val notice = builder.build()
            if (Build.VERSION.SDK_INT >= 29) service.startForeground(notificationId, notice, foregroundType)
            else service.startForeground(notificationId, notice)
        }, { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }, {
            if (startId == null) service.stopSelf() else service.stopSelf(startId)
        })
}
