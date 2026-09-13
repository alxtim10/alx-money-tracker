package com.alx.moneytracker.data.local

import com.alx.moneytracker.data.local.entity.CategoryEntity
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import com.alx.moneytracker.data.local.entity.TransactionEntity
import com.alx.moneytracker.data.local.entity.WalletEntity
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet

/**
 * Pure mapping functions between Room entities and Android-independent domain models.
 *
 * Conventions bridged here:
 * - [TransactionType] <-> `String` via [Enum.name] / [TransactionType.valueOf] (Requirement 10.3).
 * - [TransactionEntity.isSynced] `Int` (0/1) <-> [Transaction.isSynced] `Boolean` (Requirement 11.1).
 *
 * The domain package stays free of Room types; these functions live in the data layer.
 */

// region Transaction

/** Maps a [TransactionEntity] to its [Transaction] domain model. */
fun TransactionEntity.toDomain(): Transaction = Transaction(
    id = id,
    timestamp = timestamp,
    type = TransactionType.valueOf(type),
    amount = amount,
    sourceWalletId = sourceWalletId,
    destWalletId = destWalletId,
    categoryId = categoryId,
    note = note,
    isSynced = isSynced != 0
)

/** Maps a [Transaction] domain model to its [TransactionEntity] for persistence. */
fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    timestamp = timestamp,
    type = type.name,
    amount = amount,
    sourceWalletId = sourceWalletId,
    destWalletId = destWalletId,
    categoryId = categoryId,
    note = note,
    isSynced = if (isSynced) 1 else 0
)

// endregion

// region Wallet

/** Maps a [WalletEntity] to its [Wallet] domain model. */
fun WalletEntity.toDomain(): Wallet = Wallet(
    id = id,
    name = name,
    balance = balance,
    isDefault = isDefault,
    isArchived = isArchived
)

// endregion

// region Category

/** Maps a [CategoryEntity] to its [Category] domain model, resolving [type] via enum name. */
fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    type = TransactionType.valueOf(type),
    icon = icon,
    isArchived = isArchived
)

// endregion

// region QuickPreset

/** Maps a [QuickPresetEntity] to its [QuickPreset] domain model. */
fun QuickPresetEntity.toDomain(): QuickPreset = QuickPreset(
    id = id,
    amount = amount,
    label = label
)

// endregion
