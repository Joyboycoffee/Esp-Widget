package dev.gourav.espwidget.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.MainActivity
import dev.gourav.espwidget.R
import dev.gourav.espwidget.data.AppState
import dev.gourav.espwidget.data.PersistedState
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One home-screen widget that shows everything at a glance and adapts to the
 * size the user gives it:
 *  - small  (2x2): clock, weather, ESP battery
 *  - wide   (4x2): + next event and first stock
 *  - large  (4x3+): + now playing, notifications, steps and the watchlist
 *
 * Previously there were ten separate widgets sharing one GlanceAppWidget
 * class; updateAll() then redrew every instance with whichever type refreshed
 * last, so most of them showed the wrong card.
 */
class EspDashboardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AppGraph.init(context)
        val state = AppGraph.repository.state.first()
        provideContent { Dashboard(state) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 250.dp)
    }
}

private val DeltaKey = ActionParameters.Key<Int>("delta")

/** Left/right arrow on the home-screen widget: flips the ESP's display. */
class StepPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AppGraph.init(context)
        val delta = parameters[DeltaKey] ?: return
        AppGraph.engine.stepWidgetPage(delta)
        dev.gourav.espwidget.WidgetUpdater.refresh(context)
    }
}

class EspDashboardReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EspDashboardWidget()
}

private val cream = Color(0xFFFDF8F3)
private val card = Color(0xFFFFFDFB)
private val ink = Color(0xFF2E2A27)
private val muted = Color(0xFF8C857D)
private val line = Color(0xFFEDE6DC)
private val good = Color(0xFF5F8A6A)
private val bad = Color(0xFFB96A6A)
private val sage = Color(0xFFA8C6AE)
private val peach = Color(0xFFF4B8A4)
private val lavender = Color(0xFFC6B8E6)
private val sky = Color(0xFFA9CBE8)

private fun c(color: Color) = ColorProvider(color)

@Composable
private fun Dashboard(state: AppState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val wide = size.width >= EspDashboardWidget.WIDE.width
    val tall = size.height >= EspDashboardWidget.LARGE.height
    val p = state.persisted
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(c(cream))
            .cornerRadius(24.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Header(state, context, wide)
            Spacer(GlanceModifier.height(10.dp))
            if (wide) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    WeatherTile(p, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    if (tall) StepsTile(p, GlanceModifier.defaultWeight()) else EventTile(p, GlanceModifier.defaultWeight())
                }
                if (tall) {
                    Spacer(GlanceModifier.height(8.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        EventTile(p, GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(8.dp))
                        MediaTile(p, GlanceModifier.defaultWeight())
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    NotificationTile(p, GlanceModifier.fillMaxWidth())
                    Spacer(GlanceModifier.height(8.dp))
                    Watchlist(p, rows = 2)
                    Spacer(GlanceModifier.height(8.dp))
                    PageBar(state)
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                    PageBar(state)
                }
            } else {
                WeatherLine(p)
                Spacer(GlanceModifier.defaultWeight())
                PageBar(state, 40)
            }
        }
    }
}

@Composable
private fun Header(state: AppState, context: Context, wide: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AndroidRemoteViews(
            RemoteViews(
                context.packageName,
                if (wide) R.layout.widget_clock_large else R.layout.widget_clock_small,
            ),
        )
        Spacer(GlanceModifier.defaultWeight())
        if (wide) StatusPill(state)
    }
}

@Composable
private fun BigArrow(label: String, delta: Int, modifier: GlanceModifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(c(sage.copy(alpha = 0.45f)))
            .cornerRadius(16.dp)
            .clickable(actionRunCallback<StepPageAction>(actionParametersOf(DeltaKey to delta))),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = c(ink), fontSize = 26.sp, fontWeight = FontWeight.Bold))
    }
}

/** Full-width bar with two big tap targets: [ ‹ ]  2/5  [ › ] changes what the ESP shows. */
@Composable
private fun PageBar(state: AppState, height: Int = 46) {
    val p = state.persisted
    val count = if (p.settings.stocksMode) {
        p.stocks.count { it.valid }.let { if (it >= 2) it + 1 else 1 }
    } else {
        p.layout.pages.size.coerceAtLeast(1)
    }
    val page = ((state.link.status?.page ?: p.settings.currentPage).coerceIn(0, count - 1)) + 1
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigArrow("‹", -1, GlanceModifier.defaultWeight())
        Text(
            "$page/$count",
            style = TextStyle(color = c(muted), fontSize = 12.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.padding(horizontal = 10.dp),
        )
        BigArrow("›", 1, GlanceModifier.defaultWeight())
    }
}

