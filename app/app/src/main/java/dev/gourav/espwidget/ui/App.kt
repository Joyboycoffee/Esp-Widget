package dev.gourav.espwidget.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gourav.espwidget.AppGraph

private enum class Tab(val label: String) {
    HOME("Display"),
    LAYOUT("Layout"),
    SYNC("Sync"),
    SETTINGS("Settings"),
}

@Composable
fun EspWidgetApp() {
    val state by AppGraph.repository.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    var requested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.BLUETOOTH_CONNECT] != false
        AppGraph.repository.log(if (granted) "Permissions updated" else "Bluetooth permission was declined")
        if (granted && AppGraph.ble.hasPermissions()) dev.gourav.espwidget.service.SyncService.start(context)
    }
    val requestPermissions = {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list += Manifest.permission.ACTIVITY_RECOGNITION
        }
        permissionLauncher.launch(list.toTypedArray())
    }
    LaunchedEffect(Unit) {
        if (!requested) {
            requested = true
            val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("permissions_asked", false)) {
                prefs.edit().putBoolean("permissions_asked", true).apply()
                requestPermissions()
            }
        }
    }
    Scaffold(
        containerColor = Paper,
        bottomBar = {
            NavigationBar(containerColor = CardWhite, tonalElevation = 0.dp) {
                val icons = listOf(
                    Icons.Rounded.Dashboard,
                    Icons.Rounded.Bluetooth,
                    Icons.Rounded.Sync,
                    Icons.Rounded.Settings,
                )
                Tab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icons[index], contentDescription = item.label) },
                        label = { Text(item.label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink,
                            selectedTextColor = Ink,
                            indicatorColor = Peach.copy(alpha = 0.55f),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Paper)
                .padding(inner)
                .statusBarsPadding(),
        ) {
            when (Tab.entries[tab]) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    onOpenSync = { tab = 2 },
                    onOpenLayout = { tab = 1 },
                    onGrantBluetooth = requestPermissions,
                )
                Tab.LAYOUT -> LayoutScreen(
                    state = state,
                    onPreviewPage = {},
                )
                Tab.SYNC -> SyncScreen(
                    state = state,
                    requestPermissions = requestPermissions,
                )
                Tab.SETTINGS -> SettingsScreen(
                    state = state,
                    onRequestPermissions = requestPermissions,
                )
            }
        }
    }
}

fun openNotificationAccess(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}
