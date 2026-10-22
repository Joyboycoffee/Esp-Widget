package dev.gourav.espwidget.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.AppState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncScreen(state: AppState, requestPermissions: () -> Unit) {
    val context = LocalContext.current
    var noteDraft by remember(state.persisted.notes) { mutableStateOf(state.persisted.notes) }
    var confirmReset by remember { mutableStateOf(false) }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) AppGraph.engine.sendPhotoFromUri(uri)
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Sync", style = MaterialTheme.typography.headlineMedium)
                Text("Bluetooth keeps the widget in sync. Wi-Fi is optional, for weather while charging.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (state.link.connected) Sage.copy(alpha = 0.35f) else Paper, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.link.connected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                            null,
                            tint = Ink,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.link.deviceName ?: state.persisted.deviceName.ifBlank { "ESP-WIDGET" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            when {
                                state.link.connected -> "Connected · encrypted"
                                state.link.connecting -> "Connecting…"
                                state.link.scanning -> "Scanning nearby…"
                                else -> "Not connected"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    }
                    StatusPill(if (state.link.connected) "LINKED" else "OFFLINE", state.link.connected)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.link.connected) {
                        Button(
                            onClick = { AppGraph.engine.sendSnapshot(state.persisted) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Send everything")
                        }
                        OutlinedButton(
                            onClick = { AppGraph.ble.disconnect() },
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                        ) { Text("Disconnect", color = Ink) }
                    } else {
                        Button(
                            onClick = {
                                if (!AppGraph.ble.hasPermissions()) {
                                    requestPermissions()
                                } else if (!AppGraph.ble.isBluetoothReady) {
                                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                } else {
                                    AppGraph.ble.startScan()
                                }
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.link.scanning) "Searching…" else "Find my widget")
                        }
                        OutlinedButton(
                            onClick = { AppGraph.ble.connectFirstPaired() },
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                        ) { Text("Paired", color = Ink) }
                    }
                }
                if (!AppGraph.ble.hasPermissions()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Bluetooth permission is needed to find the widget.",
                        color = Bad,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.link.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Bad, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { WifiCard(state) }
        item {
            SectionCard(title = "Widget status", subtitle = "Reported by the ESP32-C3 over BLE") {
                val status = state.link.status
                if (status == null) {
                    Text("Connect to see live diagnostics.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LabelValue("Firmware", state.link.firmware ?: "—", Modifier.weight(0.8f))
                        LabelValue("Battery", "${status.battery}% · ${status.millivolts} mV", Modifier.weight(1.3f))
                        LabelValue("Power", powerLabel(status), Modifier.weight(1.1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LabelValue("Heap", "${status.heapKb} KB", Modifier.weight(1f))
                        LabelValue("Uptime", formatUptime(status.uptime), Modifier.weight(1f))
                        LabelValue("Page", "${status.page + 1}/${state.persisted.layout.pages.size}", Modifier.weight(0.8f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LabelValue("WiFi", wifiLabel(status.wifiState), Modifier.weight(1f))
                        LabelValue("RSSI", if (status.rssi != 0) "${status.rssi} dBm" else "—", Modifier.weight(1f))
                        LabelValue("Layout", "v${status.layoutVersion}", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { AppGraph.ble.send(dev.gourav.espwidget.protocol.Msg.STATUS_REQ) }) {
                        Text("Refresh diagnostics", color = Ink)
                    }
                }
            }
        }
        item {
            SectionCard(title = "Photo frame", subtitle = "Centre-cropped to 240 × 240 and sent over BLE") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { photoPicker.launch("image/*") },
                        enabled = state.link.connected,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Choose photo")
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (state.persisted.photoPath.isNotBlank()) "Last photo ready" else "No photo yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A 512-byte chunk is sent per message with the same CRC-protected framing as every other update.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        item {
            SectionCard(title = "Notes", subtitle = "Four short notes for the Note widget") {
                noteDraft.forEachIndexed { index, text ->
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            noteDraft = noteDraft.toMutableList().also { it[index] = value.take(95) }
                        },
                        label = { Text("Note ${index + 1}") },
                        trailingIcon = {
                            TextButton(onClick = {
                                AppGraph.repository.setNote(index, noteDraft[index])
                                AppGraph.engine.sendNote(index, noteDraft[index])
                            }) { Text("Send", color = Ink) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    if (index < 3) Spacer(Modifier.height(8.dp))
                }
            }
        }
        item {
            SectionCard(title = "Activity") {
                if (state.link.log.isEmpty()) {
                    Text("No activity yet.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                } else {
                    state.link.log.take(12).forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = if (index == 0) Ink else Muted)
                    }
                }
            }
        }
        item {
            SectionCard(title = "Careful now") {
                Text(
                    "A factory reset clears layouts, WiFi credentials, notes and Bluetooth bonds on the widget.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmReset = true },
                    enabled = state.link.connected,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Bad.copy(alpha = 0.5f)),
                ) { Text("Factory reset widget", color = Bad) }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the widget?") },
            text = { Text("This cannot be undone. You will need to pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.engine.factoryReset()
                    confirmReset = false
                }) { Text("Reset", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = Muted) }
            },
            containerColor = CardWhite,
        )
    }
}

private fun powerLabel(status: dev.gourav.espwidget.data.DeviceStatus): String = when {
    status.chargeFull -> "Docked · full"
    status.charging -> "Docked · charging"
    status.usb -> "Docked · idle"
    else -> "On battery"
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun wifiLabel(state: Int): String = when (state) {
    1 -> "Connecting"
    2 -> "Connected"
    3 -> "Error"
    else -> "Off"
}
