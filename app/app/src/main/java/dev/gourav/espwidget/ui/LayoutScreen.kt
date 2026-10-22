package dev.gourav.espwidget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gourav.espwidget.AppGraph
import dev.gourav.espwidget.data.AppState
import dev.gourav.espwidget.data.SlotModel
import dev.gourav.espwidget.data.widgetName

@Composable
fun LayoutScreen(state: AppState, onPreviewPage: (Int) -> Unit) {
    val pages = state.persisted.layout.pages
    var page by rememberSaveable { mutableIntStateOf(state.persisted.settings.currentPage) }
    LaunchedEffect(pages.size) { if (page >= pages.size) page = 0 }
    val current = pages.getOrNull(page)
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Page layout", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Arrange the little cards exactly as they appear on the display.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                pages.indices.forEach { index ->
                    val selected = index == page
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(38.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (selected) Ink else CardWhite)
                            .clickable { page = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            color = if (selected) CardWhite else Ink,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (pages.size < 5) {
                    IconButton(
                        onClick = {
                            AppGraph.repository.addPage()
                            page = pages.size
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(CardWhite, RoundedCornerShape(13.dp)),
                    ) {
                        Icon(Icons.Rounded.Add, "Add page", tint = Ink)
                    }
                }
                Spacer(Modifier.width(4.dp))
                if (pages.size > 1) {
                    IconButton(
                        onClick = {
                            AppGraph.repository.removePage(page)
                            page = (page - 1).coerceAtLeast(0)
                        },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Rounded.Close, "Remove page", tint = Muted)
                    }
                }
            }
        }
        item {
            DevicePreview(
                state,
                controlledPage = page,
                onPageChange = {
                    page = it
                    onPreviewPage(it)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TemplateChip(
                    label = "Hero + 2",
                    selected = current?.template == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { AppGraph.repository.setTemplate(page, 0) },
                )
                TemplateChip(
                    label = "Grid 2 × 2",
                    selected = current?.template == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { AppGraph.repository.setTemplate(page, 1) },
                )
            }
        }
        val slots = if (current?.template == 1) 4 else 3
        items(slots) { index ->
            val slot = current?.slots?.getOrNull(index) ?: SlotModel()
            SlotEditor(
                slot = slot,
                label = if (current?.template == 1) {
                    listOf("Top left", "Top right", "Bottom left", "Bottom right")[index]
                } else {
                    listOf("Hero card", "Small card 1", "Small card 2")[index]
                },
                onChange = { newSlot -> AppGraph.repository.setSlot(page, index, newSlot) },
            )
        }
        item {
            Text(
                "Changes sync automatically while Bluetooth is connected. The widget keeps the last layout when offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
    }
}

@Composable
private fun TemplateChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Peach.copy(alpha = 0.45f) else CardWhite)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SlotEditor(
    slot: SlotModel,
    label: String,
    onChange: (SlotModel) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { menuOpen = true }
                            .padding(vertical = 3.dp),
                    ) {
                        Text(
                            if (slot.isEmpty) "Empty" else widgetName(slot.type),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Icon(Icons.Rounded.ArrowDropDown, null, tint = Muted)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Empty") },
                            onClick = {
                                onChange(slot.copy(type = SlotModel.EMPTY))
                                menuOpen = false
                            },
                        )
                        (0..9).forEach { type ->
                            DropdownMenuItem(
                                text = { Text(widgetName(type)) },
                                onClick = {
                                    onChange(slot.copy(type = type))
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            if (!slot.isEmpty) {
                IconButton(onClick = { onChange(SlotModel()) }) {
                    Icon(Icons.Rounded.Close, "Clear slot", tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (!slot.isEmpty) {
            if (slot.type == 3) {
                Spacer(Modifier.height(8.dp))
                Text("Watchlist slot", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..5).forEach { option ->
                        OptionChip("${option + 1}", slot.option == option) {
                            onChange(slot.copy(option = option))
                        }
                    }
                }
            }
            if (slot.type == 8) {
                Spacer(Modifier.height(8.dp))
                Text("Note slot", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..3).forEach { option ->
                        OptionChip("${option + 1}", slot.option == option) {
                            onChange(slot.copy(option = option))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Accent", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..5).forEach { accent ->
                    AccentDot(accent, slot.accent == accent) { onChange(slot.copy(accent = accent)) }
                }
            }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Ink else Paper, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) CardWhite else Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
