package dev.gourav.espwidget.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class WidgetType(val id: Int, val title: String) {
    CLOCK(0, "Clock"),
    WEATHER(1, "Weather"),
    BATTERY(2, "Battery"),
    STOCKS(3, "Stocks"),
    NOTIFICATIONS(4, "Notifications"),
    MEDIA(5, "Now playing"),
    CALENDAR(6, "Calendar"),
    STEPS(7, "Steps"),
    TEXT(8, "Note"),
    PHOTO(9, "Photo"),
    PHONE_BATTERY(10, "Phone battery");

    companion object {
        fun from(id: Int): WidgetType? = entries.firstOrNull { it.id == id }
    }
}

data class SlotConfig(val type: Int, val option: Int = 0, val accent: Int = 0) {
    companion object { const val EMPTY = 0xFF }
}

data class PageConfig(val template: Int = 0, val slots: List<SlotConfig> = List(4) { SlotConfig(SlotConfig.EMPTY) })

data class LayoutPayload(val pages: List<PageConfig>, val version: Int = 1) {
    fun encode(): ByteArray {
        val buf = Wire.littleEndian(89)
        buf.put(pages.size.toByte())
        for (page in 0 until 5) {
            val config = pages.getOrNull(page) ?: PageConfig()
            buf.put(config.template.toByte())
            for (slot in 0 until 4) {
                val s = config.slots.getOrNull(slot) ?: SlotConfig(SlotConfig.EMPTY)
                buf.put(s.type.toByte()).put(s.option.toByte()).put(s.accent.toByte()).put(0)
            }
        }
        buf.put(version.toByte()).put(0).put(0)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): LayoutPayload? {
            if (bytes.size < 89) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buf.get().toInt() and 0xFF
            if (count !in 1..5) return null
            val pages = ArrayList<PageConfig>(count)
            repeat(5) {
                val template = buf.get().toInt() and 0xFF
                val slots = ArrayList<SlotConfig>(4)
                repeat(4) {
                    val type = buf.get().toInt() and 0xFF
                    val option = buf.get().toInt() and 0xFF
                    val accent = buf.get().toInt() and 0xFF
                    buf.get()
                    slots += SlotConfig(type, option, accent)
                }
                pages += PageConfig(template, slots)
            }
            return LayoutPayload(pages.take(count), buf.get().toInt() and 0xFF)
        }
    }
}

data class SettingsPayload(
    val brightness: Int = 200,
    val sleepSec: Int = 60,
    val rotation: Int = 3,
    val twelveHour: Boolean = false,
    val celsius: Boolean = true,
    val wifiEnabled: Boolean = true,
    val showSeconds: Boolean = false,
    val currentPage: Int = 0,
    val darkTheme: Boolean = false,
    val stocksMode: Boolean = false,
    val photoSwapRb: Boolean = false,
) {
    fun encode(): ByteArray {
        var flags = 0
        if (twelveHour) flags = flags or 0x01
        if (celsius) flags = flags or 0x02
        if (wifiEnabled) flags = flags or 0x04
        if (showSeconds) flags = flags or 0x08
        if (darkTheme) flags = flags or 0x10
        if (stocksMode) flags = flags or 0x20
        if (photoSwapRb) flags = flags or 0x40
        return Wire.littleEndian(8)
            .put(brightness.toByte()).put(sleepSec.toByte()).put(rotation.toByte())
            .put(flags.toByte()).put(currentPage.toByte()).put(0).put(0).put(0).array()
    }
}

data class TimePayload(val epoch: Long, val tzOffsetMinutes: Int, val dst: Boolean = false) {
    fun encode(): ByteArray = Wire.littleEndian(8)
        .putInt(epoch.toInt()).putShort(tzOffsetMinutes.toShort())
        .put(if (dst) 1 else 0).put(0).array()
}

data class WeatherPayload(
    val tempC10: Int,
    val hiC10: Int,
    val loC10: Int,
    val code: Int,
    val humidity: Int,
    val precip: Int,
    val isDay: Boolean,
    val updated: Long,
    val city: String,
    val trend: List<Int> = emptyList(),
) {
    fun encode(): ByteArray {
        val buf = Wire.littleEndian(58)
        buf.putShort(tempC10.toShort()).putShort(hiC10.toShort()).putShort(loC10.toShort())
        buf.put(code.toByte()).put(humidity.toByte()).put(precip.toByte()).put(if (isDay) 1 else 0)
        repeat(12) { i -> buf.putShort((trend.getOrNull(i) ?: 0).toShort()) }
        buf.putShort(0).putInt(updated.toInt())
        buf.putFixed(city, 16).put(trend.size.coerceIn(0, 12).toByte()).put(0)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): WeatherPayload? {
            if (bytes.size < 58) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val temp = buf.short.toInt(); val hi = buf.short.toInt(); val lo = buf.short.toInt()
            val code = buf.get().toInt() and 0xFF
            val humidity = buf.get().toInt() and 0xFF
            val precip = buf.get().toInt() and 0xFF
            val day = buf.get().toInt() != 0
            val trend = List(12) { buf.short.toInt() }
            buf.short
            val updated = buf.int.toLong() and 0xFFFFFFFFL
            val city = buf.readFixed(16)
            val count = buf.get().toInt() and 0xFF
            buf.get()
            return WeatherPayload(temp, hi, lo, code, humidity, precip, day, updated, city, trend.take(count.coerceIn(0, 12)))
        }
    }
}

