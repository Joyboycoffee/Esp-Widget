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
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.IconButton
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
import dev.gourav.espwidget.data.StockModel
import dev.gourav.espwidget.data.accentColor
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
    showStocksMode: Boolean = true,
) {
    val pages = state.persisted.layout.pages
    val stocksMode = showStocksMode && state.persisted.settings.stocksMode
    val validStocks = state.persisted.stocks.filter { it.valid }
    // Mirrors the widget: in full-screen stocks mode there is one overview page
    // (when 2+ stocks) followed by one page per stock.
    val count = if (stocksMode) (if (validStocks.size >= 2) validStocks.size + 1 else 1) else pages.size
    var selected by rememberSaveable { mutableIntStateOf(state.persisted.settings.currentPage) }
    val page = (controlledPage ?: selected).coerceIn(0, (count - 1).coerceAtLeast(0))
    LaunchedEffect(count) {
        if (selected >= count) {
            selected = 0
            onPageChange(0)
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(34.dp),
            color = Color(0xFFEAE2D8),
            shadowElevation = 12.dp,
            modifier = Modifier.padding(6.dp)
                        .pointerInput(count, page) {
                            // Swipe the preview to flip the page here and on the widget.
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    if (kotlin.math.abs(total) > 40f && count > 1) {
                                        val next = if (total < 0) page + 1 else page - 1
                                        val wrapped = (next % count + count) % count
                                        selected = wrapped
                                        onPageChange(wrapped)
                                    }
                                },
                                onHorizontalDrag = { _, delta -> total += delta },
                            )
                        },
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
                        PreviewHeader(state, page, count)
                        Spacer(Modifier.height(4.dp))
                        val config = pages.getOrNull(page) ?: PageModel()
                        if (stocksMode) {
                            StocksPreview(validStocks, page)
                        } else if (config.template == 2) {
                            MiniSlot(state, config.slots.getOrNull(0) ?: SlotModel(), true, Modifier.fillMaxWidth().height(190.dp))
                        } else if (config.template == 3) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MiniSlot(state, config.slots.getOrNull(0) ?: SlotModel(), false, Modifier.weight(1f).height(190.dp))
                                MiniSlot(state, config.slots.getOrNull(1) ?: SlotModel(), false, Modifier.weight(1f).height(190.dp))
                            }
                        } else if (config.template == 4) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MiniSlot(state, config.slots.getOrNull(0) ?: SlotModel(), true, Modifier.fillMaxWidth().height(92.dp))
                                MiniSlot(state, config.slots.getOrNull(1) ?: SlotModel(), true, Modifier.fillMaxWidth().height(92.dp))
                            }
                        } else if (config.template == 1) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val to = ((page - 1) % count + count) % count
                        selected = to
                        onPageChange(to)
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.ChevronLeft, "Previous page", tint = Muted)
                    }
                    (0 until count).forEach { index ->
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
                    IconButton(onClick = {
                        val to = (page + 1) % count
                        selected = to
                        onPageChange(to)
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.ChevronRight, "Next page", tint = Muted)
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
    val today = java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()).format(java.util.Date())
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
                today,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
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
            if (option >= 6) {
                val list = p.stocks.filter { it.valid }.take(if (hero) 4 else 3)
                if (list.isEmpty()) {
                    MiniEmpty("Add a watchlist", hero)
                    return
                }
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.forEach { stock ->
                        val trend = if (stock.changeBp >= 0) Good else Bad
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stock.symbol, fontSize = if (hero) 9.sp else 7.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f), maxLines = 1)
                            if (hero) Text(money(stock.priceCents, stock.currency), fontSize = 7.sp, color = Muted)
                            Spacer(Modifier.width(6.dp))
                            Text("${"%+.1f".format(stock.changeBp / 100.0)}%", fontSize = 7.sp, color = trend)
                        }
                    }
                }
                return
            }
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
        10 -> {
            val pb = p.phoneBattery
            val known = pb.updated != 0L
            val color = if (pb.charging) Good else if (known && pb.percent <= 20) Bad else Color(accentColor(accent))
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    if (known) "${pb.percent}%" else "—",
                    fontSize = if (hero) 26.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(if (pb.charging) "phone charging" else "phone", fontSize = 7.sp, color = Muted)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
                    if (known) Box(Modifier.fillMaxWidth((pb.percent / 100f).coerceIn(0f, 1f)).height(4.dp).background(color))
                }
            }
        }
        in 11..20 -> ExtraPreview(type, option, accent, hero, state)
        else -> MiniEmpty("Empty", hero)
    }
}

