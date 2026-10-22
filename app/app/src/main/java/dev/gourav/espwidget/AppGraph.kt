package dev.gourav.espwidget

import android.app.Application
import android.content.Context
import dev.gourav.espwidget.ble.BleManager
import dev.gourav.espwidget.data.WidgetRepository
import dev.gourav.espwidget.service.SyncEngine
import dev.gourav.espwidget.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppGraph {
    lateinit var repository: WidgetRepository
        private set
    lateinit var ble: BleManager
        private set
    lateinit var engine: SyncEngine
        private set
    lateinit var scope: CoroutineScope
        private set

    @Volatile
    var initialized = false
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            repository = WidgetRepository(app, scope)
            ble = BleManager(app, scope)
            engine = SyncEngine(app, repository, ble, scope)
            initialized = true
            engine.start()
        }
    }
}

class EspWidgetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        if (AppGraph.ble.hasPermissions()) SyncService.start(this)
        AppGraph.scope.launch {
            if (BuildConfig.DEBUG) {
                WidgetUpdater.refresh(this@EspWidgetApplication)
            }
        }
    }
}
