package com.livelens.translator.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.livelens.translator.data.repository.AppSettings
import com.livelens.translator.data.repository.FontSizePref
import com.livelens.translator.data.repository.SubtitlePosition
import com.livelens.translator.model.ModelStatusInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onNavigateToModelSetup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.appSettings.collectAsState()
    val modelStatus by viewModel.modelStatusInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── TTS ──────────────────────────────────────────────────────────
            item { SettingsSectionHeader("Text-to-Speech") }
            item {
                SettingsCard {
                    SettingsToggleRow(
                        title = "Enable TTS Readout",
                        subtitle = "Read Vietnamese translations aloud",
                        checked = settings.ttsEnabled,
                        onCheckedChange = { viewModel.setTtsEnabled(it) }
                    )
                    if (settings.ttsEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Column {
                            Text(
                                text = "Speed: ${speedLabel(settings.ttsSpeed)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = settings.ttsSpeed,
                                onValueChange = { viewModel.setTtsSpeed(it) },
                                valueRange = 0.75f..1.25f,
                                steps = 1
                            )
                        }
                    }
                }
            }

            // ── Overlay ───────────────────────────────────────────────────────
            item { SettingsSectionHeader("Overlay") }
            item {
                SettingsCard {
                    Column {
                        Text(
                            text = "Opacity: ${(settings.overlayOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = settings.overlayOpacity,
                            onValueChange = { viewModel.setOverlayOpacity(it) },
                            valueRange = 0.50f..0.90f
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingsSegmentRow(
                        title = "Subtitle Position",
                        options = listOf("Top", "Bottom"),
                        selected = if (settings.subtitlePosition == SubtitlePosition.TOP) 0 else 1,
                        onSelect = { idx ->
                            viewModel.setSubtitlePosition(
                                if (idx == 0) SubtitlePosition.TOP else SubtitlePosition.BOTTOM
                            )
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingsSegmentRow(
                        title = "Font Size",
                        options = listOf("Small", "Medium", "Large"),
                        selected = settings.fontSize.ordinal,
                        onSelect = { viewModel.setFontSize(FontSizePref.values()[it]) }
                    )
                }
            }

            // ── Translation behavior ──────────────────────────────────────────
            item { SettingsSectionHeader("Translation Behavior") }
            item {
                SettingsCard {
                    SettingsSegmentRow(
                        title = "Max Overlay Sentences",
                        options = listOf("1", "2", "3"),
                        selected = settings.maxSentences - 1,
                        onSelect = { viewModel.setMaxSentences(it + 1) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingsSegmentRow(
                        title = "Sentence Drop Threshold",
                        subtitle = "Drop oldest when queue exceeds this",
                        options = listOf("1", "2", "3"),
                        selected = settings.dropThreshold - 1,
                        onSelect = { viewModel.setDropThreshold(it + 1) }
                    )
                }
            }

            // ── Model status ──────────────────────────────────────────────────
            item { SettingsSectionHeader("AI Models") }
            item {
                ModelStatusCard(
                    modelStatus = modelStatus,
                    onManageClick = onNavigateToModelSetup
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSegmentRow(
    title: String,
    subtitle: String? = null,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, label ->
                if (index == selected) {
                    FilledTonalButton(
                        onClick = { onSelect(index) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label)
                    }
                } else {
                    TextButton(
                        onClick = { onSelect(index) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(modelStatus: ModelStatusInfo?, onManageClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ModelTraining, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Model Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = onManageClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Manage", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (modelStatus != null) {
                Spacer(Modifier.height(12.dp))
                ModelStatusRow("STT (Zipformer)", modelStatus.sttReady, "${modelStatus.sttSizeMb} MB")
                ModelStatusRow("VAD (Silero)", modelStatus.vadReady)
                ModelStatusRow("TTS (Piper)", modelStatus.ttsReady)
                ModelStatusRow("Diarization", modelStatus.diarizationReady)
                ModelStatusRow("Gemma Translate", modelStatus.gemmaReady, "${modelStatus.gemmaSizeMb} MB")
            }
        }
    }
}

@Composable
private fun ModelStatusRow(name: String, ready: Boolean, size: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (size != null) {
                Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = if (ready) "✓ Ready" else "✗ Missing",
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun speedLabel(speed: Float): String = when {
    speed <= 0.80f -> "0.75×"
    speed <= 1.10f -> "1.0×"
    else -> "1.25×"
}
