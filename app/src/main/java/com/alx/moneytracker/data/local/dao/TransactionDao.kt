package com.alx.moneytracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.alx.moneytracker.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `transactions` table plus the atomic insert-log + apply-balance operation.
 *
 * [insertAndApplyBalances] is the single atomic boundary for persistence (AGENTS.md Data Rule 1,
 * Requirement 9): the transaction insert and the wallet balance updates are executed inside one
 * `@Transaction`, so Room commits them together or rolls both back on failure (Requirement 9.5).
 */
@Dao
interface TransactionDao {

    /** Inserts a single transaction log row. */
    @Insert
    suspend fun insert(tx: TransactionEntity)

    /**
     * Emits every transaction as a reactive [Flow], re-emitting on any change to the
     * `transactions` table (AGENTS.md Data Rule 2, Requirement 2.1).
     *
     * No ordering or filtering is applied in SQL — Scope 2's History_Module performs filtering,
     * sorting, and aggregation as pure in-memory transforms over this full stream.
     */
    @Query("SELECT * FROM transactions")
    fun observeAll(): Flow<List<TransactionEntity>>

    /**
     * Applies a signed [delta] to the balance of the wallet identified by [walletId].
     * A negative delta decreases the balance; a positive delta increases it.
     */
    @Query("UPDATE wallets SET balance = balance + :delta WHERE id = :walletId")
    suspend fun applyBalanceDelta(walletId: Long, delta: Long)

    /**
     * Atomically inserts the transaction log and applies the balance change(s) (Requirement 9.1).
     *
     * - Always applies [sourceDelta] to [sourceWalletId] (EXPENSE `-amount`, INCOME `+amount`,
     *   TRANSFER `-amount`).
     * - When [destWalletId] is non-null (TRANSFER), also applies [destDelta] to it (`+amount`),
     *   satisfying Requirement 9.4.
     *
     * If any step throws, Room rolls back the entire transaction, leaving both stores unchanged
     * (Requirement 9.5).
     */
    @Transaction
    suspend fun insertAndApplyBalances(
        tx: TransactionEntity,
        sourceWalletId: Long,
        sourceDelta: Long,
        destWalletId: Long?,
        destDelta: Long
    ) {
        insert(tx)
        applyBalanceDelta(sourceWalletId, sourceDelta)
        if (destWalletId != null) applyBalanceDelta(destWalletId, destDelta)
    }
}