/** A rough phone-side picture of the widgets the ESP computes itself. */
@Composable
private fun ExtraPreview(type: Int, option: Int, accent: Int, hero: Boolean, state: AppState) {
    val p = state.persisted
    val color = Color(accentColor(accent))
    var now by remember { mutableStateOf(java.time.ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.time.ZonedDateTime.now()
            delay(1000)
        }
    }
    val hhmm = java.time.format.DateTimeFormatter.ofPattern(if (p.settings.twelveHour) "h:mm a" else "HH:mm")
    when (type) {
        11 -> Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 4f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color.copy(alpha = 0.18f), r, c)
            drawCircle(Line, r, c, style = Stroke(1.5f))
            val h = (now.hour % 12 + now.minute / 60f) * (Math.PI / 6).toFloat()
            val m = now.minute * (Math.PI / 30).toFloat()
            fun hand(a: Float, len: Float, w: Float) = drawLine(
                Ink, c, Offset(c.x + kotlin.math.sin(a) * len, c.y - kotlin.math.cos(a) * len), w, StrokeCap.Round,
            )
            hand(h, r * 0.5f, 5f)
            hand(m, r * 0.78f, 3f)
            drawCircle(color, 4f, c)
        }
        12 -> {
            val city = dev.gourav.espwidget.data.WORLD_CITIES.getOrElse(option) { dev.gourav.espwidget.data.WORLD_CITIES[0] }
            val there = now.withZoneSameInstant(java.time.ZoneId.of(city.second))
            MiniInfo(city.first, there.format(hhmm), there.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), hero)
        }
        13 -> {
            val kind = option % 4
            val today = now.toLocalDate()
            val (start, end, title) = when (kind) {
                0 -> Triple(today.atStartOfDay(now.zone), today.plusDays(1).atStartOfDay(now.zone), "Today")
                1 -> today.minusDays((today.dayOfWeek.value - 1).toLong()).let {
                    Triple(it.atStartOfDay(now.zone), it.plusDays(7).atStartOfDay(now.zone), "This week")
                }
                2 -> today.withDayOfMonth(1).let {
                    Triple(it.atStartOfDay(now.zone), it.plusMonths(1).atStartOfDay(now.zone),
                        today.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()))
                }
                else -> today.withDayOfYear(1).let {
                    Triple(it.atStartOfDay(now.zone), it.plusYears(1).atStartOfDay(now.zone), today.year.toString())
                }
            }
            val total = (end.toEpochSecond() - start.toEpochSecond()).toFloat()
            val frac = ((now.toEpochSecond() - start.toEpochSecond()) / total).coerceIn(0f, 1f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(title, fontSize = 7.sp, color = Muted)
                Text("${(frac * 100).toInt()}%", fontSize = if (hero) 24.sp else 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).background(color))
                }
            }
        }
        14 -> if (option == 1) SkyArcPreview(false, color, now, hero) else MiniInfo("Sunrise & sunset", "☀", "from your location", hero)
        15 -> if (option == 1) SkyArcPreview(true, color, now, hero) else {
            val age = ((System.currentTimeMillis() / 1000.0 - 947182440.0) / 86400.0).mod(29.530588853)
            val phase = age / 29.530588853
            val lit = ((1 - kotlin.math.cos(2 * Math.PI * phase)) * 50).toInt()
            val name = when {
                age < 1.84 -> "New moon"; age < 5.53 -> "Waxing crescent"; age < 9.22 -> "First quarter"
                age < 12.91 -> "Waxing gibbous"; age < 16.61 -> "Full moon"; age < 20.30 -> "Waning gibbous"
                age < 23.99 -> "Last quarter"; age < 27.68 -> "Waning crescent"; else -> "New moon"
            }
            MiniInfo("Moon", "$lit%", name, hero)
        }
        16 -> MiniInfo(
            now.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()),
            now.dayOfMonth.toString(),
            now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()),
            hero,
        )
        17 -> {
            val a = p.nextAlarm
            if (a.epoch == 0L) MiniEmpty("No alarm set", hero)
            else {
                val t = java.time.Instant.ofEpochSecond(a.epoch).atZone(now.zone)
                MiniInfo(a.label.ifBlank { "Next alarm" }, t.format(hhmm), t.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), hero)
            }
        }
        18 -> {
            val c = p.countdown
            if (c.target == 0L) MiniEmpty("Set a countdown", hero)
            else {
                val days = java.time.temporal.ChronoUnit.DAYS.between(
                    now.toLocalDate(), java.time.Instant.ofEpochSecond(c.target).atZone(now.zone).toLocalDate(),
                )
                MiniInfo(
                    c.label.ifBlank { "Countdown" },
                    if (days == 0L) "Today" else kotlin.math.abs(days).toString(),
                    if (days > 0) "days to go" else if (days < 0) "days ago" else "it's the day!",
                    hero,
                )
            }
        }
        19 -> MiniInfo(
            "This widget",
            state.link.status?.let { "${it.battery}%" } ?: "—",
            if (state.link.connected) "Bluetooth linked" else "offline",
            hero,
        )
        else -> MiniInfo("Thought of the day", "\u201C", "A new one every day", hero)
    }
}

