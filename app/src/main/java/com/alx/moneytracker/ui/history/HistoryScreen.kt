package com.alx.moneytracker.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag applied to the [TransactionLazyColumn] list container. */
const val TRANSACTION_LIST_TAG: String = "transaction_list"

/** Empty-state copy shown when the store has no matching records under the active filters. */
const val EMPTY_STATE_REASON_FILTERED: String = "No transactions match the current filters."

/** Empty-state copy shown when there are simply no transactions to display. */
const val EMPTY_STATE_REASON_NONE: String = "No transactions recorded yet."

/**
 * The scrollable Transaction_List (Requirement 1.1).
 *
 * Renders one [TransactionRow] per [TransactionRowUi] inside a [LazyColumn] keyed by the stable
 * `Transaction.id` ([TransactionRowUi.id]). Stable keys let Compose re-render only the row whose
 * data actually changed — e.g. a single transaction's `is_synced` flip updates just its
 * [SyncIndicator], leaving every other row untouched (Requirement 11.3).
 *
 * Stateless: the list is derived entirely from [rows]. [onEvent] is accepted so future per-row
 * interactions can be forwarded without changing the call site; the current rows are display-only.
 */
@Composable
fun TransactionLazyColumn(
    rows: List<TransactionRowUi>,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(TRANSACTION_LIST_TAG),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = rows, key = { row -> row.id }) { row ->
            TransactionRow(row = row)
        }
    }
}

/**
 * The stateless Transaction History screen (Requirement 1.1).
 *
 * Composes the already-implemented building blocks top-to-bottom:
 * 1. [FilterChipHeader] — the five filter dimensions (Requirement 8).
 * 2. [SortControl] — single-choice sort order, bound to [HistoryUiState.sortOrder]
 *    (Requirements 9.1, 9.2). Selecting a chip emits [HistoryEvent.SortSelected].
 * 3. [SummaryCard] — income/expense/net over the current Filtered_Set (Requirement 10.1).
 * 4. The list area, which shows one of:
 *    - [ErrorState] when [HistoryUiState.isError] — the header, sort control, and summary stay
 *      visible and the previously loaded rows are preserved upstream, so the error replaces only
 *      the list area rather than discarding the prior view (Requirements 1.7, 12.4);
 *    - [EmptyState] when there are no rows and no error (Requirement 1.6);
 *    - [TransactionLazyColumn] otherwise.
 *
 * Every interaction is forwarded to [onEvent]; this composable owns no state.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onEvent: (HistoryEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.testTag("history_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FilterChipHeader(state = state, onEvent = onEvent)

            SortControl(
                selectedSort = state.sortOrder,
                onSortSelected = { order -> onEvent(HistoryEvent.SortSelected(order)) }
            )

            SummaryCard(
                totals = state.totals,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // List area fills the remaining space and swaps between the list and the
            // empty/error placeholders (Requirements 1.6, 1.7).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    state.isError -> ErrorState()
                    state.isEmpty -> EmptyState(reason = emptyStateReason(state))
                    else -> TransactionLazyColumn(rows = state.rows, onEvent = onEvent)
                }
            }
        }
    }
}

/**
 * Chooses the empty-state copy: a "no matches" message when any filter is active, otherwise a
 * "nothing recorded yet" message (Requirements 1.6, 3.5, 5.5, 6.7, 7.5).
 */
private fun emptyStateReason(state: HistoryUiState): String =
    if (state.filterSet.isEmpty) EMPTY_STATE_REASON_NONE else EMPTY_STATE_REASON_FILTERED

/**
 * Stateful overload that binds the screen to a [TransactionHistoryViewModel].
 *
 * Collects [TransactionHistoryViewModel.uiState] and delegates to the stateless overload, routing
 * UI actions back through [TransactionHistoryViewModel.onEvent]. Uses [collectAsState] to match
 * Scope 1's `TransactionInputScreen` convention and avoid pulling in an extra lifecycle dependency.
 */
@Composable
fun HistoryScreen(
    viewModel: TransactionHistoryViewModel,
    modifier: Modifier = Modifier
) {
    val state: State<HistoryUiState> = viewModel.uiState.collectAsState()
    HistoryScreen(
        state = state.value,
        onEvent = viewModel::onEvent,
        modifier = modifier
    )
}
