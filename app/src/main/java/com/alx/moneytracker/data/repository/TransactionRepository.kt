package com.alx.moneytracker.data.repository

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import kotlinx.coroutines.flow.Flow

/**
 * Boundary between the ViewModel and the local Room data layer for the Transaction Input Engine.
 *
 * All observation is exposed as domain-model [Flow]s so wallet balances, categories, and presets
 * stream to the UI in real time (AGENTS.md Data Rule 2, Requirement 12.1). Persistence is a single
 * suspend operation that never surfaces raw exceptions: the atomic write result is wrapped in a
 * [Result] so the ViewModel can react to success or failure without a try/catch (Requirement 9.5).
 */
interface TransactionRepository {

    /** Emits the non-archived wallets available for selection (Requirements 4.2, 12.1). */
    fun observeWallets(): Flow<List<Wallet>>

    /**
     * Emits the non-archived categories whose type matches [type] (Requirements 5.2, 5.3, 12.1).
     */
    fun observeCategories(type: TransactionType): Flow<List<Category>>

    /** Emits the configured preset chip set ordered by amount (Requirements 2.1, 12.1). */
    fun observeQuickPresets(): Flow<List<QuickPreset>>

    /** Emits the default source wallet, or null when none is configured (Requirement 4.1). */
    fun observeDefaultWallet(): Flow<Wallet?>

    /**
     * Persists [transaction] and applies the corresponding wallet balance change(s) within a single
     * atomic write (Requirement 9.1). The balance deltas are derived from [Transaction.type]:
     * EXPENSE decreases the source balance, INCOME increases it, and TRANSFER decreases the source
     * while increasing the destination (Requirements 9.2, 9.3, 9.4).
     *
     * Returns [Result.success] on commit; on any failure the underlying atomic write is rolled back
     * and [Result.failure] carries the cause (Requirement 9.5).
     */
    suspend fun saveTransaction(transaction: Transaction): Result<Unit>
}
