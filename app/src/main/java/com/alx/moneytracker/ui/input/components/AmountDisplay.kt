package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Read-only display of the current [runningAmount].
 *
 * This is a plain [Text] (never a `TextField`) so the operating system keyboard is never requested
 * for numeric input — amount entry happens exclusively through [CustomNumpad]
 * (Requirement 1.1, AGENTS.md UI Rule 1).
 *
 * The amount is rendered as an integer in the smallest currency unit, with thousands grouped by
 * '.' (rupiah-style), e.g. `1234567` -> `1.234.567` (Requirement 1.5).
 */
@Composable
fun AmountDisplay(
    runningAmount: Long,
    modifier: Modifier = Modifier
) {
    Text(
        text = formatAmount(runningAmount),
        style = MaterialTheme.typography.displayMedium,
        textAlign = TextAlign.End,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("amount_display")
    )
}

/**
 * Formats a non-negative smallest-unit amount as a grouped integer string.
 *
 * Groups of three digits are separated by '.' (e.g. `1234567` -> `"1.234.567"`). No decimal point
 * is ever emitted because amounts are integers of the smallest currency unit.
 */
fun formatAmount(amount: Long): String {
    val digits = amount.coerceAtLeast(0L).toString()
    val builder = StringBuilder()
    val firstGroup = digits.length % 3
    for (i in digits.indices) {
        if (i != 0 && (i - firstGroup) % 3 == 0) {
            builder.append('.')
        }
        builder.append(digits[i])
    }
    return builder.toString()
}
