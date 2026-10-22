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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.AppState
import dev.gourav.espwidget.data.StockModel
import java.util.Locale

/**
 * Dedicated stocks screen: turn on the full-screen stocks mode for the widget,
 * edit the watchlist and choose how often quotes refresh.
 */
@Composable
fun StocksScreen(state: AppState) {
    val settings = state.persisted.settings
    val sources = state.persisted.sources
    var symbols by remember(sources.stockSymbols) { mutableStateOf(sources.stockSymbols.joinToString(", ")) }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Stocks", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Text(
                "Watch your list on the widget's whole screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
        item {
            SectionCard(title = "Stocks mode", subtitle = "Replaces the normal pages on the widget") {
                ToggleRow(
                    "Full-screen stocks",
                    "Tap the widget to flip between the list and each stock's big chart",
                    settings.stocksMode,
                ) {
                    AppGraph.repository.setSettings(settings.copy(stocksMode = it))
                    if (it) {
                        if (sources.stockMinutes > 5) {
                            AppGraph.repository.setSources(sources.copy(stockMinutes = 2))
                        }
                        AppGraph.engine.refreshStocks(force = true)
                    }
                }
                Text(
                    if (state.link.connected) "Widget connected" else "Connect the widget on the Sync tab to apply this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        item {
            SectionCard(title = "Watchlist", subtitle = "Up to 6 symbols, e.g. AAPL.US, MSFT.US, RELIANCE.IN") {
                OutlinedTextField(
                    value = symbols,
                    onValueChange = { symbols = it },
                    label = { Text("Symbols, comma separated") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = { AppGraph.engine.refreshStocks(force = true) },
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = Ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh now", color = Ink)
                    }
                }
            }
        }
        item {
            SectionCard(title = "Refresh", subtitle = "How often the phone fetches new prices") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "1 min", 2 to "2 min", 5 to "5 min", 15 to "15 min", 30 to "30 min").forEach { (min, label) ->
                        val selected = sources.stockMinutes == min
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (selected) Ink else Paper, RoundedCornerShape(12.dp))
                                .clickable { AppGraph.repository.setSources(sources.copy(stockMinutes = min)) }
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
                Spacer(Modifier.height(6.dp))
                Text(
                    "Prices come from Yahoo Finance through your phone. Some exchanges publish delayed quotes, " +
                        "so \"real time\" is as fresh as the exchange allows. The phone must be connected to the widget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        item {
            SectionCard(title = "Latest quotes") {
                val quotes = state.persisted.stocks.filter { it.valid }
                if (quotes.isEmpty()) {
                    Text("No quotes yet. Save a watchlist and tap Refresh now.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                } else {
                    quotes.forEach { QuoteRow(it) }
                }
            }
        }
    }
}

@Composable
private fun QuoteRow(stock: StockModel) {
    val up = stock.changeBp >= 0
    val currency = when (stock.currency) {
        0 -> "Rs "
        1 -> "$"
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stock.symbol, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                currency + String.format(Locale.US, "%.2f", stock.priceCents / 100.0),
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Text(
                String.format(Locale.US, "%+.2f%%", stock.changeBp / 100.0),
                style = MaterialTheme.typography.bodyMedium,
                color = if (up) Good else Bad,
            )
        }
    }
}