@Composable
private fun StatusPill(state: AppState) {
    val connected = state.link.connected
    val battery = state.link.status?.battery
    Row(
        modifier = GlanceModifier
            .background(c(if (connected) sage.copy(alpha = 0.35f) else line))
            .cornerRadius(12.dp)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(6.dp).cornerRadius(3.dp).background(c(if (connected) good else muted))) {}
        Spacer(GlanceModifier.width(5.dp))
        Text(
            when {
                connected && battery != null -> "ESP $battery%"
                connected -> "Linked"
                else -> "Offline"
            },
            style = TextStyle(color = c(ink), fontSize = 10.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun Tile(modifier: GlanceModifier, accent: Color, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .background(c(card))
            .cornerRadius(14.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(GlanceModifier.width(14.dp).height(3.dp).cornerRadius(2.dp).background(c(accent))) {}
        Spacer(GlanceModifier.height(5.dp))
        content()
    }
}

@Composable
private fun TileLabel(text: String) {
    Text(text, style = TextStyle(color = c(muted), fontSize = 10.sp), maxLines = 1)
}

@Composable
private fun TileValue(text: String, size: Int = 16) {
    Text(text, style = TextStyle(color = c(ink), fontSize = size.sp, fontWeight = FontWeight.Bold), maxLines = 1)
}

@Composable
private fun WeatherTile(p: PersistedState, modifier: GlanceModifier) {
    val w = p.weather
    Tile(modifier, sky) {
        TileValue(if (w.valid) "${w.tempC10 / 10}°  ${weatherLabel(w.code)}" else "--°")
        TileLabel(if (w.valid) "${w.city.ifBlank { "Local" }} · H ${w.hiC10 / 10}  L ${w.loC10 / 10}" else "Waiting for weather")
    }
}

@Composable
private fun EventTile(p: PersistedState, modifier: GlanceModifier) {
    val e = p.calendar
    Tile(modifier, lavender) {
        if (!e.valid) {
            TileValue("No events", 14)
            TileLabel("Calendar is clear")
        } else {
            TileValue(e.title.ifBlank { "Untitled event" }, 14)
            TileLabel(
                if (e.allDay) {
                    SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(e.start * 1000)) + " · All day"
                } else {
                    SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(e.start * 1000))
                },
            )
        }
    }
}

@Composable
private fun MediaTile(p: PersistedState, modifier: GlanceModifier) {
    val m = p.media
    Tile(modifier, peach) {
        if (!m.valid) {
            TileValue("Nothing playing", 14)
            TileLabel("Music shows here")
        } else {
            TileValue((if (m.playing) "▶ " else "❚❚ ") + m.title.ifBlank { "Unknown track" }, 14)
            TileLabel(m.artist.ifBlank { "Unknown artist" })
        }
    }
}

@Composable
private fun StepsTile(p: PersistedState, modifier: GlanceModifier) {
    val s = p.steps
    val goal = s.goal.coerceAtLeast(1)
    val pct = (s.steps * 100 / goal).toInt().coerceIn(0, 100)
    Tile(modifier, sage) {
        TileValue(if (s.valid) "%,d steps".format(s.steps) else "-- steps")
        Spacer(GlanceModifier.height(4.dp))
        ProgressBar(if (s.valid) pct else 0, c(sage))
    }
}

@Composable
private fun NotificationTile(p: PersistedState, modifier: GlanceModifier) {
    val n = p.notifications
    Tile(modifier, peach) {
        if (!n.valid) {
            TileValue("All quiet", 14)
        } else {
            TileValue("${n.app.ifBlank { "Notification" }} · ${n.count}", 14)
            TileLabel(n.title.ifBlank { n.body })
        }
    }
}

@Composable
private fun Watchlist(p: PersistedState, rows: Int) {
    val stocks = p.stocks.filter { it.valid }.take(rows)
    if (stocks.isEmpty()) return
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(c(card))
            .cornerRadius(14.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        stocks.forEach { stock ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stock.symbol,
                    style = TextStyle(color = c(ink), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(money(stock.priceCents, stock.currency), style = TextStyle(color = c(muted), fontSize = 11.sp))
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    "%+.1f%%".format(stock.changeBp / 100.0),
                    style = TextStyle(
                        color = c(if (stock.changeBp >= 0) good else bad),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WeatherLine(p: PersistedState) {
    val w = p.weather
    Text(
        if (w.valid) "${w.tempC10 / 10}°  ${weatherLabel(w.code)}" else "--°",
        style = TextStyle(color = c(ink), fontSize = 14.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
    Text(
        if (w.valid) w.city.ifBlank { "Local" } else "Waiting for weather",
        style = TextStyle(color = c(muted), fontSize = 10.sp),
        maxLines = 1,
    )
}

@Composable
private fun DeviceLine(state: AppState) {
    val battery = state.link.status?.battery
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.size(6.dp).cornerRadius(3.dp)
                .background(c(if (state.link.connected) good else muted)),
        ) {}
        Spacer(GlanceModifier.width(5.dp))
        Text(
            when {
                state.link.connected && battery != null -> "ESP $battery%"
                state.link.connected -> "ESP linked"
                else -> "ESP offline"
            },
            style = TextStyle(color = c(muted), fontSize = 10.sp),
        )
    }
}

@Composable
private fun ProgressBar(percent: Int, color: ColorProvider) {
    val filled = (percent / 10).coerceIn(0, 10)
    Row(modifier = GlanceModifier.fillMaxWidth().height(4.dp)) {
        repeat(10) { index ->
            Box(
                modifier = GlanceModifier
                    .height(4.dp)
                    .defaultWeight()
                    .padding(end = 2.dp),
            ) {
                Box(
                    GlanceModifier.fillMaxSize().cornerRadius(2.dp)
                        .background(if (index < filled) color else c(line)),
                ) {}
            }
        }
    }
}

private fun money(cents: Int, currency: Int): String {
    val value = cents / 100.0
    val text = if (value >= 10_000) "%,.0f".format(value) else "%,.2f".format(value)
    return when (currency) {
        0 -> "₹$text"
        1 -> "$$text"
        else -> text
    }
}

private fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Foggy"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Showers"
    85, 86 -> "Snow showers"
    in 95..99 -> "Storm"
    else -> "Weather"
}
