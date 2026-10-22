package dev.gourav.espwidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.gourav.espwidget.ui.EspWidgetApp
import dev.gourav.espwidget.ui.EspWidgetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        enableEdgeToEdge()
        setContent {
            EspWidgetTheme {
                EspWidgetApp()
            }
        }
    }
}
