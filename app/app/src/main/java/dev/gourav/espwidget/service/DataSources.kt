package dev.gourav.espwidget.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.gourav.espwidget.data.CalendarModel
import dev.gourav.espwidget.data.MediaModel
import dev.gourav.espwidget.data.SourceConfig
import dev.gourav.espwidget.data.StockModel
import dev.gourav.espwidget.data.WeatherModel
import dev.gourav.espwidget.service.NotificationRelay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

object WeatherSource {
    private val json = Json { ignoreUnknownKeys = true }

    data class Location(val lat: Double, val lon: Double, val city: String)

    fun resolveLocation(context: Context, config: SourceConfig): Location {
        if (config.useGps && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val provider = when {
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                }
                val last = provider?.let { manager.getLastKnownLocation(it) }
                if (last != null) {
                    val name = if (Geocoder.isPresent()) {
                        runCatching {
                            @Suppress("DEPRECATION")
                            Geocoder(context, Locale.getDefault()).getFromLocation(last.latitude, last.longitude, 1)
                                ?.firstOrNull()?.locality
                        }.getOrNull()
                    } else null
                    return Location(last.latitude, last.longitude, name ?: config.city)
                }
            }
        }
        return Location(config.latitude, config.longitude, config.city)
    }

    fun fetch(context: Context, config: SourceConfig, celsius: Boolean): WeatherModel? {
        val location = resolveLocation(context, config)
        val unit = if (celsius) "celsius" else "fahrenheit"
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${location.lat}&longitude=${location.lon}" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,is_day" +
                "&hourly=temperature_2m&forecast_hours=12" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&forecast_days=1&timezone=auto&temperature_unit=$unit",
        )
        val body = httpGet(url, 12_000) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val current = root["current"]?.jsonObject ?: return@runCatching null
            val daily = root["daily"]?.jsonObject
            fun number(obj: kotlinx.serialization.json.JsonObject?, key: String): Double =
                obj?.get(key)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            fun first(obj: kotlinx.serialization.json.JsonObject?, key: String): Double =
                obj?.get(key)?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val temp = number(current, "temperature_2m")
            val toC10 = { value: Double -> if (celsius) (value * 10).toInt() else ((value - 32) * 5 / 9 * 10).toInt() }
            val trend = root["hourly"]?.jsonObject?.get("temperature_2m")?.jsonArray
                ?.take(12)
                ?.mapNotNull { it.jsonPrimitive.content.toDoubleOrNull() }
                ?.map { toC10(it) }
                ?: emptyList()
            WeatherModel(
                tempC10 = toC10(temp),
                hiC10 = toC10(first(daily, "temperature_2m_max")),
                loC10 = toC10(first(daily, "temperature_2m_min")),
                code = number(current, "weather_code").toInt(),
                humidity = number(current, "relative_humidity_2m").toInt(),
                precip = first(daily, "precipitation_probability_max").toInt(),
                isDay = number(current, "is_day").toInt() != 0,
                updated = System.currentTimeMillis() / 1000,
                city = location.city.take(15),
                trend = trend,
                valid = true,
            )
        }.getOrNull()
    }

    fun httpGet(url: URL, timeoutMs: Int = 10_000): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ESPWidget/1.0 (Android)")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

