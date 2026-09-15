package com.alx.moneytracker.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.SyncStatus
import com.alx.moneytracker.domain.TransactionType

// ---- testTag prefixes so task 12.4's Compose tests can locate each chip ----

/** testTag prefix for a transaction-type chip; suffixed with the [TransactionType] name. */
const val TYPE_CHIP_TAG_PREFIX: String = "type_chip_"

/** testTag prefix for a wallet chip; suffixed with the wallet id. */
const val WALLET_CHIP_TAG_PREFIX: String = "wallet_chip_"

/** testTag prefix for a category chip; suffixed with the category id. */
const val CATEGORY_CHIP_TAG_PREFIX: String = "category_chip_"

/** testTag prefix for a sync-status chip; suffixed with the [SyncStatus] name. */
const val SYNC_CHIP_TAG_PREFIX: String = "sync_chip_"

/** testTag for the single time-range chip. */
const val TIME_CHIP_TAG: String = "time_chip"

/**
 * A horizontally scrollable header of Material 3 [FilterChip]s covering all five filter dimensions
 * (Requirement 8). From left to right it renders:
 *
 * - one chip per [TransactionType] value — EXPENSE, INCOME, TRANSFER (Requirement 3.1);
 * - one chip per available wallet from [HistoryUiState.availableWallets] (Requirement 4.1);
 * - one chip per available category from [HistoryUiState.availableCategories] (Requirement 5.1);
 * - a SYNCED chip and a PENDING chip (Requirement 6.1);
 * - a single time-range chip that opens a Material 3 date-range picker (Requirement 7.1).
 *
 * Each chip's selected state is derived from [HistoryUiState.filterSet], and a tap emits the
 * corresponding toggle [HistoryEvent]: [HistoryEvent.TypeChipToggled],
 * [HistoryEvent.WalletChipToggled], [HistoryEvent.CategoryChipToggled],
 * [HistoryEvent.SyncChipToggled], [HistoryEvent.TimeRangeSelected], and
 * [HistoryEvent.TimeFilterCleared].
 *
 * The time-range chip deliberately uses a Material 3 [DateRangePicker] rather than free numeric
 * entry (AGENTS.md UI Rule 1 — no system keyboard for numeric input): when active, tapping it
 * clears the range; when inactive, it opens the picker. Confirming the picker with a valid range
 * emits [HistoryEvent.TimeRangeSelected] with inclusive epoch-millis bounds.
 *
 * Stateless with respect to the filter set; the only local state is the picker's open/close flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterChipHeader(
    state: HistoryUiState,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val filterSet = state.filterSet
    val timeActive = filterSet.timeFilter != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("filter_chip_header"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- Type dimension: EXPENSE / INCOME / TRANSFER (Requirement 3.1) ---
        TransactionType.entries.forEach { type ->
            val label = type.displayLabel()
            HistoryFilterChip(
                selected = type in filterSet.types,
                label = label,
                testTag = TYPE_CHIP_TAG_PREFIX + type.name,
                contentDescription = "Filter by type $label",
                onClick = { onEvent(HistoryEvent.TypeChipToggled(type)) }
            )
        }

        // --- Wallet dimension: one chip per available wallet (Requirement 4.1) ---
        state.availableWallets.forEach { wallet ->
            HistoryFilterChip(
                selected = wallet.id in filterSet.walletIds,
                label = wallet.name,
                testTag = WALLET_CHIP_TAG_PREFIX + wallet.id,
                contentDescription = "Filter by wallet ${wallet.name}",
                onClick = { onEvent(HistoryEvent.WalletChipToggled(wallet.id)) }
            )
        }

        // --- Category dimension: one chip per available category (Requirement 5.1) ---
        state.availableCategories.forEach { category ->
            HistoryFilterChip(
                selected = category.id in filterSet.categoryIds,
                label = category.name,
                testTag = CATEGORY_CHIP_TAG_PREFIX + category.id,
                contentDescription = "Filter by category ${category.name}",
                onClick = { onEvent(HistoryEvent.CategoryChipToggled(category.id)) }
            )
        }

        // --- Sync dimension: SYNCED and PENDING chips (Requirement 6.1) ---
        SyncStatus.entries.forEach { status ->
            val label = status.displayLabel()
            HistoryFilterChip(
                selected = status in filterSet.syncStatuses,
                label = label,
                testTag = SYNC_CHIP_TAG_PREFIX + status.name,
                contentDescription = "Filter by sync status $label",
                onClick = { onEvent(HistoryEvent.SyncChipToggled(status)) }
            )
        }

        // --- Time dimension: single range chip opening a date-range picker (Requirement 7.1) ---
        HistoryFilterChip(
            selected = timeActive,
            label = "Time range",
            testTag = TIME_CHIP_TAG,
            contentDescription = "Filter by time range",
            onClick = {
                // Active -> clear the range (Requirement 7.4); inactive -> open the picker.
                if (timeActive) onEvent(HistoryEvent.TimeFilterCleared) else showDatePicker = true
            }
        )
    }

    if (showDatePicker) {
        TimeRangePickerDialog(
            onDismiss = { showDatePicker = false },
            onRangeConfirmed = { start, end ->
                onEvent(HistoryEvent.TimeRangeSelected(startInclusive = start, endInclusive = end))
                showDatePicker = false
            }
        )
    }
}

/**
 * A Material 3 date-range picker dialog (AGENTS.md UI Rule 1 — no numeric keyboard entry).
 *
 * Confirm is enabled only once both ends of the range are chosen; confirming emits the inclusive
 * epoch-millis bounds via [onRangeConfirmed]. Dismissing leaves the current filter untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangePickerDialog(
    onDismiss: () -> Unit,
    onRangeConfirmed: (startInclusive: Long, endInclusive: Long) -> Unit
) {
    val pickerState = rememberDateRangePickerState()
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = start != null && end != null,
                onClick = {
                    val s = start
                    val e = end
                    if (s != null && e != null) onRangeConfirmed(s, e)
                },
                modifier = Modifier.testTag("time_range_confirm")
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("time_range_cancel")) {
                Text("Cancel")
            }
        }
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.testTag("time_range_picker")
        )
    }
}

/** A themed Material 3 [FilterChip] carrying a stable [testTag] and [contentDescription]. */
@Composable
private fun HistoryFilterChip(
    selected: Boolean,
    label: String,
    testTag: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier
            .testTag(testTag)
            .semantics { this.contentDescription = contentDescription }
    )
}

/** Human-readable label for a transaction-type chip. */
private fun TransactionType.displayLabel(): String = when (this) {
    TransactionType.EXPENSE -> "Expense"
    TransactionType.INCOME -> "Income"
    TransactionType.TRANSFER -> "Transfer"
}

/** Human-readable label for a sync-status chip. */
private fun SyncStatus.displayLabel(): String = when (this) {
    SyncStatus.SYNCED -> "Synced"
    SyncStatus.PENDING -> "Pending"
}
