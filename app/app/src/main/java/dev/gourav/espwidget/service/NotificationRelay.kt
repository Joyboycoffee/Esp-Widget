package dev.gourav.espwidget.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.NotificationModel

class NotificationRelay : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AppGraph.init(this)
        AppGraph.repository.log("Notification access connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        AppGraph.init(this)
        if (sbn.packageName == packageName) return
        if (!AppGraph.repository.persisted.value.sources.notificationsEnabled) return
        val app = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        if (sbn.notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) != 0) return
        val count = runCatching {
            activeNotifications?.count { notification ->
                notification.packageName != packageName &&
                    (notification.notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) == 0)
            }
        }.getOrNull() ?: 1
        AppGraph.repository.setNotification(
            NotificationModel(
                app = app.take(15),
                title = title.take(39),
                body = body.take(55),
                count = count.coerceAtLeast(1),
                updated = System.currentTimeMillis() / 1000,
                valid = true,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val remaining = runCatching {
            activeNotifications?.count { it.packageName != packageName } ?: 0
        }.getOrDefault(0)
        if (remaining == 0) AppGraph.repository.clearNotifications()
    }
}
