package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
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
 * Property-based test for [TransactionSorter] ordering (Task 4.2).
 *
 * Exactly one property test covering Property 4, using kotest-property with a minimum
 * of 100 iterations. The generators deliberately draw timestamps and amounts from small
 * ranges so that duplicate timestamps and duplicate amounts occur frequently within a
 * single generated list, exercising the amount-tie timestamp tie-breaker for the two
 * amount orders (Requirements 9.5, 9.6).
 *
 * The property is checked by asserting the ordering relation on every adjacent pair of
 * the sorted output for each of the four [SortOrder] values:
 *
 * - [SortOrder.TIME_NEWEST_FIRST]: non-increasing timestamps (Requirements 1.5, 9.3).
 * - [SortOrder.TIME_OLDEST_FIRST]: non-decreasing timestamps (Requirement 9.4).
 * - [SortOrder.AMOUNT_LARGEST_FIRST]: non-increasing amounts, equal amounts broken by
 *   non-increasing timestamps (Requirement 9.5).
 * - [SortOrder.AMOUNT_SMALLEST_FIRST]: non-decreasing amounts, equal amounts broken by
 *   non-increasing timestamps (Requirement 9.6).
 *
 * Validates: Requirements 1.5, 9.3, 9.4, 9.5, 9.6
 */
class TransactionSorterOrderingPropertyTest : FunSpec({

    val types: Arb<TransactionType> = Arb.of(TransactionType.entries)

    // Transactions whose timestamps and amounts are drawn from intentionally small
    // ranges so duplicates appear often within a list, exercising the tie-breakers.
    val transactions: Arb<Transaction> = Arb.bind(
        Arb.string(4..8),        // id — varies so the deterministic id tie-breaker is total
        Arb.long(0L..5L),        // timestamp — tiny range => frequent duplicates
        types,
        Arb.long(0L..5L),        // amount — tiny range => frequent duplicates
        Arb.long(1L..4L),        // sourceWalletId
        Arb.boolean()            // isSynced
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

    val orders: Arb<SortOrder> = Arb.of(SortOrder.entries)

    // Feature: transaction-history-audit, Property 4: Sort order is deterministic with defined tie-breakers
    test("Property 4: Sort order is deterministic with defined tie-breakers") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(transactions, 0..30),
            orders
        ) { list, order ->
            val sorted = TransactionSorter.sort(list, order)

            // Every adjacent pair must respect the order relation for the chosen SortOrder.
            sorted.zipWithNext().all { (a, b) ->
                when (order) {
                    // Non-increasing timestamps.
                    SortOrder.TIME_NEWEST_FIRST -> a.timestamp >= b.timestamp
                    // Non-decreasing timestamps.
                    SortOrder.TIME_OLDEST_FIRST -> a.timestamp <= b.timestamp
                    // Non-increasing amounts; equal amounts broken by non-increasing timestamps.
                    SortOrder.AMOUNT_LARGEST_FIRST ->
                        a.amount > b.amount ||
                            (a.amount == b.amount && a.timestamp >= b.timestamp)
                    // Non-decreasing amounts; equal amounts broken by non-increasing timestamps.
                    SortOrder.AMOUNT_SMALLEST_FIRST ->
                        a.amount < b.amount ||
                            (a.amount == b.amount && a.timestamp >= b.timestamp)
                }
            }.shouldBeTrue()
        }
    }
})
