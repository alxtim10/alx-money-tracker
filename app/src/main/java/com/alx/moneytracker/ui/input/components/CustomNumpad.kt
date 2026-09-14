package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A custom in-app numeric keypad with digit keys 0-9 and a delete key.
 *
 * All input is provided through in-app buttons so the operating system keyboard is never shown for
 * numeric entry (Requirements 1.2, 1.3, AGENTS.md UI Rule 1). This composable is stateless: it emits
 * [onDigit] for a tapped digit and [onDelete] for the backspace key.
 *
 * Styling follows the Gemini aesthetic (design.md): digit keys are **borderless** — the numeral
 * stands over the background surface — with a **circular ripple** on press and **haptic feedback**
 * on every tap. The delete key uses a muted red (error container) treatment. Keys use a flat aspect
 * ratio so the keypad stays short and the note field remains reachable without scrolling.
 *
 * Layout is a 4-row grid:
 * ```
 * 1 2 3
 * 4 5 6
 * 7 8 9
 *   0 ⌫
 * ```
 */
@Composable
fun CustomNumpad(
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("numpad"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NumpadRow {
            DigitKey(1, onDigit); DigitKey(2, onDigit); DigitKey(3, onDigit)
        }
        NumpadRow {
            DigitKey(4, onDigit); DigitKey(5, onDigit); DigitKey(6, onDigit)
        }
        NumpadRow {
            DigitKey(7, onDigit); DigitKey(8, onDigit); DigitKey(9, onDigit)
        }
        NumpadRow {
            KeySpacer()
            DigitKey(0, onDigit)
            DeleteKey(onDelete)
        }
    }
}

@Composable
private fun NumpadRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun RowScope.DigitKey(digit: Int, onDigit: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .aspectRatio(3.0f)
            .clip(CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDigit(digit)
            }
            .testTag("numpad_key_$digit")
    ) {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RowScope.DeleteKey(onDelete: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .aspectRatio(3.0f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete()
            }
            .semantics { contentDescription = "Delete last digit" }
            .testTag("numpad_delete")
    ) {
        Text(
            text = "\u232B",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun RowScope.KeySpacer() {
    Spacer(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(3.0f)
    )
}