data class BatteryPayload(val percent: Int, val millivolts: Int, val charging: Boolean, val usb: Boolean, val updated: Long) {
    companion object {
        fun decode(bytes: ByteArray): BatteryPayload? {
            if (bytes.size < 9) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val percent = buf.get().toInt() and 0xFF
            val mv = buf.short.toInt() and 0xFFFF
            val charging = buf.get().toInt() != 0
            val usb = buf.get().toInt() != 0
            val updated = buf.int.toLong() and 0xFFFFFFFFL
            return BatteryPayload(percent, mv, charging, usb, updated)
        }
    }
}

data class StockPayload(
    val symbol: String,
    val priceCents: Int,
    val changeBp: Int,
    val spark: List<Int>,
    val currency: Int,
    val market: Int,
    val updated: Long,
    val index: Int,
) {
    fun encode(): ByteArray {
        val buf = Wire.littleEndian(76)
        buf.putFixed(symbol, 12).putInt(priceCents).putShort(changeBp.toShort())
        repeat(24) { i -> buf.putShort((spark.getOrNull(i) ?: 0).toShort()) }
        buf.put(currency.toByte()).put(market.toByte()).putInt(updated.toInt()).put(index.toByte())
        buf.put(0).put(0).put(0)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): StockPayload? {
            if (bytes.size < 76) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val symbol = buf.readFixed(12)
            val price = buf.int
            val change = buf.short.toInt()
            val spark = List(24) { buf.short.toInt() }
            val currency = buf.get().toInt() and 0xFF
            val market = buf.get().toInt() and 0xFF
            val updated = buf.int.toLong() and 0xFFFFFFFFL
            val index = buf.get().toInt() and 0xFF
            return StockPayload(symbol, price, change, spark, currency, market, updated, index)
        }
    }
}

data class NotificationPayload(
    val app: String, val title: String, val body: String, val count: Int, val updated: Long,
) {
    fun encode(): ByteArray = Wire.littleEndian(118)
        .putFixed(app, 16).putFixed(title, 40).putFixed(body, 56)
        .put(count.toByte()).put(0).putInt(updated.toInt()).array()

    companion object {
        fun decode(bytes: ByteArray): NotificationPayload? {
            if (bytes.size < 118) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val app = buf.readFixed(16); val title = buf.readFixed(40); val body = buf.readFixed(56)
            val count = buf.get().toInt() and 0xFF
            buf.get()
            val updated = buf.int.toLong() and 0xFFFFFFFFL
            return NotificationPayload(app, title, body, count, updated)
        }
    }
}

data class MediaPayload(
    val title: String, val artist: String, val playing: Boolean, val progressPct: Int, val updated: Long,
) {
    fun encode(): ByteArray = Wire.littleEndian(70)
        .putFixed(title, 40).putFixed(artist, 24)
        .put(if (playing) 1 else 0).put(progressPct.toByte()).putInt(updated.toInt()).array()

    companion object {
        fun decode(bytes: ByteArray): MediaPayload? {
            if (bytes.size < 70) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val title = buf.readFixed(40); val artist = buf.readFixed(24)
            val playing = buf.get().toInt() != 0
            val progress = buf.get().toInt() and 0xFF
            val updated = buf.int.toLong() and 0xFFFFFFFFL
            return MediaPayload(title, artist, playing, progress, updated)
        }
    }
}

data class CalendarPayload(
    val title: String, val start: Long, val allDay: Boolean, val location: String, val updated: Long,
) {
    fun encode(): ByteArray = Wire.littleEndian(65)
        .putFixed(title, 40).putInt(start.toInt()).put(if (allDay) 1 else 0)
        .putFixed(location, 16).putInt(updated.toInt()).array()

    companion object {
        fun decode(bytes: ByteArray): CalendarPayload? {
            if (bytes.size < 65) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val title = buf.readFixed(40)
            val start = buf.int.toLong() and 0xFFFFFFFFL
            val allDay = buf.get().toInt() != 0
            val location = buf.readFixed(16)
            val updated = buf.int.toLong() and 0xFFFFFFFFL
            return CalendarPayload(title, start, allDay, location, updated)
        }
    }
}

data class PhoneBatteryPayload(val percent: Int, val charging: Boolean, val updated: Long) {
    fun encode(): ByteArray = Wire.littleEndian(6)
        .put(percent.coerceIn(0, 100).toByte()).put((if (charging) 1 else 0).toByte())
        .putInt(updated.toInt()).array()
}

data class StepsPayload(val steps: Long, val goal: Long, val updated: Long) {
    fun encode(): ByteArray = Wire.littleEndian(12)
        .putInt(steps.toInt()).putInt(goal.toInt()).putInt(updated.toInt()).array()

