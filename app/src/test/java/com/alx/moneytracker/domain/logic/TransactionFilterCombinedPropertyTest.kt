package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.SyncStatus
import com.alx.moneytracker.domain.TimeFilter
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * Property-based test for [TransactionFilter.apply] (Task 3.2).
 *
 * Property 1 characterises the Filtered_Set as "OR within a dimension, AND across dimensions"
 * over all five filter dimensions. It is verified with model-based testing: an INDEPENDENT
 * brute-force reference predicate (deliberately not sharing any code with
 * [TransactionFilter]) recomputes the expected membership, and the two results must agree for
 * every generated (transaction-list, FilterSet) pair. The empty-FilterSet case is asserted
 * separately so that an inactive Filter_Set yields the input list unchanged.
 *
 * Generators keep ids and values in small ranges so every dimension — including the
 * source-OR-destination wallet rule and the inclusive/inverted time range — is exercised
 * frequently across the iterations. Note: Scope 1's [Transaction.isSynced] is a Boolean, so a
 * synced record maps to [SyncStatus.SYNCED] and an unsynced record to [SyncStatus.PENDING].
 *
 * Validates: Requirements 3.2, 3.3, 3.4, 4.5, 5.2, 5.3, 5.4, 6.3, 6.4, 6.5, 6.6, 7.4, 8.1,
 * 8.2, 8.3, 8.4
 */
class TransactionFilterCombinedPropertyTest : FunSpec({

    // Small, overlapping ranges so selections frequently hit and miss.
    val walletIds: Arb<Long> = Arb.long(1L..5L)
    val categoryIds: Arb<Long> = Arb.long(1L..5L)
    val timestamps: Arb<Long> = Arb.long(0L..20L)
    val amounts: Arb<Long> = Arb.long(0L..1_000L)
    val types: Arb<TransactionType> = Arb.of(TransactionType.entries)

    val transactions: Arb<Transaction> = Arb.bind(
        Arb.string(1..8),
        timestamps,
        types,
        amounts,
        walletIds,
        walletIds.orNull(0.4),
        categoryIds,
        Arb.boolean()
    ) { id, ts, type, amount, source, dest, category, synced ->
        Transaction(
            id = id,
            timestamp = ts,
            type = type,
            amount = amount,
            sourceWalletId = source,
            destWalletId = dest,
            categoryId = category,
            note = "",
            isSynced = synced
        )
    }

    // Each dimension is independently active (a selection) or inactive (empty / null).
    val filterSets: Arb<FilterSet> = Arb.bind(
        Arb.set(types, 0..3),
        Arb.set(walletIds, 0..5),
        Arb.set(categoryIds, 0..5),
        Arb.set(Arb.of(SyncStatus.entries), 0..2),
        // Include inverted ranges (start > end) so the degenerate case is covered.
        Arb.bind(timestamps, timestamps) { a, b -> TimeFilter(a, b) }.orNull(0.5)
    ) { types, wallets, cats, syncs, time ->
        FilterSet(
            types = types,
            walletIds = wallets,
            categoryIds = cats,
            syncStatuses = syncs,
            timeFilter = time
        )
    }

    /**
     * Independent brute-force reference predicate. Written from scratch against the acceptance
     * criteria; it must not call into [TransactionFilter] or [SyncStatusMapper].
     */
    fun referenceMatches(t: Transaction, f: FilterSet): Boolean {
        // Type dimension: OR over selected types; inactive when empty.
        val typeOk = f.types.isEmpty() || f.types.contains(t.type)

        // Wallet dimension: source OR destination is selected; inactive when empty.
        val walletOk = f.walletIds.isEmpty() ||
            f.walletIds.contains(t.sourceWalletId) ||
            (t.destWalletId != null && f.walletIds.contains(t.destWalletId))

        // Category dimension: OR over selected categories; inactive when empty.
        val categoryOk = f.categoryIds.isEmpty() || f.categoryIds.contains(t.categoryId)

        // Sync dimension: derive status independently; inactive when empty.
        val status = if (t.isSynced) SyncStatus.SYNCED else SyncStatus.PENDING
        val syncOk = f.syncStatuses.isEmpty() || f.syncStatuses.contains(status)

        // Time dimension: inclusive range; inverted range matches nothing; inactive when null.
        val timeOk = when (val range = f.timeFilter) {
            null -> true
            else ->
                if (range.startInclusive > range.endInclusive) false
                else t.timestamp >= range.startInclusive && t.timestamp <= range.endInclusive
        }

        // AND across active dimensions.
        return typeOk && walletOk && categoryOk && syncOk && timeOk
    }

    // Feature: transaction-history-audit, Property 1: Combined filter — OR within a dimension, AND across dimensions
    test("Property 1: Combined filter — OR within a dimension, AND across dimensions") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(transactions, 0..30),
            filterSets
        ) { txs, filters ->
            // Model-based check: apply() must equal the independent brute-force reference,
            // preserving input order.
            val expected = txs.filter { referenceMatches(it, filters) }
            TransactionFilter.apply(txs, filters) shouldBe expected

            // Empty FilterSet yields the input list unchanged (Requirement 8.4).
            TransactionFilter.apply(txs, FilterSet()) shouldBe txs
        }
    }
})
