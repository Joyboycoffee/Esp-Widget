package dev.gourav.espwidget.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.MainActivity
import dev.gourav.espwidget.data.AppState
import dev.gourav.espwidget.data.PersistedState
import dev.gourav.espwidget.data.accentColor
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class WidgetKind(val type: Int) {
    CLOCK(0), WEATHER(1), BATTERY(2), STOCKS(3), NOTIFICATIONS(4),
    MEDIA(5), CALENDAR(6), STEPS(7), TEXT(8), PHOTO(9);

    companion object {
        fun of(type: Int): WidgetKind = entries.getOrElse(type) { CLOCK }
    }
}

class EspWidgetGlance(private val kind: WidgetKind) : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AppGraph.init(context)
        val state = AppGraph.repository.state.first()
        val photo = if (kind == WidgetKind.PHOTO) loadPhoto(state.persisted.photoPath) else null
        provideContent { WidgetContent(kind, state, photo, context) }
    }

    private fun loadPhoto(path: String): Bitmap? {
        if (path.isBlank()) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }
}

private val cream = Color(0xFFFDF8F3)
private val card = Color(0xFFFFFDFB)
private val ink = Color(0xFF2E2A27)
private val muted = Color(0xFF8C857D)
private val line = Color(0xFFEDE6DC)

private fun textColor() = ColorProvider(ink)
private fun mutedColor() = ColorProvider(muted)
private fun surfaceColor() = ColorProvider(cream)
private fun cardColor() = ColorProvider(card)
private fun lineColor() = ColorProvider(line)

@Composable
private fun WidgetContent(kind: WidgetKind, state: AppState, photo: Bitmap?, context: Context) {
    val p = state.persisted
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(surfaceColor())
            .cornerRadius(22.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(7.dp)
                        .cornerRadius(4.dp)
                        .background(ColorProvider(Color(accentColor(kind.type)))),
                ) {}
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    kind.label(),
                    style = TextStyle(
                        color = mutedColor(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    if (state.link.connected) "LIVE" else "OFFLINE",
                    style = TextStyle(
                        color = if (state.link.connected) ColorProvider(Color(0xFF5F8A6A)) else mutedColor(),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            Box(modifier = GlanceModifier.fillMaxSize()) {
                when (kind) {
                    WidgetKind.CLOCK -> ClockWidget(p)
                    WidgetKind.WEATHER -> WeatherWidget(p)
                    WidgetKind.BATTERY -> BatteryWidget(state, p)
                    WidgetKind.STOCKS -> StocksWidget(p)
                    WidgetKind.NOTIFICATIONS -> NotificationsWidget(p)
                    WidgetKind.MEDIA -> MediaWidget(p)
                    WidgetKind.CALENDAR -> CalendarWidget(p)
                    WidgetKind.STEPS -> StepsWidget(p)
                    WidgetKind.TEXT -> TextWidget(p)
                    WidgetKind.PHOTO -> PhotoWidget(photo)
                }
            }
        }
    }
}

@Composable
private fun ClockWidget(p: PersistedState) {
    val now = Date()
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            SimpleDateFormat(if (p.settings.twelveHour) "hh:mm" else "HH:mm", Locale.getDefault()).format(now),
            style = TextStyle(color = textColor(), fontSize = 40.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now),
            style = TextStyle(color = mutedColor(), fontSize = 11.sp),
        )
    }
}

@Composable
private fun WeatherWidget(p: PersistedState) {
    val w = p.weather
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Text(
            if (w.valid) "${w.tempC10 / 10}°" else "--°",
            style = TextStyle(color = textColor(), fontSize = 36.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            if (w.valid) weatherLabel(w.code) else "Waiting for weather",
            style = TextStyle(color = mutedColor(), fontSize = 11.sp),
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (w.valid) "${w.city.ifBlank { "Local" }}  H${w.hiC10 / 10} L${w.loC10 / 10}" else "Open the app",
            style = TextStyle(color = mutedColor(), fontSize = 9.sp),
        )
    }
}

@Composable
private fun BatteryWidget(state: AppState, p: PersistedState) {
    val battery = stateBatteryPercent(state)
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$battery%",
            style = TextStyle(color = textColor(), fontSize = 34.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            when {
                p.phoneBattery.charging -> "Phone charging"
                state.link.status?.battery != null -> "Widget ${state.link.status.battery}%"
                else -> "Phone battery"
            },
            style = TextStyle(color = mutedColor(), fontSize = 10.sp),
        )
        Spacer(GlanceModifier.height(8.dp))
        ProgressBar(battery, ColorProvider(Color(0xFFA8C6AE)))
    }
}

