package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.QuickPreset

/**
 * Renders the configured [presets] as a wrapping row of chips. Each tap emits the tapped preset's
 * amount via [onPresetTap] so the caller can accumulate it into the running amount (Requirement 2.1).
 *
 * Stateless: holds no selection; presets are additive so there is nothing to highlight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickPresetChips(
    presets: List<QuickPreset>,
    onPresetTap: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("quick_preset_chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            AssistChip(
                onClick = { onPresetTap(preset.amount) },
                label = { Text(preset.label) },
                modifier = Modifier.testTag("preset_chip_${preset.id}")
            )
        }
    }
}
