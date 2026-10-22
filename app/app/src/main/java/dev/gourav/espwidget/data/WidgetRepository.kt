package dev.gourav.espwidget.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.gourav.espwidget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val Context.widgetStore by preferencesDataStore(name = "esp_widget")
private val STATE_KEY = stringPreferencesKey("state_v1")

class WidgetRepository(private val context: Context, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _persisted = MutableStateFlow(PersistedState())
    private val _link = MutableStateFlow(LinkState())

    val persisted: StateFlow<PersistedState> = _persisted
    val link: StateFlow<LinkState> = _link

    val state: StateFlow<AppState> = combine(_persisted, _link) { persisted, link ->
        AppState(persisted, link)
    }.stateIn(scope, SharingStarted.Eagerly, AppState())

    init {
        scope.launch {
            val stored = context.widgetStore.data.first()[STATE_KEY]
            if (stored != null) {
                runCatching { json.decodeFromString<PersistedState>(stored) }
                    .onSuccess { _persisted.value = it }
            }
        }
    }

    fun update(transform: (PersistedState) -> PersistedState) {
        val next = transform(_persisted.value)
        _persisted.value = next
        scope.launch {
            context.widgetStore.edit { it[STATE_KEY] = json.encodeToString(PersistedState.serializer(), next) }
            WidgetUpdater.refresh(context)
        }
    }

    fun updateLink(transform: (LinkState) -> LinkState) {
        val before = _link.value
        val after = transform(before)
        _link.value = after
        // The home-screen widget shows link + ESP battery; refresh it when those change.
        if (before.connected != after.connected || before.status?.battery != after.status?.battery) {
            WidgetUpdater.refresh(context)
        }
    }

    fun log(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _link.value = _link.value.copy(log = (listOf("$stamp  $message") + _link.value.log).take(60))
    }

    fun setLayout(layout: LayoutModel) = update {
        it.copy(layout = layout.copy(version = it.layout.version + 1))
    }

    fun setSlot(pageIndex: Int, slotIndex: Int, slot: SlotModel) = update { current ->
        val pages = current.layout.pages.toMutableList()
        if (pageIndex !in pages.indices) return@update current
        val page = pages[pageIndex]
        val slots = page.slots.toMutableList()
        if (slotIndex !in slots.indices) return@update current
        slots[slotIndex] = slot
        pages[pageIndex] = page.copy(slots = slots)
        current.copy(layout = current.layout.copy(pages = pages, version = current.layout.version + 1))
    }

    fun setTemplate(pageIndex: Int, template: Int) = update { current ->
        val pages = current.layout.pages.toMutableList()
        if (pageIndex !in pages.indices) return@update current
        pages[pageIndex] = pages[pageIndex].copy(template = template)
        current.copy(layout = current.layout.copy(pages = pages, version = current.layout.version + 1))
    }

    fun addPage() = update { current ->
        if (current.layout.pages.size >= 5) current
        else current.copy(
            layout = current.layout.copy(
                pages = current.layout.pages + PageModel(0, List(4) { SlotModel() }),
                version = current.layout.version + 1,
            ),
        )
    }

    fun removePage(index: Int) = update { current ->
        if (current.layout.pages.size <= 1 || index !in current.layout.pages.indices) current
        else current.copy(
            layout = current.layout.copy(
                pages = current.layout.pages.filterIndexed { i, _ -> i != index },
                version = current.layout.version + 1,
            ),
        )
    }

    fun setSettings(settings: SettingsModel) = update { it.copy(settings = settings) }
    fun setSources(sources: SourceConfig) = update { it.copy(sources = sources) }
    fun setNote(index: Int, text: String) = update {
        if (index !in 0..3) it else it.copy(notes = it.notes.toMutableList().also { list -> list[index] = text.take(95) })
    }

    fun setWeather(weather: WeatherModel) = update { it.copy(weather = weather) }
    fun setStocks(stocks: List<StockModel>) = update { it.copy(stocks = stocks.take(6)) }
    fun setCalendar(calendar: CalendarModel) = update { it.copy(calendar = calendar) }
    fun setSteps(steps: StepsModel) = update { it.copy(steps = steps) }
    fun setNotification(notification: NotificationModel) = update { it.copy(notifications = notification) }
    fun clearNotifications() = update { it.copy(notifications = NotificationModel()) }
    fun setMedia(media: MediaModel) = update { it.copy(media = media) }
    fun setPhoneBattery(battery: PhoneBattery) = update { it.copy(phoneBattery = battery) }
    fun setDeviceName(name: String) = update { it.copy(deviceName = name) }
    fun setPhotoPath(path: String) = update { it.copy(photoPath = path) }
    fun setLastSync(epoch: Long) = update { it.copy(lastSync = epoch) }
    fun setError(message: String) = update { it.copy(lastError = message) }
}
