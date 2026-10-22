package dev.gourav.espwidget.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.gourav.espwidget.AppGraph

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppGraph.init(context)
        SyncService.start(context)
    }
}
