package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.SummaryTotals
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType

/**
 * Pure aggregation logic for the Summary_Card.
 *
 * Computes the income, expense, and net totals over a Filtered_Set. INCOME and
 * EXPENSE amounts are summed separately, the net total is income minus expense,
 * and TRANSFER transactions are excluded from every total (Requirements 10.2,
 * 10.3, 10.4). An empty input yields zero for all totals (Requirement 10.7).
 *
 * All amounts are non-negative [Long] values in the smallest currency unit. This
 * object holds no Android dependencies so it can be exercised directly on the JVM
 * by unit and property tests.
 */
object SummaryAggregator {

    /**
     * Sums the [transactions] by [TransactionType] into a [SummaryTotals].
     *
     * INCOME amounts contribute to [SummaryTotals.incomeTotal], EXPENSE amounts to
     * [SummaryTotals.expenseTotal], and TRANSFER records are ignored entirely
     * (Requirement 10.4). The net total is derived by [SummaryTotals.netTotal] as
     * income minus expense (Requirement 10.3). An empty list produces zero totals
     * (Requirement 10.7).
     *
     * @param transactions the Filtered_Set to aggregate.
     * @return the income, expense, and net totals over [transactions].
     */
    fun aggregate(transactions: List<Transaction>): SummaryTotals {
        var incomeTotal = 0L
        var expenseTotal = 0L
        for (transaction in transactions) {
            when (transaction.type) {
                TransactionType.INCOME -> incomeTotal += transaction.amount
                TransactionType.EXPENSE -> expenseTotal += transaction.amount
                TransactionType.TRANSFER -> Unit // excluded from all totals (Requirement 10.4)
            }
        }
        return SummaryTotals(incomeTotal = incomeTotal, expenseTotal = expenseTotal)
    }
}
