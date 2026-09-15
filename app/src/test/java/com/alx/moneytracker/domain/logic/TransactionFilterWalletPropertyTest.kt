package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based test for the wallet dimension of [TransactionFilter] (Task 3.3).
 *
 * Exactly one property test covering Property 2, using kotest-property with a minimum
 * of 100 iterations. Generates arbitrary transaction lists and non-empty selected
 * wallet-id sets with all other dimensions inactive, so the assertion isolates the
 * source-OR-destination wallet matching behaviour.
 *
 * Wallet ids are drawn from a small pool so that generated transactions and selected
 * wallet-id sets overlap frequently, exercising both matching and non-matching records
 * (including TRANSFER records whose destination wallet is the one that matches).
 *
 * Validates: Requirements 4.2, 4.4
 */
class TransactionFilterWalletPropertyTest : FunSpec({

    // A small wallet-id pool so selections and transaction wallets overlap often.
    val walletIds: Arb<Long> = Arb.long(0L..8L)

    // Transactions across all types; destination wallet is drawn from the same small
    // pool (kept even for non-TRANSFER records) so the source-OR-destination predicate
    // is exercised regardless of type.
    val transactions: Arb<Transaction> = Arb.bind(
        Arb.string(1..12),
        Arb.long(0L..1_000_000L),
        Arb.enum<TransactionType>(),
        Arb.long(0L..1_000_000_000L),
        walletIds,
        walletIds,
        Arb.long(0L..20L),
        Arb.boolean()
    ) { id, timestamp, type, amount, source, dest, categoryId, isSynced ->
        Transaction(
            id = id,
            timestamp = timestamp,
            type = type,
            amount = amount,
            sourceWalletId = source,
            destWalletId = dest,
            categoryId = categoryId,
            note = "",
            isSynced = isSynced
        )
    }

    val transactionLists: Arb<List<Transaction>> = Arb.list(transactions, 0..30)

    // Non-empty selected wallet-id sets, drawn from the same pool as the transactions.
    val selectedWalletSets: Arb<Set<Long>> = Arb.set(walletIds, 1..8)

    // Feature: transaction-history-audit, Property 2: Wallet filter matches source or destination
    test("Property 2: Wallet filter matches source or destination") {
        checkAll(
            PropTestConfig(iterations = 100),
            transactionLists,
            selectedWalletSets
        ) { list, selected ->
            // All other dimensions inactive: only the wallet dimension restricts.
            val filters = FilterSet(walletIds = selected)

            val actual = TransactionFilter.apply(list, filters)

            // Reference: exactly the records whose source OR destination wallet is selected.
            val expected = list.filter { tx ->
                tx.sourceWalletId in selected || tx.destWalletId in selected
            }

            actual shouldBe expected
        }
    }
})
