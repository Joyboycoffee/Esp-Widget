package dev.gourav.espwidget.data

import dev.gourav.espwidget.protocol.PageConfig
import dev.gourav.espwidget.protocol.SlotConfig
import kotlinx.serialization.Serializable

@Serializable
data class SlotModel(val type: Int = EMPTY, val option: Int = 0, val accent: Int = 0) {
    val isEmpty get() = type == EMPTY

    companion object { const val EMPTY = 0xFF }
}

@Serializable
data class PageModel(
    val template: Int = 0,
    val slots: List<SlotModel> = List(4) { SlotModel() },
)

@Serializable
data class LayoutModel(
    val pages: List<PageModel> = defaultPages(),
    val version: Int = 1,
) {
    fun toPayload(): List<PageConfig> = pages.map { page ->
        PageConfig(page.template, List(4) { i ->
            val slot = page.slots.getOrNull(i) ?: SlotModel()
            SlotConfig(slot.type, slot.option, slot.accent)
        })
    }

    companion object {
        fun defaultPages(): List<PageModel> = listOf(
            PageModel(0, listOf(SlotModel(0, 0, 0), SlotModel(1, 0, 2), SlotModel(2, 0, 1), SlotModel())),
            PageModel(0, listOf(SlotModel(3, 0, 4), SlotModel(4, 0, 5), SlotModel(5, 0, 3), SlotModel())),
            PageModel(0, listOf(SlotModel(6, 0, 3), SlotModel(7, 0, 1), SlotModel(8, 0, 0), SlotModel())),
            PageModel(0, listOf(SlotModel(9, 0, 2), SlotModel(0, 0, 0), SlotModel(1, 0, 1), SlotModel())),
            PageModel(1, List(4) { SlotModel() }),
        )
    }
}

@Serializable
data class SettingsModel(
    val brightness: Int = 200,
    val sleepSec: Int = 60,
    val rotation: Int = 3,
    val twelveHour: Boolean = false,
    val celsius: Boolean = true,
    val wifiEnabled: Boolean = true,
    val showSeconds: Boolean = false,
    val currentPage: Int = 0,
    /** 0 = follow the phone, 1 = light, 2 = dark. The widget follows the resolved value. */
    val themeMode: Int = 0,
    /** Use the whole widget display for the stock watchlist. */
    val stocksMode: Boolean = false,
    val photoSwapRb: Boolean = true,
)

@Serializable
data class SourceConfig(
    val city: String = "Mumbai",
    val latitude: Double = 19.076,
    val longitude: Double = 72.8777,
    val useGps: Boolean = true,
    val weatherMinutes: Int = 30,
    val stockSymbols: List<String> = listOf("AAPL.US", "MSFT.US", "RELIANCE.IN"),
    val stockMinutes: Int = 15,
    val stepsGoal: Int = 8000,
    val calendarDays: Int = 7,
    val notificationsEnabled: Boolean = true,
    val mediaEnabled: Boolean = true,
    val calendarEnabled: Boolean = true,
    /** Last Wi-Fi network sent to the widget (name only; the password is never stored). */
    val wifiSsid: String = "",
)

@Serializable
data class WeatherModel(
    val tempC10: Int = 0,
    val hiC10: Int = 0,
    val loC10: Int = 0,
    val code: Int = 0,
    val humidity: Int = 0,
    val precip: Int = 0,
    val isDay: Boolean = true,
    val updated: Long = 0,
    val city: String = "",
    val trend: List<Int> = emptyList(),
    val valid: Boolean = false,
)

@Serializable
data class StockModel(
    val symbol: String = "",
    val priceCents: Int = 0,
    val changeBp: Int = 0,
    val spark: List<Int> = emptyList(),
    val currency: Int = 1,
    val market: Int = 0,
    val updated: Long = 0,
    val index: Int = 0,
    val valid: Boolean = false,
)

@Serializable
data class NotificationModel(
    val app: String = "",
    val title: String = "",
    val body: String = "",
    val count: Int = 0,
    val updated: Long = 0,
    val valid: Boolean = false,
)

@Serializable
data class MediaModel(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val progressPct: Int = 0,
    val updated: Long = 0,
    val valid: Boolean = false,
)

@Serializable
data class CalendarModel(
    val title: String = "",
    val start: Long = 0,
    val allDay: Boolean = false,
    val location: String = "",
    val updated: Long = 0,
    val valid: Boolean = false,
)

