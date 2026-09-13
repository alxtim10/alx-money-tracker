package com.alx.moneytracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.entity.CategoryEntity
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import com.alx.moneytracker.data.local.entity.TransactionEntity
import com.alx.moneytracker.data.local.entity.WalletEntity

/**
 * Room database for the Transaction Input Engine (Requirement 12.1).
 *
 * Registers the four local entities and exposes the four DAOs used by the repository:
 * - [TransactionEntity] via [transactionDao] — the atomic insert-log + apply-balance boundary.
 * - [WalletEntity] via [walletDao] — observed active/default wallets.
 * - [CategoryEntity] via [categoryDao] — categories filtered by transaction type.
 * - [QuickPresetEntity] via [quickPresetDao] — preset chip set.
 *
 * `exportSchema` is disabled because schema history export is not needed for this scope.
 */
@Database(
    entities = [
        TransactionEntity::class,
        WalletEntity::class,
        CategoryEntity::class,
        QuickPresetEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /** DAO for the `transactions` table and the atomic persistence operation. */
    abstract fun transactionDao(): TransactionDao

    /** DAO for the `wallets` table. */
    abstract fun walletDao(): WalletDao

    /** DAO for the `categories` table. */
    abstract fun categoryDao(): CategoryDao

    /** DAO for the `quick_presets` table. */
    abstract fun quickPresetDao(): QuickPresetDao

    companion object {
        /** Database file name used when building a persistent instance. */
        const val DATABASE_NAME: String = "alx_money_tracker.db"
    }
}
