package com.alx.moneytracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the `transactions` table.
 *
 * The field set is a superset of the sync payload so a later synchronization scope can map it
 * directly. This feature only guarantees [isSynced] = 0 (unsynced) on write.
 *
 * - id: UUID string primary key (Requirement 10.1).
 * - timestamp: epoch-millisecond creation time (Requirement 10.2).
 * - type: [com.alx.moneytracker.domain.TransactionType] name (Requirement 10.3).
 * - amount: non-negative integer in the smallest currency unit.
 * - sourceWalletId: source wallet id (Requirement 10.3).
 * - destWalletId: destination wallet id, non-null only for TRANSFER (Requirements 10.4/10.5).
 * - categoryId: selected category id (Requirement 10.3).
 * - note: free text, "" when omitted (Requirement 6.4).
 * - isSynced: 0 = unsynced, 1 = synced (Requirement 11.1).
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val type: String,
    val amount: Long,
    @ColumnInfo(name = "source_wallet") val sourceWalletId: Long,
    @ColumnInfo(name = "dest_wallet") val destWalletId: Long?,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val note: String,
    @ColumnInfo(name = "is_synced") val isSynced: Int
)