@Composable
private fun StocksWidget(p: PersistedState) {
    val stock = p.stocks.firstOrNull { it.valid }
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (stock == null) {
            Text("Add a watchlist", style = TextStyle(color = mutedColor(), fontSize = 12.sp))
            return@Column
        }
        Text(stock.symbol, style = TextStyle(color = mutedColor(), fontSize = 10.sp, fontWeight = FontWeight.Medium))
        Text(
            money(stock.priceCents, stock.currency),
            style = TextStyle(color = textColor(), fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            "${if (stock.changeBp >= 0) "+" else ""}${stock.changeBp / 100.0}%",
            style = TextStyle(
                color = if (stock.changeBp >= 0) ColorProvider(Color(0xFF5F8A6A))
                else ColorProvider(Color(0xFFB96A6A)),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun NotificationsWidget(p: PersistedState) {
    val n = p.notifications
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (!n.valid) {
            Text("All quiet", style = TextStyle(color = mutedColor(), fontSize = 13.sp))
            return@Column
        }
        Text(
            "${n.count}",
            style = TextStyle(color = textColor(), fontSize = 26.sp, fontWeight = FontWeight.Bold),
        )
        Text(n.app.ifBlank { "Notification" }, style = TextStyle(color = mutedColor(), fontSize = 10.sp))
        Spacer(GlanceModifier.height(3.dp))
        Text(n.title, style = TextStyle(color = textColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun MediaWidget(p: PersistedState) {
    val m = p.media
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (!m.valid) {
            Text("Nothing playing", style = TextStyle(color = mutedColor(), fontSize = 13.sp))
            return@Column
        }
        Text(
            if (m.playing) "▶  NOW PLAYING" else "❚❚  PAUSED",
            style = TextStyle(color = mutedColor(), fontSize = 8.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(5.dp))
        Text(m.title.ifBlank { "Unknown track" }, style = TextStyle(color = textColor(), fontSize = 13.sp, fontWeight = FontWeight.Bold))
        Text(m.artist, style = TextStyle(color = mutedColor(), fontSize = 10.sp))
        Spacer(GlanceModifier.defaultWeight())
        ProgressBar(m.progressPct, ColorProvider(Color(0xFFC6B8E6)))
    }
}

@Composable
private fun CalendarWidget(p: PersistedState) {
    val c = p.calendar
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (!c.valid) {
            Text("No upcoming event", style = TextStyle(color = mutedColor(), fontSize = 12.sp))
            return@Column
        }
        Text(
            SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(c.start * 1000)),
            style = TextStyle(color = mutedColor(), fontSize = 10.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(3.dp))
        Text(c.title.ifBlank { "Untitled event" }, style = TextStyle(color = textColor(), fontSize = 13.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (c.allDay) "All day" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.start * 1000)),
            style = TextStyle(color = mutedColor(), fontSize = 10.sp),
        )
    }
}

@Composable
private fun StepsWidget(p: PersistedState) {
    val steps = p.steps
    val goal = steps.goal.coerceAtLeast(1)
    val pct = (steps.steps * 100 / goal).toInt().coerceIn(0, 100)
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$pct%",
            style = TextStyle(color = textColor(), fontSize = 32.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            "${steps.steps} of $goal",
            style = TextStyle(color = mutedColor(), fontSize = 10.sp),
        )
        Spacer(GlanceModifier.height(8.dp))
        ProgressBar(pct, ColorProvider(Color(0xFFF4B8A4)))
    }
}

@Composable
private fun TextWidget(p: PersistedState) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Text(
            p.notes.firstOrNull { it.isNotBlank() } ?: "Add a note in the app",
            style = TextStyle(color = textColor(), fontSize = 13.sp),
            maxLines = 4,
        )
    }
}

@Composable
private fun PhotoWidget(photo: Bitmap?) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(12.dp)
            .background(cardColor()),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                provider = ImageProvider(photo),
                contentDescription = "Widget photo",
                modifier = GlanceModifier.fillMaxSize().cornerRadius(12.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("Send a photo", style = TextStyle(color = mutedColor(), fontSize = 12.sp, textAlign = TextAlign.Center))
        }
    }
}

@Composable
private fun ProgressBar(percent: Int, color: ColorProvider) {
    val filled = (percent / 10).coerceIn(0, 10)
    Row(modifier = GlanceModifier.fillMaxWidth().height(5.dp)) {
        repeat(10) { index ->
            Box(
                modifier = GlanceModifier
                    .height(5.dp)
                    .padding(end = 2.dp)
                    .cornerRadius(2.dp)
                    .background(if (index < filled) color else lineColor())
                    .defaultWeight(),
            ) {}
        }
    }
}

private fun WidgetKind.label(): String = when (this) {
    WidgetKind.CLOCK -> "CLOCK"
    WidgetKind.WEATHER -> "WEATHER"
    WidgetKind.BATTERY -> "BATTERY"
    WidgetKind.STOCKS -> "MARKETS"
    WidgetKind.NOTIFICATIONS -> "ALERTS"
    WidgetKind.MEDIA -> "NOW PLAYING"
    WidgetKind.CALENDAR -> "CALENDAR"
    WidgetKind.STEPS -> "STEPS"
    WidgetKind.TEXT -> "NOTE"
    WidgetKind.PHOTO -> "PHOTO"
}

private fun stateBatteryPercent(state: AppState): Int =
    state.link.status?.battery?.takeIf { it in 1..100 } ?: state.persisted.phoneBattery.percent

private fun money(cents: Int, currency: Int): String =
    if (currency == 0) "₹${"%.2f".format(cents / 100.0)}" else "$" + "%.2f".format(cents / 100.0)

private fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear sky"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Foggy"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Showers"
    85, 86 -> "Snow showers"
    in 95..99 -> "Thunderstorm"
    else -> "Weather"
}

class ClockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.CLOCK)
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.WEATHER)
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.BATTERY)
}

class StocksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.STOCKS)
}

class NotificationsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.NOTIFICATIONS)
}

class MediaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.MEDIA)
}

class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.CALENDAR)
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.STEPS)
}

class TextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.TEXT)
}

class PhotoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspWidgetGlance(WidgetKind.PHOTO)
}
