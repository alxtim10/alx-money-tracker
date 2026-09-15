package com.alx.moneytracker.data.repository

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.Wallet
import kotlinx.coroutines.flow.Flow

/**
 * Read-only boundary between the History ViewModel and the local Room data layer (Scope 2).
 *
 * This interface deliberately exposes **only** `observe*` methods and no write, override, or delete
 * operation, so the read-only invariant (Requirement 12) holds by construction — the type itself
 * makes it impossible for the History_Module to mutate the Transactions_Store.
 *
 * All observation is exposed as domain-model [Flow]s so the Transaction_List, wallet chips, and
 * category chips stream to the UI in real time (AGENTS.md Data Rule 2, Requirements 2.1, 4.1, 5.1).
 * The transactions stream is wrapped in [Result] so a read failure surfaces as [Result.failure]
 * without crashing the stream, letting the UI preserve the previously displayed list and show an
 * error indication (Requirements 1.7, 12.4).
 */
interface HistoryRepository {

    /**
     * Emits the full set of transactions on every change to the `transactions` table, subject to
     * no SQL ordering or filtering — filtering, sorting, and aggregation are pure in-memory
     * transforms performed downstream (Requirements 1.1, 2.1).
     *
     * Each emission is wrapped in a [Result]: [Result.success] carries the current list, while a
     * read failure is mapped to [Result.failure] so the stream stays alive (Requirements 1.7, 12.4).
     */
    fun observeTransactions(): Flow<Result<List<Transaction>>>

    /** Emits the non-archived wallets available for the wallet filter chips (Requirement 4.1). */
    fun observeWallets(): Flow<List<Wallet>>

    /** Emits the non-archived categories available for the category filter chips (Requirement 5.1). */
    fun observeCategories(): Flow<List<Category>>
}
