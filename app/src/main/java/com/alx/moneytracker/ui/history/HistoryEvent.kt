package com.alx.moneytracker.ui.history

import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.SyncStatus
import com.alx.moneytracker.domain.TransactionType

/**
 * User actions dispatched from the history UI to [TransactionHistoryViewModel].
 *
 * Each chip-toggle event flips membership of the corresponding selection inside the
 * `FilterSet` (adding on activate, removing on deactivate — Requirements 3.4, 4.3, 5.4,
 * 6.6). [TimeRangeSelected] sets the `TimeFilter`; [TimeFilterCleared] removes it
 * (Requirement 7.4). [SortSelected] replaces the single active `SortOrder` while leaving
 * the `FilterSet` untouched (Requirement 9.7).
 */
sealed interface HistoryEvent {

    /** Toggles the given [TransactionType] in the type dimension (Requirement 3.4). */
    data class TypeChipToggled(val type: TransactionType) : HistoryEvent

    /** Toggles the given wallet id in the wallet dimension (Requirement 4.3). */
    data class WalletChipToggled(val walletId: Long) : HistoryEvent

    /** Toggles the given category id in the category dimension (Requirement 5.4). */
    data class CategoryChipToggled(val categoryId: Long) : HistoryEvent

    /** Toggles the given [SyncStatus] (SYNCED or PENDING) in the sync dimension (Requirement 6.6). */
    data class SyncChipToggled(val status: SyncStatus) : HistoryEvent

    /** Activates the Time_Filter with an inclusive epoch-millis range (Requirement 7.2). */
    data class TimeRangeSelected(val startInclusive: Long, val endInclusive: Long) : HistoryEvent

    /** Deactivates the Time_Filter (Requirement 7.4). */
    data object TimeFilterCleared : HistoryEvent

    /** Replaces the active [SortOrder], preserving the Filter_Set (Requirement 9.7). */
    data class SortSelected(val order: SortOrder) : HistoryEvent
}
