package dev.gourav.espwidget.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gourav.espwidget.data.AppState
import dev.gourav.espwidget.data.PageModel
import dev.gourav.espwidget.data.SlotModel
import dev.gourav.espwidget.data.accentColor
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DevicePreview(
    state: AppState,
    modifier: Modifier = Modifier,
    controlledPage: Int? = null,
    onPageChange: (Int) -> Unit = {},
) {
    val pages = state.persisted.layout.pages
    var selected by rememberSaveable { mutableIntStateOf(state.persisted.settings.currentPage) }
    val page = (controlledPage ?: selected).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    LaunchedEffect(pages.size) {
        if (selected >= pages.size) {
            selected = 0
            onPageChange(0)
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(34.dp),
            color = Color(0xFFEAE2D8),
            shadowElevation = 12.dp,
            modifier = Modifier.padding(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .background(Paper)
                    .padding(horizontal = 14.dp, vertical = 18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Paper)
                        .border(1.dp, Line, RoundedCornerShape(20.dp))
                        .padding(9.dp),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PreviewHeader(state, page, pages.size)
                        Spacer(Modifier.height(4.dp))
                        val config = pages.getOrNull(page) ?: PageModel()
                        if (config.template == 1) {
                            val w = (222.dp - 6.dp) / 2
                            val h = (222.dp - 22.dp - 6.dp) / 2
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MiniSlot(state, config.slots.getOrNull(0) ?: SlotModel(), false, Modifier.width(w).height(h))
                                    MiniSlot(state, config.slots.getOrNull(1) ?: SlotModel(), false, Modifier.width(w).height(h))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MiniSlot(state, config.slots.getOrNull(2) ?: SlotModel(), false, Modifier.width(w).height(h))
                                    MiniSlot(state, config.slots.getOrNull(3) ?: SlotModel(), false, Modifier.width(w).height(h))
                                }
                            }
                        } else {
                            MiniSlot(
                                state,
                                config.slots.getOrNull(0) ?: SlotModel(),
                                true,
                                Modifier.fillMaxWidth().height(118.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MiniSlot(state, config.slots.getOrNull(1) ?: SlotModel(), false, Modifier.weight(1f).height(66.dp))
                                MiniSlot(state, config.slots.getOrNull(2) ?: SlotModel(), false, Modifier.weight(1f).height(66.dp))
                            }
                        }
                    }
                    if (state.link.scanning || state.link.connecting) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(Ink, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (state.link.scanning) "Looking for widget…" else "Connecting…",
                                color = CardWhite,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    pages.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .size(if (index == page) 18.dp else 8.dp, 8.dp)
                                .clip(CircleShape)
                                .background(if (index == page) Ink else Line)
                                .clickable {
                                    selected = index
                                    onPageChange(index)
                                },
                        )
                    }
                }
            }
        }
        Text(
            "240 × 240 · ST7789 · live layout",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PreviewHeader(state: AppState, page: Int, pages: Int) {
    val names = listOf("TODAY", "MARKETS", "FOCUS", "FRAME", "CUSTOM")
    val status = state.link.status
    val battery = status?.battery ?: 0
    val levelColor = when {
        status?.charging == true || status?.chargeFull == true -> Good
        battery > 50 -> Good
        battery > 20 -> Butter
        else -> Bad
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)).background(Line)) {
            Box(
                Modifier
                    .fillMaxWidth((battery / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(levelColor),
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                names.getOrElse(page) { "CUSTOM" },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Spacer(Modifier.width(6.dp))
            Text("${page + 1}/$pages", fontSize = 8.sp, color = Muted, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Bluetooth, null, tint = if (state.link.connected) Ink else Line, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Rounded.Wifi, null, tint = if (status?.wifiState == 2) Ink else Line, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .width(24.dp)
                    .height(10.dp)
                    .border(1.dp, Line, RoundedCornerShape(3.dp))
                    .padding(1.5.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((battery / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(levelColor, RoundedCornerShape(1.dp)),
                )
            }
            Spacer(Modifier.width(3.dp))
            Text(
                if (status == null) "—" else "$battery%",
                fontSize = 8.sp,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MiniSlot(state: AppState, slot: SlotModel, hero: Boolean, modifier: Modifier) {
    val accent = Color(accentColor(slot.accent))
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardWhite)
            .border(1.dp, Line, RoundedCornerShape(10.dp)),
    ) {
        if (slot.isEmpty) {
            Text(
                "Empty",
                fontSize = 8.sp,
                color = Muted,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        Box(
            Modifier
                .padding(start = 7.dp, top = 7.dp)
                .width(3.dp)
                .height(if (hero) 28.dp else 17.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Box(modifier = Modifier.fillMaxSize().padding(start = 14.dp, top = 6.dp, end = 7.dp, bottom = 5.dp)) {
            MiniWidgetContent(slot.type, slot.option, slot.accent, hero, state)
        }
    }
}

@Composable
private fun MiniWidgetContent(type: Int, option: Int, accent: Int, hero: Boolean, state: AppState) {
    val p = state.persisted
    when (type) {
        0 -> {
            var now by remember { mutableStateOf(LocalDateTime.now()) }
            LaunchedEffect(Unit) {
                while (true) {
                    now = LocalDateTime.now()
                    delay(1000)
                }
            }
            val pattern = if (p.settings.twelveHour) "hh:mm" else "HH:mm"
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    now.format(DateTimeFormatter.ofPattern(pattern)),
                    fontSize = if (hero) 29.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
                    fontSize = 7.sp,
                    color = Muted,
                )
            }
        }
        1 -> {
            val w = p.weather
            if (!w.valid) {
                MiniEmpty("No weather yet", hero)
                return
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        weatherIcon(w.code),
                        null,
                        tint = Color(accentColor(accent)),
                        modifier = Modifier.size(if (hero) 30.dp else 17.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Column {
                        Text("${w.tempC10 / 10}°", fontSize = if (hero) 26.sp else 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(
                            w.city.ifBlank { "Local" },
                            fontSize = 7.sp,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hero) Text("H ${w.hiC10 / 10}  L ${w.loC10 / 10}", fontSize = 7.sp, color = Muted)
                    }
                }
                if (w.trend.size >= 2) {
                    Sparkline(
                        values = w.trend,
                        color = Color(accentColor(accent)),
                        modifier = Modifier.fillMaxWidth().height(if (hero) 30.dp else 15.dp),
                    )
                }
            }
        }
        2 -> {
            val status = state.link.status
            val detail = when {
                status == null -> "offline"
                status.chargeFull -> "full"
                status.charging -> "charging"
                status.usb -> "docked"
                else -> "on battery"
            }
            val color = if (status?.charging == true || status?.chargeFull == true) {
                Good
            } else if (status != null && status.battery <= 20) {
                Bad
            } else {
                Color(accentColor(accent))
            }
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    status?.let { "${it.battery}%" } ?: "—",
                    fontSize = if (hero) 26.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(detail, fontSize = 7.sp, color = Muted)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
                    if (status != null) {
                        Box(Modifier.fillMaxWidth((status.battery / 100f).coerceIn(0f, 1f)).height(4.dp).background(color))
                    }
                }
                if (hero) {
                    Spacer(Modifier.height(3.dp))
                    Text(if (status != null) "${status.millivolts} mV" else "no telemetry", fontSize = 7.sp, color = Muted)
                }
            }
        }
        3 -> {
            val stock = p.stocks.getOrNull(option % 6)
            if (stock == null || !stock.valid) {
                MiniEmpty("Add a watchlist", hero)
                return
            }
            val trend = if (stock.changeBp >= 0) Good else Bad
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stock.symbol, fontSize = 7.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                        Text(
                            money(stock.priceCents, stock.currency),
                            fontSize = if (hero) 19.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                    }
                    Text(
                        "${if (stock.changeBp >= 0) "▲" else "▼"} ${"%.2f".format(stock.changeBp / 100.0)}%",
                        fontSize = 7.sp,
                        color = trend,
                    )
                }
                if (stock.spark.size >= 2) {
                    Sparkline(
                        values = stock.spark,
                        color = trend,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp),
                    )
                }
            }
        }
        4 -> {
            val n = p.notifications
            if (!n.valid) {
                MiniEmpty("All quiet", hero)
                return
            }
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text("${n.count}", fontSize = if (hero) 22.sp else 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(n.app, fontSize = 7.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hero) Text(n.title, fontSize = 8.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        5 -> {
            val m = p.media
            if (!m.valid) {
                MiniEmpty("Nothing playing", hero)
                return
            }
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    (if (m.playing) "▶  " else "❚❚  ") + m.title,
                    fontSize = if (hero) 10.sp else 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(m.artist, fontSize = 7.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
                    Box(Modifier.fillMaxWidth(m.progressPct / 100f).height(3.dp).background(Color(accentColor(3))))
                }
            }
        }
        6 -> {
            val c = p.calendar
            if (!c.valid) {
                MiniEmpty("No event", hero)
                return
            }
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    java.text.SimpleDateFormat("EEE d", Locale.getDefault()).format(java.util.Date(c.start * 1000)),
                    fontSize = 7.sp,
                    color = Muted,
                )
                Text(
                    c.title,
                    fontSize = if (hero) 10.sp else 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hero) Text(c.location, fontSize = 7.sp, color = Muted, maxLines = 1)
            }
        }
        7 -> {
            val pct = (p.steps.steps * 100f / p.steps.goal.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (hero) 40.dp else 24.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 4.dp.toPx()
                        drawArc(
                            color = Line,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(stroke),
                        )
                        drawArc(
                            color = Color(accentColor(1)),
                            startAngle = -90f,
                            sweepAngle = pct * 3.6f,
                            useCenter = false,
                            style = Stroke(stroke),
                        )
                    }
                    Text("$pct", fontSize = 7.sp, color = Ink, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(5.dp))
                Column {
                    Text("${p.steps.steps}", fontSize = if (hero) 15.sp else 12.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("of ${p.steps.goal}", fontSize = 7.sp, color = Muted)
                }
            }
        }
        8 -> {
            val note = p.notes.getOrNull(option % 4).orEmpty()
            Text(
                note.ifBlank { "Add a note in the app" },
                fontSize = if (hero) 10.sp else 8.sp,
                color = Ink,
                maxLines = if (hero) 4 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxSize().padding(top = 2.dp),
            )
        }
        9 -> {
            val bitmap = remember(p.photoPath) {
                p.photoPath.takeIf { it.isNotBlank() }?.let {
                    runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp)),
                )
            } else {
                MiniEmpty("Send a photo", hero)
            }
        }
        else -> MiniEmpty("Empty", hero)
    }
}

@Composable
fun Sparkline(values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        if (max - min < 6) {
            drawLine(color.copy(alpha = 0.5f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            return@Canvas
        }
        val pad = 1.5.dp.toPx()
        val span = (max - min).toFloat()
        fun x(index: Int) = index.toFloat() / (values.size - 1) * size.width
        fun y(value: Int) = size.height - pad - (value - min) / span * (size.height - pad * 2)
        val line = Path()
        values.forEachIndexed { index, value ->
            if (index == 0) line.moveTo(x(index), y(value)) else line.lineTo(x(index), y(value))
        }
        val area = Path()
        area.moveTo(0f, size.height)
        area.lineTo(0f, y(values.first()))
        values.forEachIndexed { index, value -> area.lineTo(x(index), y(value)) }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, color = color.copy(alpha = 0.14f))
        drawPath(line, color = color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 1.8.dp.toPx(), center = Offset(x(values.lastIndex), y(values.last())))
    }
}

@Composable
private fun MiniEmpty(text: String, hero: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = if (hero) 9.sp else 7.sp, color = Muted, textAlign = TextAlign.Center)
    }
}

private fun weatherIcon(code: Int) = when (code) {
    0, 1 -> Icons.Rounded.WbSunny
    2, 3, 45, 48 -> Icons.Rounded.Cloud
    in 51..67, in 80..82 -> Icons.Rounded.Grain
    in 95..99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

private fun money(cents: Int, currency: Int): String =
    if (currency == 0) "₹${"%.2f".format(cents / 100.0)}" else "$" + "%.2f".format(cents / 100.0)
