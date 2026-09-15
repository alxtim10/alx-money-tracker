package com.alx.moneytracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.alx.moneytracker.data.local.entity.WalletEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `wallets` table.
 *
 * Observation is exposed as [Flow] so wallet balances stream to the UI in real time
 * (AGENTS.md Data Rule 2, Requirement 12.1): when a balance changes, Room re-emits automatically.
 *
 * The write/override/archive/default methods added for Scope 3 (Customization & Metadata) operate
 * only on the `wallets` table. None of them reference the `transactions` table: a
 * `Direct_Balance_Override` is a single-row balance UPDATE with no accompanying transaction insert
 * (AGENTS.md Data Rule 3, Requirement 4).
 */
@Dao
interface WalletDao {

    /** Emits the non-archived wallets available for selection (Requirement 4.2). */
    @Query("SELECT * FROM wallets WHERE is_archived = 0")
    fun observeActiveWallets(): Flow<List<WalletEntity>>

    /** Emits the default source wallet, or null when none is configured (Requirement 4.1). */
    @Query("SELECT * FROM wallets WHERE is_default = 1 LIMIT 1")
    fun observeDefaultWallet(): Flow<WalletEntity?>

    /** Inserts a new wallet, returning its generated id (Requirement 1.1). */
    @Insert
    suspend fun insert(wallet: WalletEntity): Long

    /** Renames the wallet identified by [id], leaving all other fields unchanged (Requirement 3.1). */
    @Query("UPDATE wallets SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    /**
     * Direct Balance Override: overwrites the balance of the wallet identified by [id] with
     * [balance]. This is a single-row balance UPDATE with **no** `transactions` insert — the
     * mechanical realization of AGENTS.md Data Rule 3 (Requirement 4.1, 4.3, 4.4).
     */
    @Query("UPDATE wallets SET balance = :balance WHERE id = :id")
    suspend fun overrideBalance(id: Long, balance: Long)

    /** Soft-deletes the wallet identified by [id] by setting `is_archived = 1` (Requirement 5.1). */
    @Query("UPDATE wallets SET is_archived = 1 WHERE id = :id")
    suspend fun markArchived(id: Long)

    /** Counts the non-archived wallets (used to resolve default-when-first, Requirement 1.5). */
    @Query("SELECT COUNT(*) FROM wallets WHERE is_archived = 0")
    suspend fun activeCount(): Int

    /** Returns the wallet identified by [id], or null when it does not exist. */
    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun findById(id: Long): WalletEntity?

    /** Clears the default flag on every wallet. */
    @Query("UPDATE wallets SET is_default = 0")
    suspend fun clearAllDefaults()

    /** Sets the default flag on the wallet identified by [id]. */
    @Query("UPDATE wallets SET is_default = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)

    /**
     * Atomically assigns the default wallet: clears all existing defaults, then sets [id] as the
     * single default. Runs inside one `@Transaction` so no intermediate state with zero or two
     * defaults is ever observable (AGENTS.md Data Rule 1, Requirement 6.1, 6.2).
     */
    @Transaction
    suspend fun assignDefault(id: Long) {
        clearAllDefaults()
        setDefault(id)
    }

    /**
     * Atomically archives the wallet identified by [id] and, when [replacementId] is non-null,
     * reassigns the default to that replacement (clearing all existing defaults first). Runs inside
     * one `@Transaction` so the exactly-one-default invariant never has an observable gap
     * (Requirement 5.1, 5.5, 5.6, 6.2).
     */
    @Transaction
    suspend fun archiveAndReassignDefault(id: Long, replacementId: Long?) {
        markArchived(id)
        if (replacementId != null) {
            clearAllDefaults()
            setDefault(replacementId)
        }
    }
}
