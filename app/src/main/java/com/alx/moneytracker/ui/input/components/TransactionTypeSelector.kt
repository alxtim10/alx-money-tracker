package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.TransactionType

/**
 * A single-choice segmented control over the three [TransactionType] values. The [selectedType]
 * renders as the active segment; tapping a segment emits [onTypeSelected] (Requirement 3.3).
 *
 * Stateless: the active selection is driven entirely by [selectedType].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionTypeSelector(
    selectedType: TransactionType,
    onTypeSelected: (TransactionType) -> Unit,
    modifier: Modifier = Modifier
) {
    val types = TransactionType.entries
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("type_selector")
    ) {
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selectedType,
                onClick = { onTypeSelected(type) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                modifier = Modifier.testTag("type_${type.name}")
            ) {
                Text(type.displayLabel())
            }
        }
    }
}

private fun TransactionType.displayLabel(): String = when (this) {
    TransactionType.EXPENSE -> "Expense"
    TransactionType.INCOME -> "Income"
    TransactionType.TRANSFER -> "Transfer"
}
