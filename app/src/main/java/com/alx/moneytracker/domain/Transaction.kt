package com.alx.moneytracker.domain

/**
 * A financial record persisted by the Input Engine.
 *
 * Amounts are expressed as non-negative integers in the smallest currency unit
 * (e.g. rupiah), never floating point, to avoid rounding drift.
 *
 * @property id UUID string assigned on persistence (Requirement 10.1).
 * @property timestamp Epoch-millisecond creation time (Requirement 10.2).
 * @property type The [TransactionType] of this record.
 * @property amount The transaction amount in the smallest currency unit; `> 0` when persisted.
 * @property sourceWalletId The wallet the amount is drawn from.
 * @property destWalletId The destination wallet; non-null only for [TransactionType.TRANSFER]
 *   (Requirements 10.4 / 10.5).
 * @property categoryId The selected category classifying this transaction.
 * @property note Free-text note; `""` when omitted (Requirement 6.4).
 * @property isSynced Whether the record has been transmitted to the backend; `false` (=0) on
 *   creation (Requirement 11).
 */
data class Transaction(
    val id: String,
    val timestamp: Long,
    val type: TransactionType,
    val amount: Long,
    val sourceWalletId: Long,
    val destWalletId: Long?,
    val categoryId: Long,
    val note: String,
    val isSynced: Boolean
)
