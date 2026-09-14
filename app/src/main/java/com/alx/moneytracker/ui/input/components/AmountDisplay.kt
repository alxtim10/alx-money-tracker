package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Read-only hero display of the current [runningAmount].
 *
 * This is a plain [Text] (never a `TextField`) so the operating system keyboard is never requested
 * for numeric input — amount entry happens exclusively through [CustomNumpad]
 * (Requirement 1.1, AGENTS.md UI Rule 1).
 *
 * The amount is rendered as an integer in the smallest currency unit, with thousands grouped by
 * '.' (rupiah-style), e.g. `1234567` -> `1.234.567` (Requirement 1.5), preceded by a soft "Rp"
 * prefix. A small caption above frames it as the amount being entered.
 */
@Composable
fun AmountDisplay(
    runningAmount: Long,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Amount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Rp",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            // Hero nominal: strong color while an amount is being typed, muted grey when empty
            // (design.md — nominal display color is dynamic). Letter spacing is loosened slightly.
            val amountColor =
                if (runningAmount > 0L) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = formatAmount(runningAmount),
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = amountColor,
                modifier = Modifier.testTag("amount_display")
            )
        }
    }
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
