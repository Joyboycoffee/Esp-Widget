package dev.gourav.espwidget.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.AppState
import kotlinx.coroutines.delay

/**
 * Connect the widget to a Wi-Fi network from the phone:
 * scan (done by the widget) -> pick a network -> password -> Connect,
 * with the widget's live Wi-Fi state shown at the top.
 */
@Composable
fun WifiCard(state: AppState) {
    val linked = state.link.connected
    val status = state.link.status
    val savedSsid = state.persisted.sources.wifiSsid
    var scanning by remember { mutableStateOf(false) }
    var selectedSsid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }

    // Stop the spinner when results arrive, or give up after 15 s.
    LaunchedEffect(scanning, state.link.wifiScan) {
        if (scanning && state.link.wifiScan.isNotEmpty()) scanning = false
        if (scanning) {
            delay(15_000)
            scanning = false
        }
    }

    SectionCard(title = "Wi-Fi", subtitle = "Lets the widget fetch weather and time by itself") {
        // ---- live state -------------------------------------------------------
        val (label, detail, ok) = when {
            !linked -> Triple("Widget not connected", "Connect over Bluetooth above to set up Wi-Fi.", false)
            status == null -> Triple("Checking…", "", false)
            status.wifiState == 2 -> Triple(
                "Connected" + if (savedSsid.isNotBlank()) " to $savedSsid" else "",
                if (status.rssi != 0) "Signal ${signalWord(status.rssi)} (${status.rssi} dBm)" else "",
                true,
            )
            status.wifiState == 1 -> Triple(
                "Connecting" + if (savedSsid.isNotBlank()) " to $savedSsid…" else "…",
                "This takes up to 20 seconds.",
                false,
            )
            status.wifiState == 3 && savedSsid.isNotBlank() -> Triple(
                "Couldn't join $savedSsid",
                "Check the password, or move the widget closer to the router.",
                false,
            )
            savedSsid.isNotBlank() && !status.usb -> Triple(
                "Saved: $savedSsid",
                "Joins automatically while the widget is charging (saves battery).",
                false,
            )
            savedSsid.isNotBlank() -> Triple("Saved: $savedSsid", "Not connected yet.", false)
            else -> Triple("No network set", "Find networks below and pick yours.", false)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(if (ok) Sage.copy(alpha = 0.35f) else Paper, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (status?.wifiState == 1 && linked) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
                } else {
                    Icon(
                        if (ok) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                        null,
                        tint = if (status?.wifiState == 3) Bad else Ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        }

        // ---- scan --------------------------------------------------------------
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scanning = true
                    AppGraph.engine.requestWifiScan()
                },
                enabled = linked && !scanning,
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Ink)
                } else {
                    Icon(Icons.Rounded.Wifi, null, tint = Ink, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (scanning) "Scanning…" else "Find networks", color = Ink)
            }
            TextButton(onClick = { manualEntry = !manualEntry; selectedSsid = "" }, enabled = linked) {
                Text(if (manualEntry) "Pick from list" else "Type name", color = Muted)
            }
        }

        if (!manualEntry && state.link.wifiScan.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            state.link.wifiScan.take(8).forEach { network ->
                val selected = selectedSsid == network.ssid
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) Peach.copy(alpha = 0.25f) else CardWhite, RoundedCornerShape(12.dp))
                        .clickable {
                            selectedSsid = network.ssid
                            password = ""
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Wifi, null, tint = Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(network.ssid, style = MaterialTheme.typography.bodyLarge, color = Ink, modifier = Modifier.weight(1f))
                    if (network.secure) Icon(Icons.Rounded.Lock, null, tint = Muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(signalWord(network.rssi), fontSize = 11.sp, color = Muted)
                }
            }
        } else if (!manualEntry && scanning.not() && linked) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap Find networks. The widget looks for networks itself (2.4 GHz only).",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }

        // ---- credentials --------------------------------------------------------
        val showForm = linked && (manualEntry || selectedSsid.isNotBlank())
        if (showForm) {
            Spacer(Modifier.height(10.dp))
            if (manualEntry) {
                OutlinedTextField(
                    value = selectedSsid,
                    onValueChange = { selectedSsid = it.take(32) },
                    label = { Text("Network name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(63) },
                label = { Text(if (manualEntry) "Password" else "Password for $selectedSsid") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (showPassword) "Hide password" else "Show password",
                            tint = Muted,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    AppGraph.engine.sendWifiCredentials(selectedSsid.trim(), password)
                    password = ""
                    selectedSsid = ""
                    manualEntry = false
                },
                enabled = selectedSsid.isNotBlank(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = CardWhite),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
        }

        if (savedSsid.isNotBlank() && linked) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { AppGraph.engine.forgetWifi() }) {
                Text("Forget $savedSsid", color = Muted)
            }
        }
    }
}

private fun signalWord(rssi: Int): String = when {
    rssi >= -55 -> "Excellent"
    rssi >= -67 -> "Good"
    rssi >= -78 -> "Fair"
    else -> "Weak"
}
