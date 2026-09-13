package com.alx.moneytracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the `wallets` table (a "kantong" / store of monetary value).
 *
 * - id: auto-generated primary key.
 * - name: display name of the wallet.
 * - balance: current balance in the smallest currency unit.
 * - isDefault: marks the default source wallet for new transactions (Requirement 4.1).
 * - isArchived: excludes the wallet from selectable lists when true (Requirement 4.2).
 */
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val balance: Long,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false
)
