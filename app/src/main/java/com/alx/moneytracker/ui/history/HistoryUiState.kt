package com.alx.moneytracker.ui.history

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.SummaryTotals
import com.alx.moneytracker.domain.Wallet

/**
 * The single immutable state rendered by the history screen (MVVM). It carries the already
 * filtered, sorted, and aggregated view of the Transactions_Store: the [rows] to display, the
 * [totals] for the Summary_Card, the active [filterSet] and [sortOrder] driving them, and the
 * [availableWallets]/[availableCategories] that source the filter chips.
 *
 * [isError] is set when a read from the Transactions_Store fails; in that case the last
 * successfully rendered [rows]/[totals] are retained so the UI can show an error indication
 * without discarding the prior list (Requirements 1.7, 12.4).
 *
 * @property rows The rendered Transaction_List entries (empty for the empty-state, Requirement 1.6).
 * @property totals The Summary_Card income/expense/net totals over the Filtered_Set (Requirement 10.1).
 * @property filterSet The active Filter_Set across all dimensions (Requirement 8).
 * @property sortOrder The active Sort_Order; defaults to [SortOrder.TIME_NEWEST_FIRST] (Requirement 9.2).
 * @property availableWallets Wallets available as filter chips (Requirement 4.1).
 * @property availableCategories Categories available as filter chips (Requirement 5.1).
 * @property isError True when the most recent read failed (Requirements 1.7, 12.4).
 */
data class HistoryUiState(
    val rows: List<TransactionRowUi> = emptyList(),
    val totals: SummaryTotals = SummaryTotals(),
    val filterSet: FilterSet = FilterSet(),
    val sortOrder: SortOrder = SortOrder.TIME_NEWEST_FIRST,
    val availableWallets: List<Wallet> = emptyList(),
    val availableCategories: List<Category> = emptyList(),
    val isError: Boolean = false
) {
    /** True when there are no rows to display (empty-state, Requirement 1.6). */
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        /** The initial state shown before the first emission arrives. */
        val Initial = HistoryUiState()
    }
}
