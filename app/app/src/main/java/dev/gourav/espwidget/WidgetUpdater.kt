package dev.gourav.espwidget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dev.gourav.espwidget.widget.EspWidgetGlance
import dev.gourav.espwidget.widget.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object WidgetUpdater {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    fun refresh(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (!started) {
                started = true
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                scope.launch {
                    for (ignored in requests) {
                        runCatching { WidgetKind.entries.forEach { EspWidgetGlance(it).updateAll(app) } }
                        delay(8_000)
                    }
                }
            }
        }
        requests.trySend(Unit)
    }
}
