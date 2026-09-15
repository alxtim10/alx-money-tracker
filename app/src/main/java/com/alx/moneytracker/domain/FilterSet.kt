package com.alx.moneytracker.domain

/**
 * The active Filter_Set across the five filter dimensions Type, Wallet, Category, Sync_Status,
 * and Time.
 *
 * An empty set in a dimension means that dimension imposes no restriction (Requirements 3.2,
 * 4.5, 5.2, 6.2, 8.4); a null [timeFilter] means the Time dimension is inactive. Selections
 * within a dimension combine with OR; active dimensions combine with AND (Requirement 8).
 *
 * All defaults are inactive so that a freshly constructed [FilterSet] presents all Transaction
 * records (Requirement 8.4).
 *
 * @property types Selected [TransactionType] values, or empty when the Type dimension is inactive.
 * @property walletIds Selected wallet ids, or empty when the Wallet dimension is inactive.
 * @property categoryIds Selected category ids, or empty when the Category dimension is inactive.
 * @property syncStatuses Selected [SyncStatus] values, or empty when the Sync_Status dimension is
 *   inactive.
 * @property timeFilter The active [TimeFilter], or null when the Time dimension is inactive.
 */
data class FilterSet(
    val types: Set<TransactionType> = emptySet(),
    val walletIds: Set<Long> = emptySet(),
    val categoryIds: Set<Long> = emptySet(),
    val syncStatuses: Set<SyncStatus> = emptySet(),
    val timeFilter: TimeFilter? = null
) {
    /** True when no dimension is active, i.e. the Filter_Set imposes no restriction. */
    val isEmpty: Boolean
        get() = types.isEmpty() &&
            walletIds.isEmpty() &&
            categoryIds.isEmpty() &&
            syncStatuses.isEmpty() &&
            timeFilter == null
}
