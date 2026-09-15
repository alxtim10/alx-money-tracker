package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based test for [SummaryAggregator] totals (Task 5.2).
 *
 * Exactly one property test covering Property 6, using kotest-property with a minimum
 * of 100 iterations. For an arbitrary transaction list the income total must equal the
 * sum of every INCOME amount, the expense total must equal the sum of every EXPENSE
 * amount, and the net total must equal income minus expense (Requirements 10.2, 10.3).
 * The empty list is covered directly because the generated list size includes zero, and
 * is also asserted explicitly to pin down the zero-totals guarantee (Requirement 10.7).
 *
 * The expected totals are computed by an independent reference (filter-then-sum) that does
 * not call [SummaryAggregator], so the test cross-checks the implementation against a
 * separate computation rather than restating it. Amounts are kept modest so summing a full
 * generated list cannot overflow a [Long].
 *
 * Validates: Requirements 10.2, 10.3, 10.7
 */
class SummaryAggregatorTotalsPropertyTest : FunSpec({

    val allTypes: Arb<TransactionType> = Arb.of(TransactionType.entries)

    val transactions: Arb<Transaction> = Arb.bind(
        Arb.string(4..8),
        Arb.long(0L..1_000_000L),
        allTypes,
        Arb.long(0L..1_000_000L),
        Arb.long(1L..4L),
        Arb.boolean()
    ) { id, timestamp, type, amount, sourceWalletId, isSynced ->
        Transaction(
            id = id,
            timestamp = timestamp,
            type = type,
            amount = amount,
            sourceWalletId = sourceWalletId,
            destWalletId = if (type == TransactionType.TRANSFER) sourceWalletId + 1 else null,
            categoryId = 1L,
            note = "",
            isSynced = isSynced
        )
    }

    // Feature: transaction-history-audit, Property 6: Summary totals sum the filtered set by type
    test("Property 6: Summary totals sum the filtered set by type") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(transactions, 0..30)
        ) { list ->
            val totals = SummaryAggregator.aggregate(list)

            // Independent reference computation: filter by type, then sum the amounts.
            val expectedIncome = list
                .filter { it.type == TransactionType.INCOME }
                .sumOf { it.amount }
            val expectedExpense = list
                .filter { it.type == TransactionType.EXPENSE }
                .sumOf { it.amount }

            totals.incomeTotal shouldBe expectedIncome
            totals.expenseTotal shouldBe expectedExpense
            totals.netTotal shouldBe (expectedIncome - expectedExpense)

            // Zero-totals guarantee for the empty Filtered_Set (Requirement 10.7).
            if (list.isEmpty()) {
                totals.incomeTotal shouldBe 0L
                totals.expenseTotal shouldBe 0L
                totals.netTotal shouldBe 0L
            }
        }
    }
})
