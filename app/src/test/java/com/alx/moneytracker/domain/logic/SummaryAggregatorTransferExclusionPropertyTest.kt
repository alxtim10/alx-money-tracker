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
 * Property-based test for [SummaryAggregator] TRANSFER exclusion (Task 5.3).
 *
 * Exactly one property test covering Property 7, using kotest-property with a minimum
 * of 100 iterations. This is a metamorphic test: it generates a base transaction list
 * (drawn across all three types) and an arbitrary list of TRANSFER records, then asserts
 * that interleaving the TRANSFERs into the base list leaves the income total, the expense
 * total, and the net total unchanged (Requirement 10.4).
 *
 * The base generator draws all three [TransactionType] values so the invariant is shown to
 * hold regardless of how many INCOME/EXPENSE records already contribute to the totals, and
 * regardless of the arbitrary amounts carried by the added TRANSFERs.
 *
 * Validates: Requirements 10.4
 */
class SummaryAggregatorTransferExclusionPropertyTest : FunSpec({

    val allTypes: Arb<TransactionType> = Arb.of(TransactionType.entries)

    // Base transactions of any type, with amounts kept modest so many can be summed
    // without the totals overflowing a Long across a generated list.
    val baseTransactions: Arb<Transaction> = Arb.bind(
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

    // TRANSFER-only records with arbitrary amounts; these must never move the totals.
    val transferTransactions: Arb<Transaction> = Arb.bind(
        Arb.string(4..8),
        Arb.long(0L..1_000_000L),
        Arb.long(0L..1_000_000L),
        Arb.long(1L..4L),
        Arb.boolean()
    ) { id, timestamp, amount, sourceWalletId, isSynced ->
        Transaction(
            id = id,
            timestamp = timestamp,
            type = TransactionType.TRANSFER,
            amount = amount,
            sourceWalletId = sourceWalletId,
            destWalletId = sourceWalletId + 1,
            categoryId = 1L,
            note = "",
            isSynced = isSynced
        )
    }

    // Feature: transaction-history-audit, Property 7: Transfers do not affect the summary totals
    test("Property 7: Transfers do not affect the summary totals") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(baseTransactions, 0..30),
            Arb.list(transferTransactions, 0..30)
        ) { base, transfers ->
            val before = SummaryAggregator.aggregate(base)

            // Interleave the TRANSFERs into the base list (append covers the general case;
            // aggregation is order-independent so position does not matter).
            val withTransfers = base + transfers
            val after = SummaryAggregator.aggregate(withTransfers)

            after.incomeTotal shouldBe before.incomeTotal
            after.expenseTotal shouldBe before.expenseTotal
            after.netTotal shouldBe before.netTotal
        }
    }
})
