package dev.gourav.espwidget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.AppState

/** The widget's own battery plus remote wake / deep-sleep buttons. */
@Composable
fun WidgetPowerCard(state: AppState) {
    val status = state.link.status
    val connected = state.link.connected
    var confirmSleep by remember { mutableStateOf(false) }

    SectionCard(title = "Widget power", subtitle = "Battery and remote controls") {
        val percent = status?.battery?.coerceIn(0, 100)
        val mode = when {
            status == null -> "No reading yet"
            status.chargeFull -> "Full"
            status.charging -> "Charging"
            status.usb -> "On USB power"
            else -> "On battery"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (status != null && percent != null && (status.millivolts > 0 || status.chargeFull)) "$percent%" else "--",
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(mode, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    if (status != null && status.millivolts > 0) "%.2f V".format(status.millivolts / 1000.0) else "No cell reading",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Line, RoundedCornerShape(5.dp)),
        ) {
            val fraction = ((percent ?: 0) / 100f).coerceIn(0f, 1f)
            val low = (percent ?: 100) <= 20 && status?.charging != true && status?.usb != true
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(if (low) Bad else Good, RoundedCornerShape(5.dp)),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { AppGraph.engine.wakeWidgetScreen() },
                enabled = connected,
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.LightMode, null, tint = Ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Wake screen", color = Ink)
            }
            Button(
                onClick = { confirmSleep = true },
                enabled = connected,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Deep sleep")
            }
        }
        Text(
            if (connected) "Deep sleep turns off Bluetooth, Wi-Fi and the screen. Only the button on the widget wakes it."
            else "Connect over Bluetooth to use these.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }

    if (confirmSleep) {
        AlertDialog(
            onDismissRequest = { confirmSleep = false },
            title = { Text("Put the widget to deep sleep?") },
            text = {
                Text("Bluetooth, Wi-Fi and the screen turn off, so the app can't reach it. Press the button on the widget to turn it back on.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSleep = false
                    AppGraph.engine.deepSleepWidget()
                }) { Text("Deep sleep") }
            },
            dismissButton = { TextButton(onClick = { confirmSleep = false }) { Text("Cancel") } },
        )
    }
}
