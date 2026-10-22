package dev.gourav.espwidget.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.gourav.espwidget.ble.BleManager
import dev.gourav.espwidget.data.PersistedState
import dev.gourav.espwidget.data.PhoneBattery
import dev.gourav.espwidget.data.StepsModel
import dev.gourav.espwidget.data.WeatherModel
import dev.gourav.espwidget.data.WidgetRepository
import dev.gourav.espwidget.protocol.CalendarPayload
import dev.gourav.espwidget.protocol.HelloPayload
import dev.gourav.espwidget.protocol.LayoutPayload
import dev.gourav.espwidget.protocol.LocationPayload
import dev.gourav.espwidget.protocol.MediaPayload
import dev.gourav.espwidget.protocol.Msg
import dev.gourav.espwidget.protocol.NotificationPayload
import dev.gourav.espwidget.protocol.PhotoBeginPayload
import dev.gourav.espwidget.protocol.SettingsPayload
import dev.gourav.espwidget.protocol.StatusPayload
import dev.gourav.espwidget.protocol.StepsPayload
import dev.gourav.espwidget.protocol.StockPayload
import dev.gourav.espwidget.protocol.TextPayload
import dev.gourav.espwidget.protocol.TimePayload
import dev.gourav.espwidget.protocol.WeatherPayload
import dev.gourav.espwidget.protocol.WifiSetPayload
import dev.gourav.espwidget.protocol.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class SyncEngine(
    private val context: Context,
    private val repo: WidgetRepository,
    private val ble: BleManager,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : SensorEventListener {
    private val started = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("sync_engine", Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastWeatherFetch = 0L
    private var lastStockFetch = 0L
    private var lastCalendarFetch = 0L
    private var lastMediaCheck = 0L
    private var lastTimeSend = 0L
    private var stepBase = 0f
    private var stepDay = ""
    private var stepsRegistered = false
    private var lastSentSnapshot: PersistedState? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            if (level >= 0) {
                repo.setPhoneBattery(
                    PhoneBattery(
                        percent = level * 100 / scale.coerceAtLeast(1),
                        charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == android.os.BatteryManager.BATTERY_STATUS_FULL,
                        temperatureC = temp,
                        updated = System.currentTimeMillis() / 1000,
                    ),
                )
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registerSteps()
        repo.update { it.copy(steps = it.steps.copy(goal = it.sources.stepsGoal.toLong())) }

        scope.launch {
            repo.persisted.debounce(450).collectLatest { state ->
                if (ble.connection.value.connected) {
                    // lastSync is written by sendSnapshot itself; ignore that field
                    // when deciding whether the snapshot content actually changed.
                    val comparable = state.copy(lastSync = 0)
                    if (comparable != lastSentSnapshot) {
                        sendSnapshot(state)
                    }
                }
            }
        }

        scope.launch {
            ble.lastError.collect { message ->
                repo.updateLink { link -> link.copy(error = message) }
            }
        }

        scope.launch {
            ble.connection.collectLatest { link ->
                if (link.connected) {
                    repo.updateLink { it.copy(connected = true, connecting = false, scanning = false, error = null) }
                    link.address?.let { address -> prefs.edit().putString("last_address", address).apply() }
                    delay(350)
                    lastSentSnapshot = null
                    sendSnapshot(repo.persisted.value)
                    ble.send(Msg.STATUS_REQ)
                    refreshWeather(force = false)
                    refreshCalendar()
                    refreshMedia()
                    refreshStocks(force = false)
                } else {
                    repo.updateLink {
                        it.copy(
                            connected = false,
                            connecting = link.connecting,
                            scanning = link.scanning,
                            deviceName = link.deviceName ?: it.deviceName,
                            error = link.error,
                        )
                    }
                }
            }
        }

        scope.launch {
            ble.frames.collect { frame ->
                when (frame.type) {
                    Msg.HELLO -> HelloPayload.decode(frame.payload)?.let {
                        repo.updateLink { link ->
                            link.copy(deviceName = it.name, firmware = it.firmware)
                        }
                        repo.setDeviceName(it.name)
                        repo.log("Widget ${it.name} · firmware ${it.firmware}")
                        // Hello only arrives after the TX subscription is live, so
                        // RX is guaranteed to be writable now. Re-send the full
                        // snapshot instead of racing service discovery.
                        lastSentSnapshot = null
                        sendSnapshot(repo.persisted.value)
                    }
                    Msg.STATUS -> StatusPayload.decode(frame.payload)?.let {
                        repo.updateLink { link -> link.copy(status = DeviceStatusMapper.from(it)) }
                    }
                    Msg.WIFI_SCAN_RESULT -> Unit
                    Msg.TIME_REQ -> sendTime()
                    Msg.PING -> ble.send(Msg.PONG, frame.payload)
                    Msg.ACK -> Unit
                    Msg.NACK -> repo.log("Widget rejected message 0x${frame.type.toString(16)}")
                }
            }
        }

        scope.launch {
            ble.wifiNetworks.collect { networks ->
                repo.updateLink { link -> link.copy(wifiScan = networks.map { dev.gourav.espwidget.data.WifiNetwork(it.ssid, it.rssi, it.secure) }) }
            }
        }

        scope.launch { ticker() }

        val lastAddress = prefs.getString("last_address", null)
        if (lastAddress != null && ble.hasPermissions()) {
            scope.launch {
                delay(1200)
                ble.connect(lastAddress)
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(batteryReceiver) }
        sensorManager.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    private fun registerSteps() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        if (!sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))) return
        stepsRegistered = true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (day != stepDay) {
            stepDay = day
            stepBase = prefs.getFloat("base_$day", event.values[0])
            if (!prefs.contains("base_$day")) prefs.edit().putFloat("base_$day", stepBase).apply()
        }
        val total = event.values[0]
        val steps = (total - stepBase).toLong().coerceAtLeast(0)
        val goal = repo.persisted.value.sources.stepsGoal.toLong()
        repo.setSteps(
            StepsModel(
                steps = steps,
                goal = goal,
                updated = System.currentTimeMillis() / 1000,
                valid = true,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private suspend fun ticker() {
        while (true) {
            val now = System.currentTimeMillis()
            if (!stepsRegistered) registerSteps()
            if (ble.connection.value.connected) {
                if (now - lastTimeSend > 30_000) sendTime()
                if (now - lastMediaCheck > 5_000) refreshMedia()
                refreshWeather(force = false)
                refreshStocks(force = false)
                if (now - lastCalendarFetch > 300_000) refreshCalendar()
            } else {
                refreshWeather(force = false)
                refreshStocks(force = false)
            }
            delay(5_000)
        }
    }

    fun sendSnapshot(state: PersistedState) {
        if (!ble.connection.value.connected) return
        lastSentSnapshot = state.copy(lastSync = 0)
        sendTime()
        val version = state.layout.version.coerceAtLeast(1)
        ble.send(Msg.LAYOUT, LayoutPayload(state.layout.toPayload(), version).encode())
        val s = state.settings
        ble.send(
            Msg.SETTINGS,
            SettingsPayload(
                brightness = s.brightness.coerceIn(10, 255),
                sleepSec = s.sleepSec.coerceIn(0, 240),
                rotation = s.rotation.coerceIn(0, 3),
                twelveHour = s.twelveHour,
                celsius = s.celsius,
                wifiEnabled = s.wifiEnabled,
                showSeconds = s.showSeconds,
                currentPage = s.currentPage.coerceIn(0, state.layout.pages.size - 1),
            ).encode(),
        )
        if (state.weather.valid) ble.send(Msg.WEATHER, state.weather.toPayload().encode())
        state.notes.forEachIndexed { index, note ->
            if (note.isNotBlank()) ble.send(Msg.TEXT, TextPayload(index, note).encode())
        }
        state.stocks.filter { it.valid }.forEach { stock ->
            ble.send(Msg.STOCK, stock.toPayload().encode())
        }
        state.notifications.takeIf { it.valid }?.let {
            ble.send(Msg.NOTIFICATION, NotificationPayload(it.app, it.title, it.body, it.count, it.updated).encode())
        }
        state.media.takeIf { it.valid }?.let {
            ble.send(Msg.MEDIA, MediaPayload(it.title, it.artist, it.playing, it.progressPct, it.updated).encode())
        }
        state.calendar.takeIf { it.valid }?.let {
            ble.send(Msg.CALENDAR, CalendarPayload(it.title, it.start, it.allDay, it.location, it.updated).encode())
        }
        state.steps.takeIf { it.valid }?.let {
            ble.send(Msg.STEPS, StepsPayload(it.steps, it.goal, it.updated).encode())
        }
        ble.send(
            Msg.LOCATION,
            LocationPayload(
                (state.sources.latitude * 1_000_000).toInt(),
                (state.sources.longitude * 1_000_000).toInt(),
                state.sources.city,
            ).encode(),
        )
        repo.setLastSync(System.currentTimeMillis() / 1000)
    }

    fun sendTime() {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val payload = TimePayload(
            epoch = now / 1000,
            tzOffsetMinutes = zone.getOffset(now) / 60_000,
            dst = zone.inDaylightTime(Date(now)),
        )
        if (ble.send(Msg.TIME, payload.encode())) lastTimeSend = now
    }

    fun refreshWeather(force: Boolean) {
        val state = repo.persisted.value
        val interval = state.sources.weatherMinutes.coerceIn(5, 240) * 60_000L
        val now = System.currentTimeMillis()
        if (!force && state.weather.valid && now - lastWeatherFetch < interval) return
        lastWeatherFetch = now
        scope.launch {
            val weather = withContext(Dispatchers.IO) {
                WeatherSource.fetch(context, state.sources, celsius = true)
            }
            if (weather != null) {
                repo.setWeather(weather)
                repo.setError("")
                repo.log("Weather ${weather.tempC10 / 10}°C · ${weather.city}")
            } else if (state.weather.valid) {
                repo.log("Weather refresh failed; keeping last reading")
            }
        }
    }

    fun refreshStocks(force: Boolean) {
        val state = repo.persisted.value
        val interval = state.sources.stockMinutes.coerceIn(5, 240) * 60_000L
        val now = System.currentTimeMillis()
        if (!force && now - lastStockFetch < interval) return
        if (state.sources.stockSymbols.none { it.isNotBlank() }) return
        lastStockFetch = now
        scope.launch {
            val stocks = withContext(Dispatchers.IO) { StockSource.fetch(state.sources.stockSymbols) }
            if (stocks.any { it.valid }) {
                val merged = List(6) { index ->
                    stocks.getOrNull(index) ?: state.stocks.getOrNull(index)
                        ?: dev.gourav.espwidget.data.StockModel(index = index)
                }
                repo.setStocks(merged)
                repo.log("Watchlist updated (${stocks.count { it.valid }} symbols)")
            }
        }
    }

    fun refreshCalendar() {
        lastCalendarFetch = System.currentTimeMillis()
        val state = repo.persisted.value
        if (!state.sources.calendarEnabled) return
        scope.launch {
            val event = withContext(Dispatchers.IO) { CalendarSource.next(context, state.sources.calendarDays) }
            if (event != null) repo.setCalendar(event)
        }
    }

    fun refreshMedia() {
        lastMediaCheck = System.currentTimeMillis()
        val state = repo.persisted.value
        if (!state.sources.mediaEnabled) return
        val media = MediaSource.current(context) ?: return
        if (media.title != state.media.title || media.artist != state.media.artist ||
            media.playing != state.media.playing || kotlin.math.abs(media.progressPct - state.media.progressPct) > 2
        ) {
            repo.setMedia(media)
        }
    }

    fun requestWifiScan() {
        ble.send(Msg.WIFI_SCAN)
        repo.log("Asked widget to scan WiFi")
    }

    fun sendWifiCredentials(ssid: String, password: String) {
        ble.send(Msg.WIFI_SET, WifiSetPayload(ssid, password, connect = true).encode())
        repo.log("WiFi credentials sent for $ssid")
    }

    fun forgetWifi() {
        ble.send(Msg.WIFI_FORGET)
        repo.log("WiFi credentials cleared")
    }

    fun factoryReset() {
        ble.send(Msg.FACTORY_RESET, byteArrayOf(0xA5.toByte(), 0, 0, 0))
        repo.log("Factory reset requested")
    }

    fun sendNote(index: Int, text: String) {
        ble.send(Msg.TEXT, TextPayload(index, text).encode())
    }

    fun sendPhoto(bytes: ByteArray, onProgress: (Float) -> Unit = {}) {
        if (!ble.connection.value.connected) {
            repo.setError("Connect to the widget before sending a photo")
            return
        }
        scope.launch {
            val begin = PhotoBeginPayload(bytes.size, 240, 240).encode()
            ble.send(Msg.PHOTO_BEGIN, begin)
            var offset = 0
            val chunkSize = ble.maxPhotoChunkBytes()
            while (offset < bytes.size) {
                val size = minOf(chunkSize, bytes.size - offset)
                val payload = ByteArray(4 + size)
                Wire.putU16(payload, 0, offset)
                Wire.putU16(payload, 2, size)
                bytes.copyInto(payload, 4, offset, offset + size)
                ble.send(Msg.PHOTO_DATA, payload)
                offset += size
                onProgress(offset.toFloat() / bytes.size)
                delay(12)
            }
            ble.send(Msg.PHOTO_END)
            repo.setLastSync(System.currentTimeMillis() / 1000)
            repo.log("Photo sent (${bytes.size / 1024} KB)")
        }
    }

    fun sendPhotoFromUri(uri: android.net.Uri) {
        val resolver = context.contentResolver
        val bytes = runCatching {
            val input = resolver.openInputStream(uri) ?: return@runCatching null
            val bitmap = android.graphics.BitmapFactory.decodeStream(input)
            input.close()
            bitmap ?: return@runCatching null
            val size = minOf(bitmap.width, bitmap.height)
            val cropped = android.graphics.Bitmap.createBitmap(
                bitmap,
                (bitmap.width - size) / 2,
                (bitmap.height - size) / 2,
                size,
                size,
            )
            val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, 240, 240, true)
            var quality = 78
            var out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            while (out.size() > 40_000 && quality > 30) {
                quality -= 8
                out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            }
            if (scaled !== cropped) cropped.recycle()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            val data = out.toByteArray()
            val file = java.io.File(context.filesDir, "photo.jpg")
            file.writeBytes(data)
            repo.setPhotoPath(file.absolutePath)
            data
        }.getOrNull()
        if (bytes == null) {
            repo.setError("Could not read that image")
            return
        }
        sendPhoto(bytes)
    }

    private fun WeatherModel.toPayload(): WeatherPayload = WeatherPayload(
        tempC10 = tempC10,
        hiC10 = hiC10,
        loC10 = loC10,
        code = code,
        humidity = humidity,
        precip = precip,
        isDay = isDay,
        updated = updated,
        city = city.take(15),
        trend = trend.take(12),
    )

    private fun dev.gourav.espwidget.data.StockModel.toPayload(): StockPayload = StockPayload(
        symbol = symbol.take(11),
        priceCents = priceCents,
        changeBp = changeBp,
        spark = spark.take(24),
        currency = currency,
        market = market,
        updated = updated,
        index = index,
    )
}

private object DeviceStatusMapper {
    fun from(status: StatusPayload): dev.gourav.espwidget.data.DeviceStatus =
        dev.gourav.espwidget.data.DeviceStatus(
            page = status.page,
            bleConnected = status.bleConnected,
            wifiState = status.wifiState,
            rssi = status.rssi,
            battery = status.battery,
            millivolts = status.millivolts,
            heapKb = status.heapKb,
            uptime = status.uptime,
            epoch = status.epoch,
            layoutVersion = status.layoutVersion,
            charging = status.charging,
            usb = status.usb,
            chargeFull = status.chargeFull,
        )
}

private fun relativeTime(epoch: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epoch * 1000))