@Composable
private fun MiniInfo(title: String, big: String, sub: String, hero: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 7.sp, color = Muted, maxLines = 1)
        Text(big, fontSize = if (hero) 24.sp else 15.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
        Text(sub, fontSize = 7.sp, color = Muted, maxLines = 1)
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


/** Full-screen stocks mode: overview rows (2+ stocks) or one big stock per page. */
@Composable
private fun StocksPreview(stocks: List<StockModel>, page: Int) {
    val shape = RoundedCornerShape(14.dp)
    if (stocks.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(190.dp).clip(shape).background(CardWhite).border(1.dp, Line, shape), contentAlignment = Alignment.Center) {
            Text("No stocks yet", fontSize = 11.sp, color = Muted)
        }
        return
    }
    if (stocks.size >= 2 && page == 0) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            stocks.forEach { stock ->
                val trend = if (stock.changeBp >= 0) Good else Bad
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(if (stocks.size > 4) 28.dp else 36.dp)
                        .clip(RoundedCornerShape(10.dp)).background(CardWhite).border(1.dp, Line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp),
                ) {
                    Text(stock.symbol, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f), maxLines = 1)
                    Sparkline(stock.spark, trend, Modifier.width(34.dp).height(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(stock.priceCents, stock.currency), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("${"%+.2f".format(stock.changeBp / 100.0)}%", fontSize = 7.sp, color = trend)
                    }
                }
            }
        }
        return
    }
    val stock = stocks[(if (stocks.size >= 2) page - 1 else 0).coerceIn(0, stocks.lastIndex)]
    val trend = if (stock.changeBp >= 0) Good else Bad
    Column(
        modifier = Modifier.fillMaxWidth().height(190.dp).clip(shape).background(CardWhite)
            .border(1.dp, Line, shape).padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(stock.symbol, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            Text(if (stock.market == 1) "Market open" else "Market closed", fontSize = 8.sp, color = Muted)
        }
        Spacer(Modifier.height(4.dp))
        Text(money(stock.priceCents, stock.currency), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "${"%+.2f".format(stock.changeBp / 100.0)}%",
            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CardWhite,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(trend).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        Spacer(Modifier.height(8.dp))
        Sparkline(stock.spark, trend, Modifier.fillMaxWidth().height(50.dp))
    }
}


/** Phone-side picture of the arc style: a curve over a horizon bar with the body where it is now (rough times). */
@Composable
private fun SkyArcPreview(isMoon: Boolean, color: Color, now: java.time.ZonedDateTime, hero: Boolean) {
    val nowMin = now.hour * 60f + now.minute
    val rise = 6.5f * 60f
    val dur = 12f * 60f
    val u = if (isMoon) {
        val age = ((System.currentTimeMillis() / 1000.0 - 947182440.0) / 86400.0).mod(29.530588853)
        ((nowMin - (rise + age * 48.8)).mod(1440.0)).toFloat()
    } else nowMin - rise
    val up = u in 0f..dur
    val t = (u / dur).coerceIn(0f, 1f)
    val line = Color(0xFFD9D2C8)
    Canvas(Modifier.fillMaxSize()) {
        val pad = 14f
        val barY = size.height - 22f
        val cx = size.width / 2f
        val rx = size.width / 2f - pad
        val ry = (barY - 18f).coerceAtMost(rx * 0.8f)
        val steps = 40
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val a = Math.PI.toFloat() * f
            val p = Offset(cx - kotlin.math.cos(a) * rx, barY - kotlin.math.sin(a) * ry)
            if (up && f <= t) drawCircle(color, 3f, p) else if (i % 2 == 0) drawCircle(line, 2f, p)
        }
        drawRoundRect(line, Offset(pad - 4f, barY), androidx.compose.ui.geometry.Size(size.width - 2 * pad + 8f, 6f), androidx.compose.ui.geometry.CornerRadius(3f))
        val br = if (hero) 12f else 9f
        val a = Math.PI.toFloat() * t
        val body = if (up) Offset(cx - kotlin.math.cos(a) * rx, barY - kotlin.math.sin(a) * ry)
                   else Offset(if (u < 0f) cx - rx else cx + rx, barY - br / 2)
        val bodyColor = if (isMoon) Color(0xFFF6E6B8) else Color(0xFFE6B25C)
        if (up && !isMoon) drawCircle(bodyColor.copy(alpha = 0.3f), br + 6f, body)
        drawCircle(if (up) bodyColor else Color(0xFFB8B2BC), br, body)
    }
}