object StockSource {
    fun fetch(symbols: List<String>): List<StockModel> {
        return symbols.take(6).mapIndexed { index, raw ->
            val configured = raw.trim().uppercase(Locale.US)
            if (configured.isBlank()) return@mapIndexed StockModel(index = index, valid = false)
            val ticker = yahooTicker(configured)
            val body = WeatherSource.httpGet(
                URL("https://query1.finance.yahoo.com/v8/finance/chart/$ticker?range=3mo&interval=1d"),
                12_000,
            ) ?: return@mapIndexed StockModel(index = index, symbol = configured.substringBefore('.'), valid = false)
            runCatching {
                val root = Json.parseToJsonElement(body).jsonObject
                val result = root["chart"]?.jsonObject?.get("result")?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@runCatching null
                val meta = result["meta"]?.jsonObject ?: return@runCatching null
                val price = meta["regularMarketPrice"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: return@runCatching null
                val changePercent = meta["regularMarketChangePercent"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val currency = meta["currency"]?.jsonPrimitive?.content ?: ""
                val closes = result["indicators"]?.jsonObject
                    ?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("close")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }
                    ?: emptyList()
                val window = closes.filter { it > 0.0 }.takeLast(24)
                val spark = if (window.size >= 2) {
                    val base = window.first()
                    val points = window.map { ((it - base) / base * 10_000).toInt().coerceIn(-2000, 2000) }
                    // The wire field is fixed at 24 points; repeat the last close for
                    // very new listings so the widget never draws a drop to zero.
                    points + List(24 - points.size) { points.last() }
                } else {
                    List(24) { 0 }
                }
                val marketOpen = runCatching {
                    val regular = meta["currentTradingPeriod"]?.jsonObject?.get("regular")?.jsonObject
                    val start = regular?.get("start")?.jsonPrimitive?.content?.toLongOrNull()
                    val end = regular?.get("end")?.jsonPrimitive?.content?.toLongOrNull()
                    val now = System.currentTimeMillis() / 1000
                    start != null && end != null && now in start..end
                }.getOrDefault(isFallbackMarketOpen(configured))
                StockModel(
                    symbol = configured.substringBefore('.').take(11),
                    priceCents = Math.round(price * 100).toInt(),
                    changeBp = Math.round(changePercent * 100).toInt().coerceIn(-9999, 9999),
                    spark = spark,
                    currency = when (currency.uppercase(Locale.US)) {
                        "INR" -> 0
                        "USD" -> 1
                        else -> 2
                    },
                    market = if (marketOpen) 1 else 0,
                    updated = meta["regularMarketTime"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: System.currentTimeMillis() / 1000,
                    index = index,
                    valid = true,
                )
            }.getOrNull() ?: StockModel(index = index, symbol = configured.substringBefore('.'), valid = false)
        }
    }

    /** `.US` maps to a plain US ticker, `.IN` to NSE, everything else passes through. */
    private fun yahooTicker(symbol: String): String = when {
        symbol.endsWith(".US") -> symbol.removeSuffix(".US")
        symbol.endsWith(".IN") -> symbol.removeSuffix(".IN") + ".NS"
        else -> symbol
    }

    private fun isFallbackMarketOpen(symbol: String): Boolean {
        val now = Instant.now().atZone(ZoneOffset.UTC)
        val day = now.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        val time = now.toLocalTime()
        return if (symbol.endsWith(".US")) {
            time >= LocalTime.of(13, 30) && time <= LocalTime.of(20, 0)
        } else {
            time >= LocalTime.of(3, 45) && time <= LocalTime.of(10, 0)
        }
    }
}

object CalendarSource {
    fun next(context: Context, days: Int): CalendarModel? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val begin = System.currentTimeMillis()
        val end = begin + days.coerceIn(1, 60) * 86_400_000L
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString())
            .appendPath(end.toString())
            .build()
        return runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                CalendarModel(
                    title = cursor.getString(0)?.take(39) ?: "Event",
                    start = cursor.getLong(1) / 1000,
                    allDay = cursor.getInt(2) != 0,
                    location = cursor.getString(3)?.take(15) ?: "",
                    updated = System.currentTimeMillis() / 1000,
                    valid = true,
                )
            }
        }.getOrNull()
    }
}

object MediaSource {
    fun current(context: Context): MediaModel? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val component = ComponentName(context, NotificationRelay::class.java)
        return runCatching {
            val controller: MediaController = manager.getActiveSessions(component)
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING || it.metadata != null }
                .maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
                ?: return@runCatching null
            val metadata = controller.metadata ?: return@runCatching null
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val position = controller.playbackState?.position ?: 0
            MediaModel(
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(39) ?: "Unknown track",
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(23) ?: "",
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                progressPct = if (duration > 0) (position * 100 / duration).toInt().coerceIn(0, 100) else 0,
                updated = System.currentTimeMillis() / 1000,
                valid = true,
            )
        }.getOrNull()
    }
}