    companion object {
        fun decode(bytes: ByteArray): StepsPayload? {
            if (bytes.size < 12) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return StepsPayload(buf.int.toLong() and 0xFFFFFFFFL, buf.int.toLong() and 0xFFFFFFFFL,
                buf.int.toLong() and 0xFFFFFFFFL)
        }
    }
}

data class StatusPayload(
    val page: Int, val bleConnected: Boolean, val wifiState: Int, val rssi: Int,
    val battery: Int, val millivolts: Int, val heapKb: Int, val uptime: Long,
    val epoch: Long, val layoutVersion: Int, val charging: Boolean = false,
    val usb: Boolean = false, val chargeFull: Boolean = false,
) {
    companion object {
        fun decode(bytes: ByteArray): StatusPayload? {
            if (bytes.size < 22) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val page = buf.get().toInt() and 0xFF
            val ble = buf.get().toInt() != 0
            val wifi = buf.get().toInt() and 0xFF
            val rssi = buf.get().toInt()
            val battery = buf.get().toInt() and 0xFF
            val mv = buf.short.toInt() and 0xFFFF
            val heap = buf.short.toInt() and 0xFFFF
            val uptime = buf.int.toLong() and 0xFFFFFFFFL
            val epoch = buf.int.toLong() and 0xFFFFFFFFL
            val layout = buf.short.toInt() and 0xFFFF
            val charging = buf.get().toInt() != 0
            val usb = buf.get().toInt() != 0
            val full = buf.get().toInt() != 0
            return StatusPayload(page, ble, wifi, rssi, battery, mv, heap, uptime, epoch, layout, charging, usb, full)
        }
    }
}

data class HelloPayload(
    val name: String, val firmware: String, val protocol: Int, val maxFrame: Int,
    val width: Int, val height: Int, val pages: Int,
) {
    companion object {
        fun decode(bytes: ByteArray): HelloPayload? {
            if (bytes.size < 30) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val name = buf.readFixed(16)
            val major = buf.get().toInt() and 0xFF; val minor = buf.get().toInt() and 0xFF; val patch = buf.get().toInt() and 0xFF
            val protocol = buf.get().toInt() and 0xFF
            val maxFrame = buf.short.toInt() and 0xFFFF
            val width = buf.short.toInt() and 0xFFFF
            val height = buf.short.toInt() and 0xFFFF
            val pages = buf.get().toInt() and 0xFF
            return HelloPayload(name, "$major.$minor.$patch", protocol, maxFrame, width, height, pages)
        }
    }
}

data class WifiAp(val ssid: String, val rssi: Int, val secure: Boolean)
data class WifiScanResult(val aps: List<WifiAp>) {
    companion object {
        fun decode(bytes: ByteArray): WifiScanResult? {
            if (bytes.size < 1) return null
            val count = (bytes[0].toInt() and 0xFF).coerceAtMost(8)
            val result = ArrayList<WifiAp>(count)
            var offset = 1
            repeat(count) {
                if (offset + 34 > bytes.size) return WifiScanResult(result)
                val ssid = bytes.copyOfRange(offset, offset + 32).toString(Charsets.UTF_8).trimEnd('\u0000')
                val rssi = bytes[offset + 32].toInt()
                val secure = bytes[offset + 33].toInt() != 0
                result += WifiAp(ssid, rssi, secure)
                offset += 34
            }
            return WifiScanResult(result)
        }
    }
}

data class HelloAckPayload(val appName: String = "ESP Widget Android", val capabilities: Int = 0x0F) {
    fun encode(): ByteArray = Wire.littleEndian(24)
        .put(Wire.VERSION.toByte()).put(1).put(0).put(0)
        .putFixed(appName, 16).putInt(capabilities).array()
}

data class WifiSetPayload(val ssid: String, val password: String, val connect: Boolean) {
    fun encode(): ByteArray = Wire.littleEndian(97)
        .putFixed(ssid, 32).putFixed(password, 64).put(if (connect) 1 else 0).array()
}

data class LocationPayload(val latE6: Int, val lonE6: Int, val city: String) {
    fun encode(): ByteArray = Wire.littleEndian(24)
        .putInt(latE6).putInt(lonE6).putFixed(city, 16).array()
}

data class TextPayload(val slot: Int, val text: String) {
    fun encode(): ByteArray = Wire.littleEndian(100).putFixed(text, 96).put(slot.toByte()).put(0).put(0).put(0).array()
}

data class PhotoBeginPayload(val total: Int, val width: Int = 240, val height: Int = 240) {
    fun encode(): ByteArray = Wire.littleEndian(10)
        .putShort(total.toShort()).putShort(width.toShort()).putShort(height.toShort()).put(1).put(0).array()
}

fun ByteBuffer.putFixed(value: String, size: Int): ByteBuffer {
    val bytes = value.toByteArray(Charsets.UTF_8)
    put(bytes, 0, minOf(bytes.size, size))
    repeat(size - minOf(bytes.size, size)) { put(0) }
    return this
}

fun ByteBuffer.readFixed(size: Int): String {
    val bytes = ByteArray(size)
    get(bytes)
    val end = bytes.indexOf(0).let { if (it < 0) size else it }
    return bytes.copyOfRange(0, end).toString(Charsets.UTF_8)
}
