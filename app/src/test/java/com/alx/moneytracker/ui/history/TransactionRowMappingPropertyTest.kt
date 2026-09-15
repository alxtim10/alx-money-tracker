package com.alx.moneytracker.ui.history

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based test for the row mapper [toRows] (Task 6.2).
 *
 * Exactly one property test covering Property 10, using kotest-property with a minimum
 * of 100 iterations. Generates arbitrary transaction lists together with wallet and
 * category lookups, then asserts each produced [TransactionRowUi] faithfully reflects
 * its originating [Transaction]: same id, type, amount, source wallet name, category
 * name, and timestamp, in the same order and with the same size as the input.
 *
 * The core guarantee under test is that the destination wallet name is present exactly
 * when the transaction's type is [TransactionType.TRANSFER] and absent otherwise
 * (Requirement 1.3). Wallet and category ids are drawn from small pools that the
 * generated lookups mostly cover, so names resolve for the common case; the lookups
 * occasionally miss an id, which exercises the placeholder fallback without being the
 * focus of the assertions.
 *
 * The expected wallet/category names are computed by an independent reference lookup
 * (with the same placeholder fallback) that does not call [toRows], so the test
 * cross-checks the mapper against a separate computation rather than restating it.
 *
 * Validates: Requirements 1.2, 1.3
 */
class TransactionRowMappingPropertyTest : FunSpec({

    // Small id pools so generated lookups mostly cover the referenced ids (names resolve),
    // while still occasionally missing one (exercising the placeholder fallback).
    val walletIds: Arb<Long> = Arb.long(0L..6L)
    val categoryIds: Arb<Long> = Arb.long(0L..6L)

    val transactions: Arb<Transaction> = Arb.bind(
        Arb.string(1..12),
        Arb.long(0L..1_000_000_000L),
        Arb.enum<TransactionType>(),
        Arb.long(0L..1_000_000L),
        walletIds,
        walletIds,
        categoryIds,
        Arb.boolean()
    ) { id, timestamp, type, amount, source, dest, categoryId, isSynced ->
        Transaction(
            id = id,
            timestamp = timestamp,
            type = type,
            amount = amount,
            sourceWalletId = source,
            // Keep a destination id even for non-TRANSFER records so the mapper is forced
            // to null it out based on type rather than on a missing id.
            destWalletId = dest,
            categoryId = categoryId,
            note = "",
            isSynced = isSynced
        )
    }

    val wallets: Arb<Wallet> = Arb.bind(
        walletIds,
        Arb.string(1..10),
        Arb.long(0L..1_000_000L),
        Arb.boolean(),
        Arb.boolean()
    ) { id, name, balance, isDefault, isArchived ->
        Wallet(id = id, name = name, balance = balance, isDefault = isDefault, isArchived = isArchived)
    }

    val categories: Arb<Category> = Arb.bind(
        categoryIds,
        Arb.string(1..10),
        Arb.enum<TransactionType>(),
        Arb.string(1..6),
        Arb.boolean()
    ) { id, name, type, icon, isArchived ->
        Category(id = id, name = name, type = type, icon = icon, isArchived = isArchived)
    }

    // Feature: transaction-history-audit, Property 10: A rendered row faithfully reflects its transaction
    test("Property 10: A rendered row faithfully reflects its transaction") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(transactions, 0..30),
            Arb.list(wallets, 0..7),
            Arb.list(categories, 0..7)
        ) { txList, walletList, categoryList ->
            val rows = txList.toRows(walletList, categoryList)

            // Independent reference lookups with the same placeholder fallback semantics.
            // last-wins mirrors associateBy's behaviour when ids collide in the generated lists.
            val walletNames = walletList.associate { it.id to it.name }
            val categoryNames = categoryList.associate { it.id to it.name }

            // Order-preserving and size-preserving projection.
            rows.size shouldBe txList.size

            rows.zip(txList).forEach { (row, tx) ->
                row.id shouldBe tx.id
                row.type shouldBe tx.type
                row.amount shouldBe tx.amount
                row.timestamp shouldBe tx.timestamp

                // Source wallet name reflects the transaction's source wallet (Requirement 1.2).
                row.sourceWalletName shouldBe (walletNames[tx.sourceWalletId] ?: UNKNOWN_WALLET_LABEL)

                // Category name reflects the transaction's category (Requirement 1.2).
                row.categoryName shouldBe (categoryNames[tx.categoryId] ?: UNKNOWN_CATEGORY_LABEL)

                // Destination wallet present exactly for TRANSFER (Requirement 1.3).
                if (tx.type == TransactionType.TRANSFER) {
                    row.destWalletName.shouldNotBeNull()
                    row.destWalletName shouldBe (walletNames[tx.destWalletId] ?: UNKNOWN_WALLET_LABEL)
                } else {
                    row.destWalletName.shouldBeNull()
                }
            }
        }
    }
})