@Serializable
data class StepsModel(
    val steps: Long = 0,
    val goal: Long = 8000,
    val updated: Long = 0,
    val valid: Boolean = false,
)

/** Highest widget type id the firmware knows. */
const val LAST_WIDGET_TYPE = 20

/** Same order as kZones in the firmware (the slot option is the index). */
val WORLD_CITIES = listOf(
    "London" to "Europe/London", "New York" to "America/New_York", "Los Angeles" to "America/Los_Angeles",
    "Chicago" to "America/Chicago", "Berlin" to "Europe/Berlin", "Paris" to "Europe/Paris",
    "Dubai" to "Asia/Dubai", "Singapore" to "Asia/Singapore", "Tokyo" to "Asia/Tokyo",
    "Sydney" to "Australia/Sydney", "Hong Kong" to "Asia/Hong_Kong", "Toronto" to "America/Toronto",
    "Moscow" to "Europe/Moscow", "Kathmandu" to "Asia/Kathmandu", "New Delhi" to "Asia/Kolkata", "UTC" to "UTC",
)

val PROGRESS_KINDS = listOf("Day", "Week", "Month", "Year")

@Serializable
data class CountdownModel(val label: String = "", val target: Long = 0)

@Serializable
data class AlarmModel(val epoch: Long = 0, val label: String = "")

@Serializable
data class PhoneBattery(
    val percent: Int = 0,
    val charging: Boolean = false,
    val temperatureC: Float = 0f,
    val updated: Long = 0,
)

@Serializable
data class PersistedState(
    val layout: LayoutModel = LayoutModel(),
    val settings: SettingsModel = SettingsModel(),
    val sources: SourceConfig = SourceConfig(),
    val notes: List<String> = listOf("Make something lovely today.", "", "", ""),
    val weather: WeatherModel = WeatherModel(),
    val stocks: List<StockModel> = List(6) { StockModel(index = it) },
    val calendar: CalendarModel = CalendarModel(),
    val steps: StepsModel = StepsModel(),
    val notifications: NotificationModel = NotificationModel(),
    val media: MediaModel = MediaModel(),
    val phoneBattery: PhoneBattery = PhoneBattery(),
    val countdown: CountdownModel = CountdownModel(),
    val nextAlarm: AlarmModel = AlarmModel(),
    val deviceName: String = "",
    val photoPath: String = "",
    val lastSync: Long = 0,
    val lastError: String = "",
)

data class LinkState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val scanning: Boolean = false,
    val deviceName: String? = null,
    val address: String? = null,
    val firmware: String? = null,
    val status: DeviceStatus? = null,
    val wifiScan: List<WifiNetwork> = emptyList(),
    val error: String? = null,
    val log: List<String> = emptyList(),
)

data class DeviceStatus(
    val page: Int = 0,
    val bleConnected: Boolean = false,
    val wifiState: Int = 0,
    val rssi: Int = 0,
    val battery: Int = 0,
    val millivolts: Int = 0,
    val heapKb: Int = 0,
    val uptime: Long = 0,
    val epoch: Long = 0,
    val layoutVersion: Int = 0,
    val charging: Boolean = false,
    val usb: Boolean = false,
    val chargeFull: Boolean = false,
)

data class WifiNetwork(val ssid: String, val rssi: Int, val secure: Boolean)

data class AppState(
    val persisted: PersistedState = PersistedState(),
    val link: LinkState = LinkState(),
)

fun accentColor(index: Int): Long = when (index) {
    0 -> 0xFFF4B8A4
    1 -> 0xFFA8C6AE
    2 -> 0xFFA9CBE8
    3 -> 0xFFC6B8E6
    4 -> 0xFFF2D69B
    5 -> 0xFFF0B7C4
    else -> 0xFFE4DED6
}

fun widgetName(type: Int): String = when (type) {
    0 -> "Clock"
    1 -> "Weather"
    2 -> "Battery"
    3 -> "Stocks"
    4 -> "Notifications"
    5 -> "Now playing"
    6 -> "Calendar"
    7 -> "Steps"
    8 -> "Note"
    9 -> "Photo"
    10 -> "Phone battery"
    11 -> "Analog clock"
    12 -> "World clock"
    13 -> "Day / year progress"
    14 -> "Sunrise & sunset"
    15 -> "Moon phase"
    16 -> "Month calendar"
    17 -> "Next alarm"
    18 -> "Countdown"
    19 -> "Widget status"
    20 -> "Thought of the day"
    else -> "Empty"
}
