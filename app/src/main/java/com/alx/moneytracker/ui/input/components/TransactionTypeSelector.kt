package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.TransactionType

/**
 * A single-choice pill selector over the three [TransactionType] values. The [selectedType] pill
 * is filled with the accent color; the others are quiet surfaces. Tapping a pill emits
 * [onTypeSelected] (Requirement 3.3).
 *
 * Uses [selectable] with [Role.RadioButton] so each pill exposes selected semantics (test-visible
 * via `assertIsSelected`). Stateless: the active pill is driven entirely by [selectedType].
 */
@Composable
fun TransactionTypeSelector(
    selectedType: TransactionType,
    onTypeSelected: (TransactionType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("type_selector"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TransactionType.entries.forEach { type ->
            TypePill(
                type = type,
                selected = type == selectedType,
                onClick = { onTypeSelected(type) }
            )
        }
    }
}

@Composable
private fun RowScope.TypePill(
    type: TransactionType,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("type_${type.name}")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = type.displayLabel(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

private fun TransactionType.displayLabel(): String = when (this) {
    TransactionType.EXPENSE -> "Expense"
    TransactionType.INCOME -> "Income"
    TransactionType.TRANSFER -> "Transfer"
}
