package dev.gourav.espwidget.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.AppState
import dev.gourav.espwidget.data.SourceConfig
import dev.gourav.espwidget.widget.EspDashboardReceiver

@Composable
fun SettingsScreen(state: AppState, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val settings = state.persisted.settings
    val sources = state.persisted.sources
    var brightness by remember(settings.brightness) { mutableFloatStateOf(settings.brightness.toFloat()) }
    var sleep by remember(settings.sleepSec) { mutableFloatStateOf(settings.sleepSec.toFloat()) }
    var city by remember(sources.city) { mutableStateOf(sources.city) }
    var latitude by remember(sources.latitude) { mutableStateOf(sources.latitude.toString()) }
    var longitude by remember(sources.longitude) { mutableStateOf(sources.longitude.toString()) }
    var symbols by remember(sources.stockSymbols) { mutableStateOf(sources.stockSymbols.joinToString(", ")) }
    var stepsGoal by remember(sources.stepsGoal) { mutableStateOf(sources.stepsGoal.toString()) }
    var calendarDays by remember(sources.calendarDays) { mutableStateOf(sources.calendarDays.toString()) }
    var savedFlash by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text("Fine-tune the display and the data it receives.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        item {
            SectionCard(title = "Display", subtitle = "Applied instantly when connected") {
                Text("Brightness · ${(brightness / 2.55f).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Slider(
                    value = brightness,
                    onValueChange = {
                        brightness = it
                        // The widget follows the slider live while you drag.
                        AppGraph.engine.previewBrightness(it.toInt())
                    },
                    onValueChangeFinished = {
                        AppGraph.repository.setSettings(settings.copy(brightness = brightness.toInt().coerceIn(10, 255)))
                    },
                    valueRange = 10f..255f,
                    colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Peach, inactiveTrackColor = Line),
                )
                val timeoutLabel = when (val s = sleep.toInt()) {
                    0 -> "Screen Timeout · Never (Always On)"
                    in 1..59 -> "Screen Timeout · $s seconds"
                    60 -> "Screen Timeout · 1 minute"
                    120 -> "Screen Timeout · 2 minutes"
                    180 -> "Screen Timeout · 3 minutes"
                    240 -> "Screen Timeout · 4 minutes"
                    else -> "Screen Timeout · ${s / 60}m ${s % 60}s"
                }
                Text(timeoutLabel, style = MaterialTheme.typography.bodyMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Never", 30 to "30s", 60 to "1m", 120 to "2m", 240 to "4m").forEach { (sec, label) ->
                        val selected = sleep.toInt() == sec
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (selected) Ink else Paper,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    sleep = sec.toFloat()
                                    AppGraph.repository.setSettings(settings.copy(sleepSec = sec))
                                }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (selected) CardWhite else Ink,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = sleep,
                    onValueChange = { sleep = it },
                    onValueChangeFinished = {
                        AppGraph.repository.setSettings(settings.copy(sleepSec = sleep.toInt()))
                    },
                    valueRange = 0f..240f,
                    steps = 23,
                    colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Sage, inactiveTrackColor = Line),
                )
                Spacer(Modifier.height(6.dp))
                Text("Rotation", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..3).forEach { rotation ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (settings.rotation == rotation) Ink else Paper,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    AppGraph.repository.setSettings(settings.copy(rotation = rotation))
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${rotation * 90}°",
                                color = if (settings.rotation == rotation) CardWhite else Ink,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Theme", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (mode, label) ->
                        val selected = settings.themeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (selected) Ink else Paper, RoundedCornerShape(12.dp))
                                .clickable { AppGraph.repository.setSettings(settings.copy(themeMode = mode)) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (selected) CardWhite else Ink,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Text(
                    "The widget switches to the same theme.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow("24-hour clock", "Show 21:04 instead of 9:04 PM", !settings.twelveHour) {
                    AppGraph.repository.setSettings(settings.copy(twelveHour = !it))
                }
                ToggleRow("Seconds", "Animate the seconds bar on the clock", settings.showSeconds) {
                    AppGraph.repository.setSettings(settings.copy(showSeconds = it))
                }
                ToggleRow("Wi-Fi while charging", "Network is set up on the Sync tab", settings.wifiEnabled) {
                    AppGraph.repository.setSettings(settings.copy(wifiEnabled = it))
                }
                ToggleRow("Fix photo colours", "Turn on if the photo looks blue (swaps red and blue)", settings.photoSwapRb) {
                    AppGraph.repository.setSettings(settings.copy(photoSwapRb = it))
                }
            }
        }
        item {
            SectionCard(title = "Weather & location", subtitle = "Open-Meteo, no API key") {
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it.take(15) },
                    label = { Text("City label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                ToggleRow("Use phone location", "Prefer GPS when permission is granted", sources.useGps) {
                    AppGraph.repository.setSources(sources.copy(useGps = it))
                }
                Text("Refresh every ${sources.weatherMinutes} min", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Slider(
                    value = sources.weatherMinutes.toFloat(),
                    onValueChange = { AppGraph.repository.setSources(sources.copy(weatherMinutes = (it / 5).toInt() * 5)) },
                    valueRange = 10f..120f,
                    colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Sky, inactiveTrackColor = Line),
                )
            }
        }
        item {
            SectionCard(title = "Markets", subtitle = "Yahoo Finance quotes with a 24-day live chart") {
                OutlinedTextField(
                    value = symbols,
                    onValueChange = { symbols = it },
                    label = { Text("Symbols, comma separated") },
                    supportingText = { Text("Up to 6. e.g. AAPL.US, MSFT.US, RELIANCE.IN, TCS.IN") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        AppGraph.repository.setSources(
                            sources.copy(
                                stockSymbols = symbols.split(',', ' ', '\n')
                                    .map { it.trim().uppercase() }
                                    .filter { it.isNotBlank() }
                                    .take(6),
                            ),
                        )
                        AppGraph.engine.refreshStocks(force = true)
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                ) {
                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save watchlist")
                }
                Text(
                    "Choose which stock a card shows (or the whole list) in Layout → Stocks card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.height(6.dp))
                Text("Refresh every ${sources.stockMinutes} min", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Slider(
                    value = sources.stockMinutes.toFloat(),
                    onValueChange = { AppGraph.repository.setSources(sources.copy(stockMinutes = (it / 5).toInt() * 5)) },
                    valueRange = 5f..120f,
                    colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Butter, inactiveTrackColor = Line),
                )
            }
        }
        item {
            SectionCard(title = "Daily life") {
                OutlinedTextField(
                    value = stepsGoal,
                    onValueChange = { stepsGoal = it.filter(Char::isDigit).take(6) },
                    label = { Text("Step goal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = calendarDays,
                    onValueChange = { calendarDays = it.filter(Char::isDigit).take(2) },
                    label = { Text("Calendar look-ahead (days)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow("Calendar", "Show the next upcoming event", sources.calendarEnabled) {
                    AppGraph.repository.setSources(sources.copy(calendarEnabled = it))
                }
                ToggleRow("Now playing", "Read the active media session", sources.mediaEnabled) {
                    AppGraph.repository.setSources(sources.copy(mediaEnabled = it))
                }
                ToggleRow("Notification relay", "Mirror the latest notification", sources.notificationsEnabled) {
                    AppGraph.repository.setSources(sources.copy(notificationsEnabled = it))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val lat = latitude.toDoubleOrNull() ?: sources.latitude
                            val lon = longitude.toDoubleOrNull() ?: sources.longitude
                            AppGraph.repository.setSources(
                                sources.copy(
                                    city = city.ifBlank { "Local" },
                                    latitude = lat.coerceIn(-90.0, 90.0),
                                    longitude = lon.coerceIn(-180.0, 180.0),
                                    stockSymbols = symbols.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }.take(6),
                                    stepsGoal = stepsGoal.toIntOrNull()?.coerceIn(100, 100_000) ?: sources.stepsGoal,
                                    calendarDays = calendarDays.toIntOrNull()?.coerceIn(1, 60) ?: sources.calendarDays,
                                ),
                            )
                            savedFlash = true
                            AppGraph.engine.refreshWeather(force = true)
                            AppGraph.engine.refreshStocks(force = true)
                            AppGraph.engine.refreshCalendar()
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (savedFlash) "Saved" else "Save sources")
                    }
                }
            }
        }
        item {
            SectionCard(title = "Permissions", subtitle = "Each source needs its own access") {
                PermissionRow("Bluetooth", hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ||
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { onRequestPermissions() }
                PermissionRow("Notification access", notificationAccess) { openNotificationAccess(context) }
                PermissionRow(
                    "Activity recognition",
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION),
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }
                PermissionRow("Calendar", hasPermission(context, Manifest.permission.READ_CALENDAR)) {
                    permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
                PermissionRow("Location", hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
        item {
            SectionCard(title = "Home screen widget", subtitle = "Everything in one widget") {
                Text(
                    "Clock, weather, events, music, steps and stocks in one card. Make it bigger to see more: 2×2 shows the basics, 4×2 adds events and a stock, 4×4 shows everything.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val manager = AppWidgetManager.getInstance(context)
                        if (manager.isRequestPinAppWidgetSupported) {
                            manager.requestPinAppWidget(
                                ComponentName(context, EspDashboardReceiver::class.java),
                                null,
                                null,
                            )
                        } else {
                            AppGraph.repository.log("Launcher does not support widget pinning")
                        }
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                ) {
                    Text("Add to home screen")
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(bottom = 18.dp)) {
                Text("ESP Widget 1.2", style = MaterialTheme.typography.labelSmall)
                Text(
                    "Protocol v1 · 240 × 240 · ESP32-C3",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { AppGraph.repository.log("Refresh all data requested") }) {
                    Text("Log a refresh checkpoint", color = Muted)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(if (granted) "Granted" else "Needs access", style = MaterialTheme.typography.bodyMedium, color = if (granted) Good else Bad)
        }
        if (!granted) {
            TextButton(onClick = onFix) {
                Icon(Icons.Rounded.OpenInNew, null, tint = Ink, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Allow", color = Ink)
            }
        } else {
            Icon(Icons.Rounded.Check, null, tint = Good, modifier = Modifier.size(18.dp))
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
