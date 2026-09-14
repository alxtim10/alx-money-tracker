package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// AI-inspired accent gradient (design.md): blue -> violet, reserved for the primary action.
private val GradientStart = Color(0xFF4A90E2)
private val GradientEnd = Color(0xFF9013FE)

/**
 * The submit control for the transaction entry. Its enabled state is driven purely by [enabled]
 * (which the caller derives from the validated UI state), and a tap emits [onSubmit]
 * (Requirement 7.1). Placed by the screen in the lower region for one-handed reach (Requirement 8).
 *
 * Styling (design.md — "focus & glow"): a full-width, tall, fully-rounded button filled with the
 * AI-inspired blue->violet accent gradient when enabled, dimmed to a muted surface when disabled.
 * A tap triggers haptic feedback.
 */
@Composable
fun SubmitButton(
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(28.dp)

    val background =
        if (enabled) Brush.horizontalGradient(listOf(GradientStart, GradientEnd))
        else Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant
            )
        )
    val contentColor =
        if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(56.dp)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSubmit()
            }
            .testTag("submit_button")
    ) {
        Text(
            text = "Save Transaction",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
