package com.alx.moneytracker.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.alx.moneytracker.data.local.entity.WalletEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `wallets` table.
 *
 * Observation is exposed as [Flow] so wallet balances stream to the UI in real time
 * (AGENTS.md Data Rule 2, Requirement 12.1): when a balance changes, Room re-emits automatically.
 */
@Dao
interface WalletDao {

    /** Emits the non-archived wallets available for selection (Requirement 4.2). */
    @Query("SELECT * FROM wallets WHERE is_archived = 0")
    fun observeActiveWallets(): Flow<List<WalletEntity>>

    /** Emits the default source wallet, or null when none is configured (Requirement 4.1). */
    @Query("SELECT * FROM wallets WHERE is_default = 1 LIMIT 1")
    fun observeDefaultWallet(): Flow<WalletEntity?>
}
