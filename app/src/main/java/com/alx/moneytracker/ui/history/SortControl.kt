package com.alx.moneytracker.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.SortOrder

/** testTag prefix for the individual sort chips; suffixed with the [SortOrder] name. */
const val SORT_CHIP_TAG_PREFIX: String = "sort_chip_"

/**
 * A single-choice control over the four [SortOrder] values, exactly one selected at a time
 * (Requirement 9.1). Defaults to [SortOrder.TIME_NEWEST_FIRST] via the [selectedSort] the caller
 * supplies (Requirement 9.2) — this composable is stateless and simply highlights whichever value
 * is currently selected.
 *
 * Rendered as a horizontally scrollable row of Material 3 [FilterChip]s so the four options stay
 * reachable on narrow screens without truncation. Selecting a chip emits [onSortSelected] with the
 * corresponding [SortOrder]; re-tapping the already-selected chip is a no-op (single-choice
 * semantics — there is always exactly one active order).
 *
 * Each chip carries a stable [testTag] ([SORT_CHIP_TAG_PREFIX] + the enum name) and a
 * content description so UI tests (task 12.4) can locate and assert selection state.
 */
@Composable
fun SortControl(
    selectedSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("sort_control"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SortOrder.entries.forEach { order ->
            val selected = order == selectedSort
            val label = order.displayLabel()
            FilterChip(
                selected = selected,
                onClick = { if (!selected) onSortSelected(order) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier
                    .testTag(SORT_CHIP_TAG_PREFIX + order.name)
                    .semantics { contentDescription = "Sort by $label" }
            )
        }
    }
}

/** Human-readable label for a [SortOrder] chip. */
private fun SortOrder.displayLabel(): String = when (this) {
    SortOrder.TIME_NEWEST_FIRST -> "Newest"
    SortOrder.TIME_OLDEST_FIRST -> "Oldest"
    SortOrder.AMOUNT_LARGEST_FIRST -> "Largest"
    SortOrder.AMOUNT_SMALLEST_FIRST -> "Smallest"
}
