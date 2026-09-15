package com.alx.moneytracker.domain

/**
 * Aggregate totals computed over the Filtered_Set for the Summary_Card.
 *
 * TRANSFER transactions are excluded from all totals (Requirement 10.4). Totals are expressed
 * as `Long` values in the smallest currency unit.
 *
 * @property incomeTotal Sum of INCOME transaction amounts (Requirement 10.2).
 * @property expenseTotal Sum of EXPENSE transaction amounts (Requirement 10.2).
 */
data class SummaryTotals(
    val incomeTotal: Long = 0L,
    val expenseTotal: Long = 0L
) {
    /** Net total: income minus expense (Requirement 10.3). */
    val netTotal: Long
        get() = incomeTotal - expenseTotal
}
