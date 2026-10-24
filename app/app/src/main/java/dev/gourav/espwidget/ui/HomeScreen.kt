package dev.gourav.espwidget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.AppState
import java.time.LocalTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    state: AppState,
    onOpenSync: () -> Unit,
    onOpenLayout: () -> Unit,
    onGrantBluetooth: () -> Unit,
) {
    val previewState by AppGraph.repository.previewState.collectAsStateWithLifecycle()
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 5 -> "Still up?"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(greeting, style = MaterialTheme.typography.labelLarge, color = Muted)
                    Text("Your little display", style = MaterialTheme.typography.headlineLarge)
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Peach, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✳", fontSize = 20.sp, color = Ink)
                }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(if (state.link.connected) Good else Muted, CircleShape),
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                when {
                                    state.link.connected -> "Connected"
                                    state.link.connecting -> "Connecting"
                                    state.link.scanning -> "Searching nearby"
                                    else -> "Not connected"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            state.link.deviceName ?: state.persisted.deviceName.ifBlank { "ESP-WIDGET" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    }
                    if (state.link.connected) {
                        StatusPill("LIVE", true)
                    } else {
                        OutlinedButton(
                            onClick = {
                                if (AppGraph.initialized && AppGraph.ble.hasPermissions()) {
                                    AppGraph.ble.startScan()
                                } else {
                                    onGrantBluetooth()
                                }
                            },
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                        ) {
                            Icon(Icons.Rounded.BluetoothSearching, null, tint = Ink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(5.dp))
                            Text("Find", color = Ink)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            AppGraph.engine.sendSnapshot(state.persisted)
                            AppGraph.repository.log("Manual sync requested")
                        },
                        enabled = state.link.connected,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite, disabledContainerColor = Line),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Send now")
                    }
                    OutlinedButton(
                        onClick = onOpenSync,
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                    ) {
                        Text("Sync", color = Ink)
                    }
                    OutlinedButton(
                        onClick = onOpenLayout,
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                    ) {
                        Icon(Icons.Rounded.Tune, null, tint = Ink, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                DevicePreview(
                    previewState,
                    modifier = Modifier.fillMaxWidth(),
                    onPageChange = { page ->
                        if (AppGraph.initialized) AppGraph.engine.setWidgetPage(page)
                    },
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat(
                        label = "PHONE",
                        value = "${state.persisted.phoneBattery.percent}%",
                        hint = if (state.persisted.phoneBattery.charging) "charging" else "battery",
                        accent = Sage,
                        modifier = Modifier.weight(1f),
                    )
                    MiniStat(
                        label = "WIDGET",
                        value = state.link.status?.battery?.let { "$it%" } ?: "—",
                        hint = state.link.status?.let { status ->
                            val mode = when {
                                status.chargeFull -> "full"
                                status.charging -> "charging"
                                status.usb -> "docked"
                                else -> "battery"
                            }
                            "$mode · %d mV".format(status.millivolts)
                        } ?: "offline",
                        accent = Sky,
                        modifier = Modifier.weight(1f),
                    )
                    MiniStat(
                        label = "LAST SYNC",
                        value = if (state.persisted.lastSync == 0L) "—" else
                            SimpleDateFormat("HH:mm", Locale.US).format(Date(state.persisted.lastSync * 1000)),
                        hint = "auto over BLE",
                        accent = Lavender,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item { WidgetPowerCard(state) }
        item {
            SectionCard(title = "Today", subtitle = "Everything the widget is showing right now") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("Weather", state.persisted.weather.takeIf { it.valid }?.let { "${it.tempC10 / 10}°" } ?: "—", Modifier.weight(1f))
                    StatTile("Steps", state.persisted.steps.steps.toString(), Modifier.weight(1f))
                    StatTile("Alerts", state.persisted.notifications.count.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("Markets", state.persisted.stocks.count { it.valid }.toString(), Modifier.weight(1f))
                    StatTile("Events", if (state.persisted.calendar.valid) "1" else "0", Modifier.weight(1f))
                    StatTile("Pages", state.persisted.layout.pages.size.toString(), Modifier.weight(1f))
                }
            }
        }
        val liveStocks = state.persisted.stocks.filter { it.valid }.take(3)
        if (liveStocks.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Live charts",
                    subtitle = "Fetched on your phone and streamed to the widget",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        liveStocks.forEach { stock ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.width(92.dp)) {
                                    Text(stock.symbol, fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold)
                                    Text(
                                        money(stock.priceCents, stock.currency),
                                        fontSize = 17.sp,
                                        color = Ink,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "${if (stock.changeBp >= 0) "▲" else "▼"} ${"%.2f".format(stock.changeBp / 100.0)}%",
                                        fontSize = 10.sp,
                                        color = if (stock.changeBp >= 0) Good else Bad,
                                    )
                                }
                                Sparkline(
                                    values = stock.spark,
                                    color = if (stock.changeBp >= 0) Good else Bad,
                                    modifier = Modifier.weight(1f).height(46.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.link.log.isNotEmpty()) {
            item {
                SectionCard(title = "Recent activity") {
                    state.link.log.take(4).forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = if (index == 0) Ink else Muted)
                    }
                }
            }
        }
        if (state.persisted.lastError.isNotBlank()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Bad.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    Text(state.persisted.lastError, color = Bad, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, hint: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(CardWhite, RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Box(Modifier.size(22.dp, 4.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Muted)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(hint, fontSize = 9.sp, color = Muted, maxLines = 1)
    }
}

private fun money(cents: Int, currency: Int): String =
    if (currency == 0) "₹${"%.2f".format(cents / 100.0)}" else "$" + "%.2f".format(cents / 100.0)

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Paper, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(label, fontSize = 9.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 18.sp, color = Ink, fontWeight = FontWeight.Bold)
    }
}
