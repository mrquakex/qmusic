package com.qmusic.ui.components

import com.qmusic.ui.theme.QMusicIcons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextSecondary
import com.qmusic.ui.viewmodel.EqualizerPreset
import kotlin.math.roundToInt

private val BandLabels = listOf(
    "31Hz", "62Hz", "125Hz", "250Hz", "500Hz",
    "1kHz", "2kHz", "4kHz", "8kHz", "16kHz",
)

@Composable
fun EqualizerDialog(
    equalizerBands: List<Float>,
    selectedPreset: EqualizerPreset?,
    onBandGainChange: (Int, Float) -> Unit,
    onPresetSelect: (EqualizerPreset) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    equalizerEnabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit = {},
) {
    val s = LocalStrings.current
    val customLabel = if (s.english) "Custom" else "Özel"
    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.equalizerTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = equalizerEnabled,
                        onCheckedChange = onEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = InkInverse,
                            checkedTrackColor = Ink,
                            uncheckedThumbColor = Ink.copy(alpha = 0.7f),
                            uncheckedTrackColor = DarkSurfaceVariant,
                            uncheckedBorderColor = Ink.copy(alpha = 0.3f),
                        ),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = QMusicIcons.Close,
                            contentDescription = s.close,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val chipColors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Ink,
                    selectedLabelColor = InkInverse,
                    containerColor = DarkSurfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = DarkSurfaceVariant.copy(alpha = 0.5f),
                    disabledLabelColor = TextSecondary,
                    disabledSelectedContainerColor = Ink.copy(alpha = 0.35f),
                    disabledSelectedLabelColor = InkInverse,
                )

                // Presets row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EqualizerPreset.entries.forEach { preset ->
                        val isSelected = preset == selectedPreset
                        FilterChip(
                            selected = isSelected,
                            enabled = equalizerEnabled,
                            onClick = { onPresetSelect(preset) },
                            label = { Text(s.presetDisplayName(preset)) },
                            colors = chipColors,
                        )
                    }
                    FilterChip(
                        selected = selectedPreset == null,
                        enabled = equalizerEnabled,
                        onClick = { },
                        label = { Text(customLabel) },
                        colors = chipColors,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 10 Band sliders
                Column(modifier = Modifier.alpha(if (equalizerEnabled) 1f else 0.55f)) {
                    equalizerBands.take(10).forEachIndexed { index, gain ->
                        val label = BandLabels.getOrElse(index) { "Band $index" }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val roundedGain = gain.roundToInt()
                                val sign = if (roundedGain > 0) "+$roundedGain" else "$roundedGain"
                                Text(
                                    text = "${sign} dB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }

                            Slider(
                                value = gain,
                                onValueChange = { onBandGainChange(index, it) },
                                enabled = equalizerEnabled,
                                valueRange = -12f..12f,
                                steps = 23,
                                colors = SliderDefaults.colors(
                                    thumbColor = Ink,
                                    activeTrackColor = Ink,
                                    inactiveTrackColor = DarkSurfaceVariant,
                                    disabledThumbColor = Ink.copy(alpha = 0.4f),
                                    disabledActiveTrackColor = Ink.copy(alpha = 0.4f),
                                    disabledInactiveTrackColor = DarkSurfaceVariant.copy(alpha = 0.5f),
                                ),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Done Button
                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink,
                        contentColor = InkInverse,
                    ),
                ) {
                    Text(s.applyAndClose)
                }
            }
        }
    }
}
