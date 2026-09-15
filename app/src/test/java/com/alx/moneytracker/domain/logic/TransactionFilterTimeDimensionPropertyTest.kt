package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.TimeFilter
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

/**
 * Property-based test for the Time dimension of [TransactionFilter] (Task 3.4).
 *
 * Implemented as exactly one property-based test using kotest-property, running a minimum of
 * 100 iterations. All dimensions other than Time are left inactive so the assertion isolates the
 * inclusive-range behavior and the inverted-range (`start > end`) rejection.
 *
 * Validates: Requirements 7.2, 7.3
 */
class TransactionFilterTimeDimensionPropertyTest : FunSpec({

    // Timestamps span a bounded window so generated ranges frequently straddle the data,
    // producing meaningful boundary hits (t == start, t == end) as well as misses.
    val timestamps: Arb<Long> = Arb.long(0L..1_000L)

    val transactions: Arb<Transaction> = timestamps.map { ts ->
        Transaction(
            id = "tx-$ts",
            timestamp = ts,
            type = TransactionType.EXPENSE,
            amount = 1L,
            sourceWalletId = 1L,
            destWalletId = null,
            categoryId = 1L,
            note = "",
            isSynced = false
        )
    }

    // Range bounds drawn from the same window; the two bounds are independent so start may be
    // <=, ==, or > end, exercising both the valid and inverted cases.
    val bounds: Arb<Long> = Arb.long(0L..1_000L)

    // Feature: transaction-history-audit, Property 3: Time filter is an inclusive range and rejects inverted ranges
    test("Property 3: Time filter is an inclusive range and rejects inverted ranges") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(transactions, 0..20),
            bounds,
            bounds
        ) { list, start, end ->
            val filters = FilterSet(timeFilter = TimeFilter(startInclusive = start, endInclusive = end))

            val actual = TransactionFilter.apply(list, filters)

            val expected = if (start > end) {
                // Inverted range matches nothing (Requirement 7.3).
                emptyList()
            } else {
                // Inclusive range: start <= t <= end (Requirement 7.2).
                list.filter { it.timestamp in start..end }
            }

            actual shouldBe expected
        }
    }
})
