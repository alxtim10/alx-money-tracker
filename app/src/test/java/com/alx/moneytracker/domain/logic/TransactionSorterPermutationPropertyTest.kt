package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
 * Property-based test for [TransactionSorter] permutation invariance (Task 4.3).
 *
 * Exactly one property test covering Property 5, using kotest-property with a minimum
 * of 100 iterations. Sorting must neither add nor remove records: the output is a
 * multiset permutation of the input — the same records with the same multiplicity —
 * regardless of the chosen [SortOrder]. An empty input sorts to an empty output
 * (Requirements 9.7, 9.8).
 *
 * The multiset check groups both the input and the sorted output by the full
 * [Transaction] value and counts occurrences, so duplicate records (same value
 * appearing more than once) must be preserved with identical multiplicity. Timestamps,
 * amounts, and ids are drawn from intentionally small ranges so duplicate records occur
 * frequently within a single generated list, genuinely exercising the multiplicity
 * requirement rather than merely a set-equality check.
 *
 * Validates: Requirements 9.7, 9.8
 */
class TransactionSorterPermutationPropertyTest : FunSpec({

    val types: Arb<TransactionType> = Arb.of(TransactionType.entries)

    // Transactions drawn from small ranges so that identical records (same value across
    // all fields) recur within a list, exercising multiset multiplicity — not just set
    // membership.
    val transactions: Arb<Transaction> = Arb.bind(
        Arb.string(1..3),        // id — tiny range => identical records recur
        Arb.long(0L..3L),        // timestamp — tiny range => frequent duplicates
        types,
        Arb.long(0L..3L),        // amount — tiny range => frequent duplicates
        Arb.long(1L..3L),        // sourceWalletId
        Arb.boolean()            // isSynced
    ) { id, timestamp, type, amount, sourceWalletId, isSynced ->
        Transaction(
            id = id,
            timestamp = timestamp,
            type = type,
            amount = amount,
            destWalletId = if (type == TransactionType.TRANSFER) sourceWalletId + 1 else null,
            sourceWalletId = sourceWalletId,
            categoryId = 1L,
            note = "",
            isSynced = isSynced
        )
    }

    val orders: Arb<SortOrder> = Arb.of(SortOrder.entries)

    // Feature: transaction-history-audit, Property 5: Sorting is a permutation of its input
    test("Property 5: Sorting is a permutation of its input") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(transactions, 0..30),
            orders
        ) { list, order ->
            val sorted = TransactionSorter.sort(list, order)

            // Same number of records — nothing added or removed.
            sorted.size shouldBe list.size

            // Multiset equality: identical records with identical multiplicity.
            sorted.groupingBy { it }.eachCount() shouldBe list.groupingBy { it }.eachCount()

            // An empty input sorts to an empty output (Requirement 9.8).
            if (list.isEmpty()) {
                sorted.shouldBeEmpty()
            }
        }
    }
})
