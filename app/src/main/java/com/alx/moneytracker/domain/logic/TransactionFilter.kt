package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.Transaction

/**
 * Pure filter logic that computes the Filtered_Set from a list of [Transaction] records and a
 * [FilterSet].
 *
 * Selections within a single dimension combine with OR; active dimensions combine with AND
 * (Requirement 8). A dimension with no selection (an empty set, or a null time filter) imposes
 * no restriction, so an empty [FilterSet] leaves the input unchanged (Requirements 3.2, 4.5,
 * 5.2, 6.2 via [SyncStatusMapper], 7.4, 8.4).
 *
 * This object holds no Android dependencies so it can be exercised directly on the JVM by unit
 * and property tests.
 */
object TransactionFilter {

    /**
     * Returns the Filtered_Set: the [transactions] that satisfy [filters].
     *
     * A [Transaction] is included if and only if it matches every active dimension (AND across
     * dimensions), where matching a dimension means matching at least one of its selections (OR
     * within a dimension). When [filters] is empty every dimension is inactive, so the input
     * list is returned unchanged (Requirements 8.1, 8.2, 8.3, 8.4).
     *
     * @param transactions the records to filter.
     * @param filters the active [FilterSet].
     * @return the records satisfying [filters], preserving input order.
     */
    fun apply(transactions: List<Transaction>, filters: FilterSet): List<Transaction> {
        if (filters.isEmpty) return transactions
        return transactions.filter { matches(it, filters) }
    }

    /**
     * Whether [transaction] satisfies [filters].
     *
     * Composes five per-dimension predicates, each of which is vacuously true when its dimension
     * is inactive:
     * - Type: `transaction.type` is among the selected types (Requirements 3.2, 3.3, 3.4).
     * - Wallet: the source OR destination wallet is among the selected wallets (Requirements 4.2,
     *   4.4, 4.5).
     * - Category: `transaction.categoryId` is among the selected categories (Requirements 5.2,
     *   5.3, 5.4).
     * - Sync: the mapped [com.alx.moneytracker.domain.SyncStatus] is among the selected statuses
     *   (Requirements 6.3, 6.4, 6.5, 6.6).
     * - Time: the timestamp falls within the inclusive range; an inverted range (`start > end`)
     *   matches nothing (Requirements 7.2, 7.3, 7.4).
     *
     * @param transaction the record to test.
     * @param filters the active [FilterSet].
     * @return true when [transaction] matches every active dimension.
     */
    fun matches(transaction: Transaction, filters: FilterSet): Boolean =
        matchesType(transaction, filters) &&
            matchesWallet(transaction, filters) &&
            matchesCategory(transaction, filters) &&
            matchesSync(transaction, filters) &&
            matchesTime(transaction, filters)

    private fun matchesType(transaction: Transaction, filters: FilterSet): Boolean =
        filters.types.isEmpty() || transaction.type in filters.types

    private fun matchesWallet(transaction: Transaction, filters: FilterSet): Boolean =
        filters.walletIds.isEmpty() ||
            transaction.sourceWalletId in filters.walletIds ||
            transaction.destWalletId in filters.walletIds

    private fun matchesCategory(transaction: Transaction, filters: FilterSet): Boolean =
        filters.categoryIds.isEmpty() || transaction.categoryId in filters.categoryIds

    private fun matchesSync(transaction: Transaction, filters: FilterSet): Boolean {
        if (filters.syncStatuses.isEmpty()) return true
        val status = SyncStatusMapper.statusOf(if (transaction.isSynced) 1 else 0)
        return status in filters.syncStatuses
    }

    private fun matchesTime(transaction: Transaction, filters: FilterSet): Boolean {
        val range = filters.timeFilter ?: return true
        // An inverted range (start > end) matches nothing (Requirement 7.3).
        if (range.startInclusive > range.endInclusive) return false
        return transaction.timestamp >= range.startInclusive &&
            transaction.timestamp <= range.endInclusive
    }
}
