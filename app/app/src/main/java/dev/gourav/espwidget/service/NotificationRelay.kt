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
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        AppGraph.init(this)
        refresh(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        AppGraph.init(this)
        refresh()
    }

    private fun usable(n: StatusBarNotification): Boolean {
        if (n.packageName == packageName) return false
        val notification = n.notification ?: return false
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = notification.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        return title.isNotBlank() || body.isNotBlank()
    }

    /**
     * Recomputes what the widget should show from the live notification shade:
     * the newest remaining notification plus the real count. Called on every
     * post and removal so the widget never keeps showing a dismissed one.
     */
    private fun refresh(posted: StatusBarNotification? = null) {
        val repo = AppGraph.repository
        if (!repo.persisted.value.sources.notificationsEnabled) return
        val active = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty().filter(::usable)
        val newest = (active + listOfNotNull(posted?.takeIf(::usable)))
            .maxByOrNull { it.postTime }
        if (newest == null) {
            repo.clearNotifications()
            return
        }
        val extras = newest.notification.extras
        val app = runCatching {
            val info = packageManager.getApplicationInfo(newest.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(newest.packageName.substringAfterLast('.'))
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        val count = maxOf(active.size, 1)
        repo.setNotification(
            NotificationModel(
                app = app.take(15),
                title = title.take(39),
                body = body.take(55),
                count = count,
                updated = newest.postTime / 1000,
                valid = true,
            ),
        )
    }
}
