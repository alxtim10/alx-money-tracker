package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.Transaction

/**
 * Pure sorting logic for the Transaction_List.
 *
 * Given a list of [Transaction] records and a [SortOrder], [sort] returns a new
 * list ordered according to that [SortOrder]. The object holds no Android
 * dependencies so it can be exercised directly on the JVM by unit and property
 * tests.
 *
 * The ordering is a **total order** with deterministic tie-breakers, so the same
 * input list and [SortOrder] always yield the same output regardless of the
 * input's initial arrangement (Requirements 9.3, 9.4, 9.5, 9.6):
 *
 * - [SortOrder.TIME_NEWEST_FIRST]: timestamp descending (Requirements 1.5, 9.3).
 * - [SortOrder.TIME_OLDEST_FIRST]: timestamp ascending (Requirement 9.4).
 * - [SortOrder.AMOUNT_LARGEST_FIRST]: amount descending, equal amounts broken by
 *   timestamp descending (Requirement 9.5).
 * - [SortOrder.AMOUNT_SMALLEST_FIRST]: amount ascending, equal amounts broken by
 *   timestamp descending (Requirement 9.6).
 *
 * The output is always a permutation of the input — no records are added or
 * removed — and an empty input yields an empty output (Requirements 9.7, 9.8).
 */
object TransactionSorter {

    /**
     * Orders [transactions] according to [order], returning a new list.
     *
     * The input list is not mutated. The result contains exactly the same
     * [Transaction] records with the same multiplicity (a permutation), ordered by
     * the total order described in the object documentation.
     *
     * Ties that remain after applying the primary key (and the amount orders'
     * timestamp tie-breaker) are broken by [Transaction.id] so the ordering is a
     * deterministic total order rather than merely stable with respect to input
     * position.
     *
     * @param transactions the records to order; may be empty.
     * @param order the [SortOrder] to apply.
     * @return a newly ordered list; empty when [transactions] is empty.
     */
    fun sort(transactions: List<Transaction>, order: SortOrder): List<Transaction> =
        transactions.sortedWith(comparatorFor(order))

    /**
     * Builds the total-order [Comparator] for [order].
     *
     * Every comparator ends with an [Transaction.id] tie-breaker so records that
     * are otherwise equal under the primary (and secondary) keys still have a
     * single, deterministic relative order.
     */
    private fun comparatorFor(order: SortOrder): Comparator<Transaction> =
        when (order) {
            SortOrder.TIME_NEWEST_FIRST ->
                compareByDescending<Transaction> { it.timestamp }
                    .thenBy { it.id }

            SortOrder.TIME_OLDEST_FIRST ->
                compareBy<Transaction> { it.timestamp }
                    .thenBy { it.id }

            SortOrder.AMOUNT_LARGEST_FIRST ->
                compareByDescending<Transaction> { it.amount }
                    .thenByDescending { it.timestamp }
                    .thenBy { it.id }

            SortOrder.AMOUNT_SMALLEST_FIRST ->
                compareBy<Transaction> { it.amount }
                    .thenByDescending { it.timestamp }
                    .thenBy { it.id }
        }
}
